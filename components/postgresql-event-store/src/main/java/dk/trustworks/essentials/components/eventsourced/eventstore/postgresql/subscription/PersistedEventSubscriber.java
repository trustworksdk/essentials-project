/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.shared.Exceptions;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.reactivestreams.Subscription;
import org.slf4j.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Generic {@link BaseSubscriber} which forwards to the provided {@link PersistedEventHandler}
 * with backpressure and handles indefinite retries in relation to {@link IOExceptionUtil#isIOException(Throwable)},
 * if using constructor without any specified {@link RetryBackoffSpec}, updates {@link SubscriptionResumePoint} after each event handled
 * and applies the {@link SubscriptionErrorPolicy} to any non-retryable Exceptions (as specified by the {@link RetryBackoffSpec}):
 * <ul>
 *     <li>{@link SubscriptionErrorPolicy.Mode#SKIP} (default) - delegates to the provided <code>onErrorHandler</code>,
 *     which is responsible for error handling and for calling {@link EventStoreSubscription#request(long)} to continue event processing</li>
 *     <li>{@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP} - calls the handler again up to N times, then delegates to the <code>onErrorHandler</code></li>
 *     <li>{@link SubscriptionErrorPolicy.Mode#STOP} - keeps the resume point at the failed event, logs at ERROR and stops handling events
 *     (see {@link #isStoppedByErrorPolicy()})</li>
 * </ul>
 * Before the policy skips or stops, the event handler may take the failed event over instead - see
 * {@link PersistedEventHandler#handOffFailedEvent(PersistedEvent, Throwable)}.
 * <p>
 * A failure that surfaces after this subscriber was disposed (stop, fenced-lock release, {@code resetFrom}, unsubscribe) is none of these:
 * the retries are abandoned, neither the <code>onErrorHandler</code> nor the observer is told, and the resume point stays at the event, so the
 * restarted subscription handles it again (see {@link SubscriptionStoppedDuringRetryException}).
 * <p>
 * Acknowledgement: the subscriber reports every event it is done with through its {@link SubscriberAcknowledgement}
 * (see {@link PersistedEventSubscriberBuilder#setSubscriberAcknowledgement(SubscriberAcknowledgement)}) - an event it handled inside the
 * unit of work that handled it, after the event handler returned, so an event store that honours the acknowledgement resolves a gap fill's
 * transient gap atomically with the handling; an event the {@link SubscriptionErrorPolicy} skips, or the event handler takes over
 * ({@link PersistedEventHandler#handOffFailedEvent}), once that is decided. An event it stops at ({@link SubscriptionErrorPolicy.Mode#STOP}),
 * was stopped while handling or retrying, or ignores because it has stopped, is not acknowledged: it is owed to the restarted subscription,
 * and a gap fill among them keeps its gap. Only while the event store does not honour the acknowledgement ({@link SubscriberAcknowledgement#isHonoured()}
 * is false - it resolves a gap fill's gap once it handed the event on) does an event ignored after a stop lower the held resume point to it,
 * as the only way to have the restarted subscription handle a gap fill below it.
 */
public class PersistedEventSubscriber extends BaseSubscriber<PersistedEvent> {
    private static final Logger log = LoggerFactory.getLogger(PersistedEventSubscriber.class);

    private final PersistedEventHandler eventHandler;
    private final EventStoreSubscription eventStoreSubscription;
    private final BiConsumer<PersistedEvent, Throwable> onErrorHandler;
    private final RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec;
    private final long eventStorePollingBatchSize;
    private final EventStore eventStore;
    private final SubscriptionErrorPolicy subscriptionErrorPolicy;
    /**
     * Reports every event this subscriber is done with - see the class javadoc
     */
    private final SubscriberAcknowledgement acknowledgement;
    /**
     * The resume point of the subscription incarnation this subscriber serves. Captured once, rather than read from
     * {@link EventStoreSubscription#currentResumePoint()} each time: a restarted subscription (fenced-lock re-acquire,
     * {@code resetFrom}) replaces it, and a handler of this - by then disposed - subscriber that completes or fails late
     * must not advance or rewind the resume point of its successor
     */
    private final SubscriptionResumePoint resumePoint;
    /**
     * Guards the resume point against an event completing concurrently with {@link #holdResumePointAt(PersistedEvent)}:
     * I/O retries complete asynchronously, so a later event can finish while an earlier one is failing
     */
    private final Object resumePointLock = new Object();
    private volatile boolean stoppedByErrorPolicy;
    /**
     * Set once the resume point must stay where {@link #holdResumePointAt(PersistedEvent)} left it: by a
     * {@link SubscriptionErrorPolicy.Mode#STOP}, or by a stop that interrupted the handling of an event
     */
    private volatile boolean resumePointHeld;
    /**
     * Set when the upstream completed, which also marks this subscriber disposed - see {@link #isStopped()}
     */
    private volatile boolean completed;

    /**
     * Create a {@link PersistedEventSubscriberBuilder} that names every argument.
     *
     * @return the builder
     */
    public static PersistedEventSubscriberBuilder builder() {
        return new PersistedEventSubscriberBuilder();
    }

    /**
     * Subscribe with indefinite retries in relation to Exceptions where {@link IOExceptionUtil#isIOException(Throwable)} return true
     *
     * @param eventHandler               The event handler that {@link PersistedEvent}'s are forwarded to
     * @param eventStoreSubscription     the {@link EventStoreSubscription} (as created by {@link EventStoreSubscriptionManager})
     * @param onErrorHandler             The error handler called for any non-retryable Exceptions (as specified by the {@link RetryBackoffSpec})<br>
     *                                   <b>Note: Default behaviour needs to at least request one more event</b><br>
     *                                   Similar to:
     *                                   <pre>{@code
     *                                                                     void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
     *                                                                          log.error(msg("[{}-{}] (#{}) Skipping {} event because of error",
     *                                                                                          subscriberId,
     *                                                                                          aggregateType,
     *                                                                                          e.globalEventOrder(),
     *                                                                                          e.event().getEventTypeOrName().getValue()), cause);
     *                                                                          log.trace("[{}-{}] (#{}) Requesting 1 event from the EventStore",
     *                                                                                      subscriberId(),
     *                                                                                      aggregateType(),
     *                                                                                      e.globalEventOrder()
     *                                                                                      );
     *                                                                          eventStoreSubscription.request(1);
     *                                                                     }
     *                                                                     }</pre>
     * @param eventStorePollingBatchSize The batch size used when polling events from the {@link EventStore}
     * @param eventStore                 The {@link EventStore} to use
     */
    public PersistedEventSubscriber(PersistedEventHandler eventHandler,
                                    EventStoreSubscription eventStoreSubscription,
                                    BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                                    long eventStorePollingBatchSize,
                                    EventStore eventStore) {
        this(eventHandler,
                eventStoreSubscription,
                onErrorHandler,
                Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(100)) // Initial delay of 100ms
                        .maxBackoff(Duration.ofSeconds(1)) // Maximum backoff of 1 second
                        .jitter(0.5)
                        .filter(IOExceptionUtil::isIOException),
                eventStorePollingBatchSize,
                eventStore);
    }

    /**
     * Subscribe with custom {@link RetryBackoffSpec}
     *
     * @param eventHandler                          The event handler that {@link PersistedEvent}'s are forwarded to
     * @param eventStoreSubscription                the {@link EventStoreSubscription} (as created by {@link EventStoreSubscriptionManager})
     * @param onErrorHandler                        The error handler called for any non-retryable Exceptions (as specified by the {@link RetryBackoffSpec})<br>
     *                                              <b>Note: Default behaviour needs to at least request one more event</b><br>
     *                                              Similar to:
     *                                              <pre>{@code
     *                                                                                           void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
     *                                                                                                log.error(msg("[{}-{}] (#{}) Skipping {} event because of error",
     *                                                                                                                subscriberId,
     *                                                                                                                aggregateType,
     *                                                                                                                e.globalEventOrder(),
     *                                                                                                                e.event().getEventTypeOrName().getValue()), cause);
     *                                                                                                log.trace("[{}-{}] (#{}) Requesting 1 event from the EventStore",
     *                                                                                                            subscriberId(),
     *                                                                                                            aggregateType(),
     *                                                                                                            e.globalEventOrder()
     *                                                                                                            );
     *                                                                                                eventStoreSubscription.request(1);
     *                                                                                           }
     *                                                                                           }</pre>
     * @param forwardToEventHandlerRetryBackoffSpec The {@link RetryBackoffSpec} used.<br>
     *                                              Example:
     *                                              <pre>{@code
     *                                                                                           Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(100)) // Initial delay of 100ms
     *                                                                                                .maxBackoff(Duration.ofSeconds(1)) // Maximum backoff of 1 second
     *                                                                                                .jitter(0.5)
     *                                                                                                .filter(IOExceptionUtil::isIOException)
     *                                                                                           }
     *                                                                                           </pre>
     * @param eventStorePollingBatchSize            The batch size used when polling events from the {@link EventStore}
     * @param eventStore                            The {@link EventStore} to use
     */
    PersistedEventSubscriber(PersistedEventHandler eventHandler,
                             EventStoreSubscription eventStoreSubscription,
                             BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                             RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec,
                             long eventStorePollingBatchSize,
                             EventStore eventStore) {
        this(eventHandler,
             eventStoreSubscription,
             onErrorHandler,
             forwardToEventHandlerRetryBackoffSpec,
             eventStorePollingBatchSize,
             eventStore,
             SubscriptionErrorPolicy.skip());
    }

    /**
     * Target of {@link PersistedEventSubscriberBuilder#build()}. The other parameters are described on
     * {@link #PersistedEventSubscriber(PersistedEventHandler, EventStoreSubscription, BiConsumer, RetryBackoffSpec, long, EventStore)}
     *
     * @param subscriptionErrorPolicy what to do when the event handler fails with an error the <code>forwardToEventHandlerRetryBackoffSpec</code>
     *                                doesn't retry. For {@link SubscriptionErrorPolicy.Mode#STOP} the <code>onErrorHandler</code> is <b>not</b>
     *                                called - the subscriber stops instead of skipping
     */
    PersistedEventSubscriber(PersistedEventHandler eventHandler,
                             EventStoreSubscription eventStoreSubscription,
                             BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                             RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec,
                             long eventStorePollingBatchSize,
                             EventStore eventStore,
                             SubscriptionErrorPolicy subscriptionErrorPolicy) {
        this(eventHandler,
             eventStoreSubscription,
             onErrorHandler,
             forwardToEventHandlerRetryBackoffSpec,
             eventStorePollingBatchSize,
             eventStore,
             subscriptionErrorPolicy,
             SubscriberAcknowledgement.create());
    }

    /**
     * Target of {@link PersistedEventSubscriberBuilder#build()}. The other parameters are described on
     * {@link #PersistedEventSubscriber(PersistedEventHandler, EventStoreSubscription, BiConsumer, RetryBackoffSpec, long, EventStore, SubscriptionErrorPolicy)}
     *
     * @param acknowledgement reports every event this subscriber is done with - pass the same one to the event store's
     *                        poll this subscriber subscribes to
     */
    PersistedEventSubscriber(PersistedEventHandler eventHandler,
                             EventStoreSubscription eventStoreSubscription,
                             BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                             RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec,
                             long eventStorePollingBatchSize,
                             EventStore eventStore,
                             SubscriptionErrorPolicy subscriptionErrorPolicy,
                             SubscriberAcknowledgement acknowledgement) {
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
        this.eventStoreSubscription = requireNonNull(eventStoreSubscription, "No eventStoreSubscription provided");
        this.onErrorHandler = requireNonNull(onErrorHandler, "No errorHandler provided");
        this.forwardToEventHandlerRetryBackoffSpec = SubscriptionErrorPolicyRetries.neverRetryingAStop(requireNonNull(forwardToEventHandlerRetryBackoffSpec, "No retryBackoffSpec provided"));
        this.eventStorePollingBatchSize = eventStorePollingBatchSize;
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.subscriptionErrorPolicy = requireNonNull(subscriptionErrorPolicy, "No subscriptionErrorPolicy provided");
        this.acknowledgement = requireNonNull(acknowledgement, "No acknowledgement provided");
        // Verify that the provided eventStoreSubscription supports resume-points
        this.resumePoint = eventStoreSubscription.currentResumePoint().orElseThrow(() -> new IllegalArgumentException(msg("The provided {} doesn't support resume-points", eventStoreSubscription.getClass().getName())));
    }

    /**
     * @return true if a failed event made this subscriber stop, as {@link SubscriptionErrorPolicy.Mode#STOP} prescribes.
     * A stopped subscriber handles no further events; the subscription must be started again to continue
     */
    public boolean isStoppedByErrorPolicy() {
        return stoppedByErrorPolicy;
    }

    @Override
    protected void hookOnSubscribe(Subscription subscription) {
        log.debug("[{}-{}] On Subscribe with eventStorePollingBatchSize {}",
                eventStoreSubscription.subscriberId(),
                eventStoreSubscription.aggregateType(),
                eventStorePollingBatchSize
        );
        eventStoreSubscription.request(eventStorePollingBatchSize);
    }

    @Override
    protected void hookOnComplete() {
        completed = true;
        super.hookOnComplete();
    }

    /**
     * @return true once this subscriber has been stopped: disposed (stop, fenced-lock release, {@code resetFrom},
     * unsubscribe) or terminated by an upstream error. Not {@link #isDisposed()} on its own - that is also true after
     * the upstream completed, and an event still being handled then must get the {@link SubscriptionErrorPolicy} like
     * any other. Once true it stays true, and it turns true before the upstream is cancelled, i.e. before the delivery
     * thread is interrupted
     */
    private boolean isStopped() {
        return isDisposed() && !completed;
    }

    @Override
    protected void hookOnNext(PersistedEvent e) {
        if (resumePointHeld) {
            // Events already requested before the stop still arrive - they are left for the restarted subscription, and
            // not acknowledged: a gap fill among them keeps its gap, so the restarted subscription is handed it again.
            // Unless the event store resolved the gap when it handed the fill on (it does not honour the acknowledgement):
            // then the hold moves down to the fill, or the restarted subscription would resume above it and never see it
            var resumeFrom = acknowledgement.isHonoured() ? currentResumePoint() : holdResumePointAt(e);
            log.debug("[{}-{}] (#{}) Ignoring {} event - the subscriber has stopped (stopped by the {} SubscriptionErrorPolicy: {}). The resume point stays at #{}",
                      eventStoreSubscription.subscriberId(),
                      eventStoreSubscription.aggregateType(),
                      e.globalEventOrder(),
                      e.event().getEventTypeOrName().getValue(),
                      SubscriptionErrorPolicy.Mode.STOP,
                      stoppedByErrorPolicy,
                      resumeFrom);
            return;
        }
        // Outside the callable, so an I/O retry (which re-subscribes the callable) doesn't reset the policy's retry budget
        var policyRetriesPerformed = new AtomicInteger();
        var attemptsStarted        = new AtomicInteger();
        Mono.fromCallable(() -> {
                    if (attemptsStarted.getAndIncrement() > 0 && isStopped()) {
                        // An I/O retry must not outlive the subscriber - it would handle the event after the stop
                        throw new SubscriptionStoppedDuringRetryException(null);
                    }
                    log.trace("[{}-{}] (#{}) Forwarding {} event with eventId '{}', aggregateId: '{}', eventOrder: {} to EventHandler",
                            eventStoreSubscription.subscriberId(),
                            eventStoreSubscription.aggregateType(),
                            e.globalEventOrder(),
                            e.event().getEventTypeOrName().toString(),
                            e.eventId(),
                            e.aggregateId(),
                            e.eventOrder()
                    );
                    return SubscriptionErrorPolicyRetries.callRetryingPerPolicy(
                            () -> eventStore.getUnitOfWorkFactory()
                                            .withUnitOfWork(unitOfWork -> {
                                                var handleEventTiming = StopWatch.start("handleEvent (" + eventStoreSubscription.subscriberId() + ", " + eventStoreSubscription.aggregateType() + ")");
                                                var result = eventHandler.handleWithBackPressure(e);
                                                eventStore.getEventStoreSubscriptionObserver().handleEvent(e,
                                                        eventHandler,
                                                        eventStoreSubscription,
                                                        handleEventTiming.stop().getDuration()
                                                );
                                                // In the unit of work that handled it: a gap fill's gap is resolved atomically with the
                                                // handling, and stays open if this unit of work rolls back
                                                acknowledgement.acknowledge(e);
                                                return result;
                                            }),
                            subscriptionErrorPolicy,
                            forwardToEventHandlerRetryBackoffSpec,
                            policyRetriesPerformed,
                            this::isStopped,
                            (retryNumber, backoff, failure) -> log.warn("[{}-{}] (#{}) Handling {} event failed - performing retry {} of {} in {} ms ({} SubscriptionErrorPolicy): {}",
                                                                        eventStoreSubscription.subscriberId(),
                                                                        eventStoreSubscription.aggregateType(),
                                                                        e.globalEventOrder(),
                                                                        e.event().getEventTypeOrName().getValue(),
                                                                        retryNumber,
                                                                        subscriptionErrorPolicy.maxRetries(),
                                                                        backoff.toMillis(),
                                                                        subscriptionErrorPolicy.mode(),
                                                                        rootCauseDescription(failure)));
                })
                .retryWhen(forwardToEventHandlerRetryBackoffSpec
                        .doBeforeRetry(retrySignal -> {
                            log.trace("[{}-{}] (#{}) Ready to perform {} attempt retry of {} event with eventId '{}', aggregateId: '{}', eventOrder: {} to EventHandler",
                                    eventStoreSubscription.subscriberId(),
                                    eventStoreSubscription.aggregateType(),
                                    e.globalEventOrder(),
                                    retrySignal.totalRetries() + 1,
                                    e.event().getEventTypeOrName().getValue(),
                                    e.eventId(),
                                    e.aggregateId(),
                                    e.eventOrder()
                            );

                        })
                        .doAfterRetry(retrySignal -> {
                            log.debug("[{}-{}] (#{}) {} {} retry of {} event with eventId '{}', aggregateId: '{}', eventOrder: {} to EventHandler",
                                    eventStoreSubscription.subscriberId(),
                                    eventStoreSubscription.aggregateType(),
                                    e.globalEventOrder(),
                                    retrySignal.failure() != null ? "Failed" : "Succeeded",
                                    retrySignal.totalRetries(),
                                    e.event().getEventTypeOrName().getValue(),
                                    e.eventId(),
                                    e.aggregateId(),
                                    e.eventOrder(),
                                    retrySignal.failure()
                            );
                        }))
                .doFinally(signalType -> {
                    // Runs after the error consumer below, so a STOP (or a stop mid-retry) has already been recorded when an event fails
                    synchronized (resumePointLock) {
                        if (!resumePointHeld) {
                            // advance (not set): gap-filled events are delivered out of order, so an older
                            // event can complete last and must not rewind the resume point. A gap fill's gap was
                            // resolved with its acknowledgement, in the unit of work that handled it (or, by an event
                            // store that does not honour the acknowledgement, once hookOnNext returned)
                            resumePoint.advanceResumeFromAndIncluding(e.globalEventOrder().increment());
                        }
                    }
                })
                .subscribe(requestSize -> {
                            if (requestSize < 0) {
                                requestSize = 1;
                            }
                            log.trace("[{}-{}] (#{}) Requesting {} events from the EventStore",
                                    eventStoreSubscription.subscriberId(),
                                    eventStoreSubscription.aggregateType(),
                                    e.globalEventOrder(),
                                    requestSize
                            );
                            if (requestSize > 0) {
                                eventStoreSubscription.request(requestSize);
                            }
                        },
                        error -> {
                            var failure = SubscriptionErrorPolicyRetries.unwrapRetryExhausted(error);
                            if (isStopped()) {
                                // Not a verdict on the event - the subscriber was stopped under it (see SubscriptionStoppedDuringRetryException,
                                // which is only ever thrown once isStopped() is true). Holding the resume point of a subscriber that is still
                                // running would make it ignore every later event, unseen by isActive(), isStoppedByErrorPolicy() or the observer
                                stoppedWhileHandling(e, failure);
                                return;
                            }
                            if (handedOffToEventHandler(e, failure)) {
                                // Taken over by the event handler - done with here
                                acknowledgeGivenUp(e);
                                eventStoreSubscription.request(1);
                                return;
                            }
                            eventStore.getEventStoreSubscriptionObserver().handleEventFailed(e,
                                    eventHandler,
                                    failure,
                                    eventStoreSubscription);
                            if (subscriptionErrorPolicy.stopsOnError()) {
                                // Not acknowledged: the restarted subscription resumes at it
                                stopAt(e, failure.getCause() != null ? failure.getCause() : failure);
                            } else {
                                // Skipped - done with
                                acknowledgeGivenUp(e);
                                onErrorHandler.accept(e, failure.getCause());
                            }
                        });
    }

    /**
     * Acknowledge an event the subscriber gave up on - skipped by the {@link SubscriptionErrorPolicy}, or taken over by the
     * event handler. Outside any unit of work: the one that handled it rolled back. A failure only leaves a gap fill's gap
     * open, which delivers the event again to a later subscription, so it is logged rather than stopping the subscriber.
     */
    private void acknowledgeGivenUp(PersistedEvent e) {
        try {
            acknowledgement.acknowledge(e);
        } catch (RuntimeException acknowledgementFailure) {
            log.warn(msg("[{}-{}] (#{}) Could not acknowledge the {} event the subscriber gave up on - if it fills a gap, the gap stays open and a later subscription is handed the event again",
                         eventStoreSubscription.subscriberId(),
                         eventStoreSubscription.aggregateType(),
                         e.globalEventOrder(),
                         e.event().getEventTypeOrName().getValue()), acknowledgementFailure);
        }
    }

    private GlobalEventOrder currentResumePoint() {
        synchronized (resumePointLock) {
            return resumePoint.getResumeFromAndIncluding();
        }
    }

    /**
     * Offer the failed event to {@link PersistedEventHandler#handOffFailedEvent(PersistedEvent, Throwable)} before the
     * {@link SubscriptionErrorPolicy} gives up on it. Runs before the {@code doFinally} that advances the resume point, so
     * the resume point only moves past the event once the handler has taken it over
     *
     * @return true if the handler took the event over
     */
    private boolean handedOffToEventHandler(PersistedEvent e, Throwable failure) {
        try {
            if (eventHandler.handOffFailedEvent(e, failure)) {
                log.debug("[{}-{}] (#{}) The {} event handler took over the failed {} event - the {} SubscriptionErrorPolicy does not give up on it",
                          eventStoreSubscription.subscriberId(),
                          eventStoreSubscription.aggregateType(),
                          e.globalEventOrder(),
                          eventHandler,
                          e.event().getEventTypeOrName().getValue(),
                          subscriptionErrorPolicy.mode());
                return true;
            }
        } catch (RuntimeException handOffFailure) {
            failure.addSuppressed(handOffFailure);
            log.warn(msg("[{}-{}] (#{}) The event handler failed to take over the failed {} event - the {} SubscriptionErrorPolicy applies",
                         eventStoreSubscription.subscriberId(),
                         eventStoreSubscription.aggregateType(),
                         e.globalEventOrder(),
                         e.event().getEventTypeOrName().getValue(),
                         subscriptionErrorPolicy.mode()), handOffFailure);
        }
        return false;
    }

    /**
     * {@link SubscriptionErrorPolicy.Mode#STOP}: keep the resume point at the failed event (see {@link #holdResumePointAt(PersistedEvent)}),
     * notify {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver#subscriptionStoppedByErrorPolicy}
     * and stop handling events.
     * <p>
     * The upstream is cancelled asynchronously: this runs on the delivery thread, and cancelling a polling flux from its
     * own thread would interrupt the poll that is delivering to us.
     */
    private void stopAt(PersistedEvent e, Throwable cause) {
        // Set before the resume point is held, so the observer below already sees isStoppedByErrorPolicy() == true
        stoppedByErrorPolicy = true;
        var resumeFrom = holdResumePointAt(e);
        log.error(msg("[{}-{}] (#{}) Stopping the subscription because handling the {} event failed and the SubscriptionErrorPolicy is {}. " +
                              "The resume point stays at #{}, so no event is skipped: no further events are handled until the subscription is started again " +
                              "(restart, fenced lock hand-over, resetFrom or unsubscribe/subscribe), and it then resumes at this event",
                      eventStoreSubscription.subscriberId(),
                      eventStoreSubscription.aggregateType(),
                      e.globalEventOrder(),
                      e.event().getEventTypeOrName().getValue(),
                      SubscriptionErrorPolicy.Mode.STOP,
                      resumeFrom), cause);
        try {
            eventStore.getEventStoreSubscriptionObserver().subscriptionStoppedByErrorPolicy(e.globalEventOrder(), cause, eventStoreSubscription);
        } catch (RuntimeException observerFailure) {
            log.warn(msg("[{}-{}] EventStoreSubscriptionObserver#subscriptionStoppedByErrorPolicy failed",
                         eventStoreSubscription.subscriberId(),
                         eventStoreSubscription.aggregateType()), observerFailure);
        }
        Schedulers.boundedElastic().schedule(this::dispose);
    }

    /**
     * The subscriber was stopped (disposed) while <code>e</code> was being handled or retried: leave the resume point at
     * <code>e</code>, so the restarted subscription handles it again, instead of skipping it.
     */
    private void stoppedWhileHandling(PersistedEvent e, Throwable failure) {
        var resumeFrom = holdResumePointAt(e);
        log.info("[{}-{}] (#{}) The subscriber was stopped while handling the {} event was being retried or was in progress ({}) - " +
                         "the event is not skipped: the resume point stays at #{} and the event is handled again when the subscription is started again",
                 eventStoreSubscription.subscriberId(),
                 eventStoreSubscription.aggregateType(),
                 e.globalEventOrder(),
                 e.event().getEventTypeOrName().getValue(),
                 rootCauseDescription(failure),
                 resumeFrom);
    }

    /**
     * Keep the resume point at <code>e</code> and stop later completions from advancing it.
     * <p>
     * The resume point is moved <i>back</i> to <code>e</code> if it is already past it - which happens when a later
     * event completed first (an I/O retry of <code>e</code>, or a gap-filled event arriving late). Redelivering
     * events after it on restart is at-least-once and harmless; resuming past it would skip it.
     *
     * @return the resume point after holding it
     */
    private GlobalEventOrder holdResumePointAt(PersistedEvent e) {
        synchronized (resumePointLock) {
            resumePointHeld = true;
            if (resumePoint.getResumeFromAndIncluding().longValue() > e.globalEventOrder().longValue()) {
                resumePoint.setResumeFromAndIncluding(e.globalEventOrder());
            }
            return resumePoint.getResumeFromAndIncluding();
        }
    }

    private static String rootCauseDescription(Throwable failure) {
        var rootCause = Exceptions.getRootCause(failure);
        return rootCause.getClass().getName() + ": " + rootCause.getMessage();
    }
}

