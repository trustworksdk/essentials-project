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
import dk.trustworks.essentials.shared.collections.Lists;
import org.reactivestreams.Subscription;
import org.slf4j.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.*;
import reactor.util.retry.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.BiConsumer;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Batching alternative to the {@link PersistedEventSubscriber} that processes events in batches
 * to improve throughput and reduce database load. This is a specialized subscriber
 * that should be used when high throughput is needed and the event handlers can
 * efficiently process batches of events.
 * <p>
 * Key features:
 * <ul>
 *   <li>Processes events in batches for improved throughput</li>
 *   <li>Uses the same retry mechanism as PersistedEventSubscriber</li>
 *   <li>Tracks batch progress and updates resume points only after entire batch completes</li>
 *   <li>Only requests more events after batch processing completes</li>
 *   <li>Supports max latency for processing partial batches</li>
 * </ul>
 * Batches are handled on a single thread owned by this subscriber (not a JVM-wide scheduler), so a batch whose
 * {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP} retries are backing off holds up only this subscription.
 * <p>
 * A failure that surfaces after this subscriber was disposed (stop, {@code resetFrom}, unsubscribe) is not a verdict on
 * the batch: neither the <code>onErrorHandler</code> nor the observer is told, and the resume point stays at the batch,
 * so the restarted subscription handles it again (see {@link SubscriptionStoppedDuringRetryException}).
 * <p>
 * Acknowledgement: the subscriber acknowledges a batch through its {@link SubscriberAcknowledgement} (see
 * {@link BatchedPersistedEventSubscriberBuilder#setSubscriberAcknowledgement(SubscriberAcknowledgement)}) inside the unit of
 * work that handled it, after {@link BatchedPersistedEventHandler#handleBatch(List)} returned - so an event store that
 * honours the acknowledgement resolves the transient gaps of the gap fills in it atomically with the batch - and a batch
 * the {@link SubscriptionErrorPolicy} skips once that is decided. Events still collected for a batch, a batch it stops at
 * or was stopped while handling, and events it ignores because it has stopped are not acknowledged: they are owed to the
 * restarted subscription, and a gap fill among them keeps its gap. Only while the event store does not honour the
 * acknowledgement ({@link SubscriberAcknowledgement#isHonoured()} is false - it resolves a gap fill's gap once it handed the
 * event on) does the subscriber protect such a gap fill by holding its resume point at it instead.
 */
public class BatchedPersistedEventSubscriber extends BaseSubscriber<PersistedEvent> {
    private static final Logger log = LoggerFactory.getLogger(BatchedPersistedEventSubscriber.class);

    private final BatchedPersistedEventHandler          eventHandler;
    private final EventStoreSubscription                eventStoreSubscription;
    private final BiConsumer<PersistedEvent, Throwable> onErrorHandler;
    private final RetryBackoffSpec                      forwardToEventHandlerRetryBackoffSpec;
    private final long                                  eventStorePollingBatchSize;
    private final EventStore                            eventStore;
    private final int                                   maxBatchSize;
    private final Duration                              maxLatency;
    private final SubscriptionErrorPolicy               subscriptionErrorPolicy;
    /**
     * Reports every batch this subscriber is done with - see the class javadoc
     */
    private final SubscriberAcknowledgement             acknowledgement;
    /**
     * The resume point of the subscription incarnation this subscriber serves - captured once, so a batch of this
     * (by then disposed) subscriber that completes or fails late cannot move the resume point of a restarted
     * subscription. See {@code PersistedEventSubscriber#resumePoint}
     */
    private final SubscriptionResumePoint               resumePoint;
    /**
     * Guards the resume point against a batch completing concurrently with {@link #holdResumePointAt(PersistedEvent)}
     */
    private final Object                                resumePointLock = new Object();
    private volatile boolean                            stoppedByErrorPolicy;
    /**
     * Set once the resume point must stay where {@link #holdResumePointAt(PersistedEvent)} left it: by a
     * {@link SubscriptionErrorPolicy.Mode#STOP}, or by a stop that interrupted the handling of a batch
     */
    private volatile boolean                            resumePointHeld;
    /**
     * Set when the upstream completed, which also marks this subscriber disposed - see {@link #isStopped()}
     */
    private volatile boolean                            completed;

    // Thread-safe priority queue of events being collected for a batch, sorted by global event order
    private final ConcurrentLinkedQueue<PersistedEvent> eventQueue;
    private final AtomicInteger                         queueSize;
    private final Lock                                  processingLock;
    private final ScheduledExecutorService              scheduler;
    /**
     * Runs {@link BatchedPersistedEventHandler#handleBatch(List)}, including the synchronous {@link SubscriptionErrorPolicy} retries.
     * Owned by this subscriber: the retries sleep on it, so it must not be a thread other subscriptions depend on
     */
    private final Scheduler                             batchHandlerScheduler;
    private final AtomicReference<ScheduledFuture<?>>   scheduledProcessing;
    private final AtomicLong                            lastEventTimestamp;

    /**
     * Create a {@link BatchedPersistedEventSubscriberBuilder} that names every argument.
     *
     * @return the builder
     */
    public static BatchedPersistedEventSubscriberBuilder builder() {
        return new BatchedPersistedEventSubscriberBuilder();
    }

    /**
     * Subscribe with indefinite retries in relation to Exceptions where {@link IOExceptionUtil#isIOException(Throwable)} return true
     *
     * @param eventHandler               The event handler that batches of {@link PersistedEvent}'s are forwarded to
     * @param eventStoreSubscription     the {@link EventStoreSubscription} (as created by {@link EventStoreSubscriptionManager})
     * @param onErrorHandler             The error handler called for any non-retryable Exceptions (as specified by the {@link RetryBackoffSpec})
     *                                   Similar to the {@link PersistedEventSubscriber} error handler
     * @param eventStorePollingBatchSize The batch size used when polling events from the {@link EventStore}
     * @param eventStore                 The {@link EventStore} to use
     * @param maxBatchSize               The maximum number of events to include in a batch before processing
     * @param maxLatency                 The maximum time to wait before processing a partial batch
     */
    BatchedPersistedEventSubscriber(BatchedPersistedEventHandler eventHandler,
                             EventStoreSubscription eventStoreSubscription,
                             BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                             long eventStorePollingBatchSize,
                             EventStore eventStore,
                             int maxBatchSize,
                             Duration maxLatency) {
        this(eventHandler,
             eventStoreSubscription,
             onErrorHandler,
             Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(100)) // Initial delay of 100ms
                  .maxBackoff(Duration.ofSeconds(1)) // Maximum backoff of 1 second
                  .jitter(0.5)
                  .filter(IOExceptionUtil::isIOException),
             eventStorePollingBatchSize,
             eventStore,
             maxBatchSize,
             maxLatency);
    }

    /**
     * Subscribe with custom {@link RetryBackoffSpec}
     *
     * @param eventHandler                          The event handler that batches of {@link PersistedEvent}'s are forwarded to
     * @param eventStoreSubscription                the {@link EventStoreSubscription} (as created by {@link EventStoreSubscriptionManager})
     * @param onErrorHandler                        The error handler called for any non-retryable Exceptions (as specified by the {@link RetryBackoffSpec})
     * @param forwardToEventHandlerRetryBackoffSpec The {@link RetryBackoffSpec} used
     * @param eventStorePollingBatchSize            The batch size used when polling events from the {@link EventStore}
     * @param eventStore                            The {@link EventStore} to use
     * @param maxBatchSize                          The maximum number of events to include in a batch before processing
     * @param maxLatency                            The maximum time to wait before processing a partial batch
     */
    BatchedPersistedEventSubscriber(BatchedPersistedEventHandler eventHandler,
                                           EventStoreSubscription eventStoreSubscription,
                                           BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                                           RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec,
                                           long eventStorePollingBatchSize,
                                           EventStore eventStore,
                                           int maxBatchSize,
                                           Duration maxLatency) {
        this(eventHandler,
             eventStoreSubscription,
             onErrorHandler,
             forwardToEventHandlerRetryBackoffSpec,
             eventStorePollingBatchSize,
             eventStore,
             maxBatchSize,
             maxLatency,
             SubscriptionErrorPolicy.skip());
    }

    /**
     * Target of {@link BatchedPersistedEventSubscriberBuilder#build()}. The other parameters are described on
     * {@link #BatchedPersistedEventSubscriber(BatchedPersistedEventHandler, EventStoreSubscription, BiConsumer, RetryBackoffSpec, long, EventStore, int, Duration)}
     *
     * @param subscriptionErrorPolicy what to do when handling a batch fails with an error the <code>forwardToEventHandlerRetryBackoffSpec</code>
     *                                doesn't retry. The policy applies to the batch as a whole: {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP}
     *                                retries the whole batch, {@link SubscriptionErrorPolicy.Mode#STOP} stops at the first event of the batch
     */
    BatchedPersistedEventSubscriber(BatchedPersistedEventHandler eventHandler,
                                    EventStoreSubscription eventStoreSubscription,
                                    BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                                    RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec,
                                    long eventStorePollingBatchSize,
                                    EventStore eventStore,
                                    int maxBatchSize,
                                    Duration maxLatency,
                                    SubscriptionErrorPolicy subscriptionErrorPolicy) {
        this(eventHandler,
             eventStoreSubscription,
             onErrorHandler,
             forwardToEventHandlerRetryBackoffSpec,
             eventStorePollingBatchSize,
             eventStore,
             maxBatchSize,
             maxLatency,
             subscriptionErrorPolicy,
             SubscriberAcknowledgement.create());
    }

    /**
     * Target of {@link BatchedPersistedEventSubscriberBuilder#build()}. The other parameters are described on
     * {@link #BatchedPersistedEventSubscriber(BatchedPersistedEventHandler, EventStoreSubscription, BiConsumer, RetryBackoffSpec, long, EventStore, int, Duration, SubscriptionErrorPolicy)}
     *
     * @param acknowledgement reports every batch this subscriber is done with - pass the same one to the event store's
     *                        poll this subscriber subscribes to
     */
    BatchedPersistedEventSubscriber(BatchedPersistedEventHandler eventHandler,
                                    EventStoreSubscription eventStoreSubscription,
                                    BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                                    RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec,
                                    long eventStorePollingBatchSize,
                                    EventStore eventStore,
                                    int maxBatchSize,
                                    Duration maxLatency,
                                    SubscriptionErrorPolicy subscriptionErrorPolicy,
                                    SubscriberAcknowledgement acknowledgement) {
        this.subscriptionErrorPolicy = requireNonNull(subscriptionErrorPolicy, "No subscriptionErrorPolicy provided");
        this.acknowledgement = requireNonNull(acknowledgement, "No acknowledgement provided");
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
        this.eventStoreSubscription = requireNonNull(eventStoreSubscription, "No eventStoreSubscription provided");
        this.onErrorHandler = requireNonNull(onErrorHandler, "No errorHandler provided");
        this.forwardToEventHandlerRetryBackoffSpec = SubscriptionErrorPolicyRetries.neverRetryingAStop(requireNonNull(forwardToEventHandlerRetryBackoffSpec, "No retryBackoffSpec provided"));
        this.eventStorePollingBatchSize = eventStorePollingBatchSize;
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.maxBatchSize = maxBatchSize;
        this.maxLatency = requireNonNull(maxLatency, "No maxLatency provided");

        requireTrue(eventStorePollingBatchSize > 0, "eventStorePollingBatchSize must be > 0");
        requireTrue(maxBatchSize > 0, "maxBatchSize must be > 0");

        // Verify that the provided eventStoreSubscription supports resume-points
        this.resumePoint = eventStoreSubscription.currentResumePoint().orElseThrow(() ->
                                                                        new IllegalArgumentException(msg("The provided {} doesn't support resume-points",
                                                                                                         eventStoreSubscription.getClass().getName())));

        // Initialize thread-safe collections and scheduler
        this.eventQueue = new ConcurrentLinkedQueue<>();
        this.queueSize = new AtomicInteger(0);
        this.processingLock = new ReentrantLock();
        this.lastEventTimestamp = new AtomicLong(System.currentTimeMillis());

        // Create a daemon scheduler for the latency-based batch processing
        var executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r);
            thread.setName("BatchedEventSubscriber-" + eventStoreSubscription.subscriberId() + "-Timer");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.scheduler = executor;
        this.batchHandlerScheduler = Schedulers.newSingle("BatchedEventSubscriber-" + eventStoreSubscription.subscriberId() + "-" + eventStoreSubscription.aggregateType() + "-Handler", true);
        this.scheduledProcessing = new AtomicReference<>();

        // Schedule the first check for partial batches
        schedulePartialBatchProcessing();
    }

    @Override
    protected void hookOnSubscribe(Subscription subscription) {
        log.debug("[{}-{}] On Subscribe with eventStorePollingBatchSize {}, maxBatchSize {}, maxLatency {}",
                  eventStoreSubscription.subscriberId(),
                  eventStoreSubscription.aggregateType(),
                  eventStorePollingBatchSize,
                  maxBatchSize,
                  maxLatency
                 );
        eventStoreSubscription.request(eventStorePollingBatchSize);
    }

    @Override
    protected void hookOnNext(PersistedEvent event) {
        if (resumePointHeld) {
            // Events already requested before the stop still arrive - they are left for the restarted subscription, and not
            // acknowledged. Only if the event store does not honour the acknowledgement does the hold move down to a gap
            // fill below it (see holdResumePointBelow)
            var resumeFrom = acknowledgement.isHonoured() ? currentResumePoint() : holdResumePointAt(event);
            log.debug("[{}-{}] Ignoring event #{} - the subscriber has stopped (stopped by the {} SubscriptionErrorPolicy: {}). The resume point stays at #{}",
                      eventStoreSubscription.subscriberId(),
                      eventStoreSubscription.aggregateType(),
                      event.globalEventOrder(),
                      SubscriptionErrorPolicy.Mode.STOP,
                      stoppedByErrorPolicy,
                      resumeFrom);
            return;
        }
        // Add the event to our queue
        eventQueue.add(event);
        int currentSize = queueSize.incrementAndGet();
        lastEventTimestamp.set(System.currentTimeMillis());

        log.trace("[{}-{}] Added event #{} to batch (batch size: {}/{})",
                  eventStoreSubscription.subscriberId(),
                  eventStoreSubscription.aggregateType(),
                  event.globalEventOrder(),
                  currentSize,
                  maxBatchSize);

        // Process the batch if we've reached max batch size
        if (currentSize >= maxBatchSize) {
            processBatchIfNotAlreadyProcessing();
        }
    }

    @Override
    protected void hookOnComplete() {
        // Before the final batch below: it is handled under the SubscriptionErrorPolicy, not as a stop (see isStopped())
        completed = true;
        // Process any remaining events in the batch when the stream completes
        if (!eventQueue.isEmpty()) {
            processBatchIfNotAlreadyProcessing();
        }

        // Clean up the scheduler
        cancelScheduledProcessing();
        scheduler.shutdown();
        // Gracefully: the final batch scheduled above must still be handled
        batchHandlerScheduler.disposeGracefully().subscribe();

        super.hookOnComplete();
    }

    @Override
    protected void hookOnCancel() {
        // What is still collected for a batch is never handled by this subscriber - nor acknowledged
        holdResumePointBelow(List.copyOf(eventQueue));
        // Clean up the scheduler
        cancelScheduledProcessing();
        scheduler.shutdown();
        // Interrupts a batch in its retry backoff, which then leaves the resume point at the batch (see SubscriptionStoppedDuringRetryException)
        batchHandlerScheduler.dispose();

        super.hookOnCancel();
    }

    @Override
    protected void hookOnError(Throwable throwable) {
        // As on cancel: what is still collected for a batch is never handled by this subscriber
        holdResumePointBelow(List.copyOf(eventQueue));
        // Clean up the scheduler
        cancelScheduledProcessing();
        scheduler.shutdown();
        batchHandlerScheduler.dispose();

        super.hookOnError(throwable);
    }

    /**
     * @return true once this subscriber has been stopped: disposed (stop, {@code resetFrom}, unsubscribe) or terminated by
     * an upstream error. Not {@link #isDisposed()} on its own - that is also true while {@link #hookOnComplete()} handles
     * the final batch, which must get the {@link SubscriptionErrorPolicy} like any other. Once true it stays true, and it
     * turns true before {@link #hookOnCancel()} disposes the {@link #batchHandlerScheduler}, i.e. before a batch in its
     * retry backoff is interrupted
     */
    private boolean isStopped() {
        return isDisposed() && !completed;
    }

    /**
     * Schedule a check for partial batch processing based on max latency
     */
    private void schedulePartialBatchProcessing() {
        // Cancel any existing scheduled task
        cancelScheduledProcessing();

        // Schedule a new check
        var future = scheduler.schedule(() -> {
            try {
                var currentTime   = System.currentTimeMillis();
                var lastEventTime = lastEventTimestamp.get();
                var currentSize   = queueSize.get();

                // Process if we have events and have exceeded max latency
                if (currentSize > 0 && currentTime - lastEventTime >= maxLatency.toMillis()) {
                    log.debug("[{}-{}] Processing partial batch of {} events due to max latency ({} ms)",
                              eventStoreSubscription.subscriberId(),
                              eventStoreSubscription.aggregateType(),
                              currentSize,
                              maxLatency.toMillis());

                    processBatchIfNotAlreadyProcessing();
                }
            } catch (Throwable t) {
                log.error("[{}-{}] Error in scheduled partial batch processing",
                          eventStoreSubscription.subscriberId(),
                          eventStoreSubscription.aggregateType(),
                          t);
            } finally {
                // Reschedule for next check if not disposed
                if (!isDisposed()) {
                    schedulePartialBatchProcessing();
                }
            }
        }, maxLatency.toMillis(), TimeUnit.MILLISECONDS);

        scheduledProcessing.set(future);
    }

    /**
     * Cancel any scheduled batch processing
     */
    private void cancelScheduledProcessing() {
        ScheduledFuture<?> future = scheduledProcessing.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    /**
     * Process the current batch of events if not already processing
     */
    private void processBatchIfNotAlreadyProcessing() {
        // Only process if we can acquire the lock
        if (processingLock.tryLock()) {
            try {
                processBatch();
            } finally {
                processingLock.unlock();
            }
        } else {
            log.trace("[{}-{}] Batch processing already in progress, skipping",
                      eventStoreSubscription.subscriberId(),
                      eventStoreSubscription.aggregateType());
        }
    }

    /**
     * A lock to ensure that batch processing occurs sequentially
     */
    private final Lock batchProcessingSequenceLock = new ReentrantLock();

    /**
     * Process the current batch of events
     */
    private void processBatch() {
        // Return early if queue is empty
        if (eventQueue.isEmpty()) {
            return;
        }

        // Create a list from the current queue and reset queue
        var            currentBatch = new ArrayList<PersistedEvent>(queueSize.get());
        PersistedEvent event;
        while ((event = eventQueue.poll()) != null) {
            currentBatch.add(event);
        }

        // Reset the queue size counter
        queueSize.set(0);

        // Safety check
        if (currentBatch.isEmpty()) {
            return;
        }

        // Sort the batch by global event order to ensure in-order processing within the batch
        currentBatch.sort(Comparator.comparing(PersistedEvent::globalEventOrder));

        var firstEvent     = Lists.first(currentBatch).get();
        var lastEvent      = Lists.last(currentBatch).get();
        var immutableBatch = Collections.unmodifiableList(currentBatch);

        log.debug("[{}-{}] Processing batch of {} events (global event order: [#{} - #{}])",
                  eventStoreSubscription.subscriberId(),
                  eventStoreSubscription.aggregateType(),
                  currentBatch.size(),
                  firstEvent.globalEventOrder(),
                  lastEvent.globalEventOrder());

        // Outside the callable, so an I/O retry (which re-subscribes the callable) doesn't reset the policy's retry budget
        var policyRetriesPerformed = new AtomicInteger();
        var attemptsStarted        = new AtomicInteger();
        // Process the batch in a unit of work
        Mono.fromCallable(() -> {
                if (attemptsStarted.getAndIncrement() > 0 && isStopped()) {
                    // An I/O retry must not outlive the subscriber - it would handle the batch after the stop
                    throw new SubscriptionStoppedDuringRetryException(null);
                }
                // Acquire the sequential processing lock to ensure batches are processed in order
                batchProcessingSequenceLock.lock();
                try {
                    if (resumePointHeld) {
                        // A batch collected before an earlier batch stopped the subscriber - left for the restarted subscription,
                        // and not acknowledged. Only if the event store does not honour the acknowledgement does the hold move
                        // down to a gap fill in it (see holdResumePointBelow)
                        if (!acknowledgement.isHonoured()) {
                            holdResumePointAt(firstEvent);
                        }
                        log.debug("[{}-{}] Ignoring batch of {} events (global event order: [#{} - #{}]) - the subscriber has stopped (stopped by the {} SubscriptionErrorPolicy: {})",
                                  eventStoreSubscription.subscriberId(),
                                  eventStoreSubscription.aggregateType(),
                                  immutableBatch.size(),
                                  firstEvent.globalEventOrder(),
                                  lastEvent.globalEventOrder(),
                                  SubscriptionErrorPolicy.Mode.STOP,
                                  stoppedByErrorPolicy);
                        return 0;
                    }
                    log.trace("[{}-{}] Forwarding batch of {} events (global event order: [#{} - #{}]) to EventHandler",
                              eventStoreSubscription.subscriberId(),
                              eventStoreSubscription.aggregateType(),
                              immutableBatch.size(),
                              firstEvent.globalEventOrder(),
                              lastEvent.globalEventOrder());

                    return SubscriptionErrorPolicyRetries.callRetryingPerPolicy(
                            () -> eventStore.getUnitOfWorkFactory()
                                            .withUnitOfWork(unitOfWork -> {
                                                var requestSize = eventHandler.handleBatch(immutableBatch);
                                                // In the unit of work that handled it: the gaps of the gap fills in the batch are
                                                // resolved atomically with it, and stay open if this unit of work rolls back
                                                acknowledgement.acknowledge(immutableBatch);
                                                return requestSize;
                                            }),
                            subscriptionErrorPolicy,
                            forwardToEventHandlerRetryBackoffSpec,
                            policyRetriesPerformed,
                            this::isStopped,
                            (retryNumber, backoff, failure) -> log.warn("[{}-{}] Handling batch of {} events (global event order: [#{} - #{}]) failed - performing retry {} of {} in {} ms ({} SubscriptionErrorPolicy): {}",
                                                                        eventStoreSubscription.subscriberId(),
                                                                        eventStoreSubscription.aggregateType(),
                                                                        immutableBatch.size(),
                                                                        firstEvent.globalEventOrder(),
                                                                        lastEvent.globalEventOrder(),
                                                                        retryNumber,
                                                                        subscriptionErrorPolicy.maxRetries(),
                                                                        backoff.toMillis(),
                                                                        subscriptionErrorPolicy.mode(),
                                                                        Exceptions.getRootCause(failure).toString()));
                } finally {
                    batchProcessingSequenceLock.unlock();
                }
            })
            .subscribeOn(batchHandlerScheduler)
            .retryWhen(forwardToEventHandlerRetryBackoffSpec
                               .doBeforeRetry(retrySignal -> {
                                   log.trace("[{}-{}] Ready to perform {} attempt retry of batch processing (last event: #{})",
                                             eventStoreSubscription.subscriberId(),
                                             eventStoreSubscription.aggregateType(),
                                             retrySignal.totalRetries() + 1,
                                             lastEvent.globalEventOrder());
                               })
                               .doAfterRetry(retrySignal -> {
                                   log.debug("[{}-{}] {} {} retry of batch processing (last event: #{})",
                                             eventStoreSubscription.subscriberId(),
                                             eventStoreSubscription.aggregateType(),
                                             retrySignal.failure() != null ? "Failed" : "Succeeded",
                                             retrySignal.totalRetries(),
                                             lastEvent.globalEventOrder());
                               }))
            .doFinally(signalType -> {
                // Runs after the error consumer below, so a STOP (or a stop mid-retry) has already been recorded when the batch fails
                synchronized (resumePointLock) {
                    if (resumePointHeld) {
                        return;
                    }
                    // Update the resume point to after the last event in the batch - advance (not set),
                    // since gap-filled batches can complete out of order and must not rewind it
                    resumePoint.advanceResumeFromAndIncluding(lastEvent.globalEventOrder().increment());
                }

                // Reschedule latency check
                schedulePartialBatchProcessing();
            })
            .subscribe(requestSize -> {
                           // Handle the request for more events
                           if (requestSize < 0) {
                               requestSize = 1;
                           }
                           log.trace("[{}-{}] (#{}) Requesting {} events from the EventStore",
                                     eventStoreSubscription.subscriberId(),
                                     eventStoreSubscription.aggregateType(),
                                     lastEvent.globalEventOrder(),
                                     requestSize);
                           if (requestSize > 0) {
                               eventStoreSubscription.request(requestSize);
                           }
                       },
                       error -> {
                           // Handle errors for the entire batch
                           var failure = SubscriptionErrorPolicyRetries.unwrapRetryExhausted(error);
                           if (isStopped() || failure instanceof RejectedExecutionException) {
                               // Not a verdict on the batch - the subscriber was stopped under it (see SubscriptionStoppedDuringRetryException,
                               // which is only ever thrown once isStopped() is true), or its batchHandlerScheduler was shut down and rejected
                               // the batch (an I/O retry of the final batch after completion). Either way it is left for the restarted subscription
                               stoppedWhileHandling(firstEvent, lastEvent, failure);
                               return;
                           }
                           eventStore.getEventStoreSubscriptionObserver().handleEventBatchFailed(immutableBatch,
                                                                                                 eventHandler,
                                                                                                 failure,
                                                                                                 eventStoreSubscription);
                           if (subscriptionErrorPolicy.stopsOnError()) {
                               // Not acknowledged: the restarted subscription resumes at the batch
                               stopAt(firstEvent, failure.getCause() != null ? failure.getCause() : failure);
                           } else {
                               // Skipped - done with
                               acknowledgeGivenUp(immutableBatch);
                               onErrorHandler.accept(lastEvent, failure.getCause());
                           }
                       });
    }

    /**
     * Acknowledge a batch the {@link SubscriptionErrorPolicy} skipped - outside any unit of work: the one that handled it
     * rolled back. A failure only leaves the gaps of gap fills in it open, which delivers those events again to a later
     * subscription, so it is logged rather than stopping the subscriber.
     */
    private void acknowledgeGivenUp(List<PersistedEvent> batch) {
        try {
            acknowledgement.acknowledge(batch);
        } catch (RuntimeException acknowledgementFailure) {
            log.warn(msg("[{}-{}] Could not acknowledge the skipped batch [#{} - #{}] - the gaps of gap fills in it stay open, and a later subscription is handed those events again",
                         eventStoreSubscription.subscriberId(),
                         eventStoreSubscription.aggregateType(),
                         batch.getFirst().globalEventOrder(),
                         batch.getLast().globalEventOrder()), acknowledgementFailure);
        }
    }

    private GlobalEventOrder currentResumePoint() {
        synchronized (resumePointLock) {
            return resumePoint.getResumeFromAndIncluding();
        }
    }

    /**
     * @return true if a failed batch made this subscriber stop, as {@link SubscriptionErrorPolicy.Mode#STOP} prescribes.
     * A stopped subscriber handles no further events; the subscription must be started again to continue
     */
    public boolean isStoppedByErrorPolicy() {
        return stoppedByErrorPolicy;
    }

    /**
     * {@link SubscriptionErrorPolicy.Mode#STOP}: keep the resume point at the first event of the failed batch and stop
     * handling events. See {@code PersistedEventSubscriber#stopAt} - the same reasoning applies, per batch.
     */
    private void stopAt(PersistedEvent firstEventOfFailedBatch, Throwable cause) {
        // Set before the resume point is held, so the observer below already sees isStoppedByErrorPolicy() == true
        stoppedByErrorPolicy = true;
        var resumeFrom = holdResumePointAt(firstEventOfFailedBatch);
        cancelScheduledProcessing();
        log.error(msg("[{}-{}] Stopping the subscription because handling the batch starting at #{} failed and the SubscriptionErrorPolicy is {}. " +
                              "The resume point stays at #{}, so no event is skipped: no further events are handled until the subscription is started again " +
                              "(restart, resetFrom or unsubscribe/subscribe), and it then resumes at this batch",
                      eventStoreSubscription.subscriberId(),
                      eventStoreSubscription.aggregateType(),
                      firstEventOfFailedBatch.globalEventOrder(),
                      SubscriptionErrorPolicy.Mode.STOP,
                      resumeFrom), cause);
        try {
            eventStore.getEventStoreSubscriptionObserver().subscriptionStoppedByErrorPolicy(firstEventOfFailedBatch.globalEventOrder(), cause, eventStoreSubscription);
        } catch (RuntimeException observerFailure) {
            log.warn(msg("[{}-{}] EventStoreSubscriptionObserver#subscriptionStoppedByErrorPolicy failed",
                         eventStoreSubscription.subscriberId(),
                         eventStoreSubscription.aggregateType()), observerFailure);
        }
        Schedulers.boundedElastic().schedule(this::dispose);
    }

    /**
     * The subscriber was stopped (disposed) while a batch was being handled or retried: leave the resume point at the
     * batch's first event, so the restarted subscription handles the batch again, instead of skipping it.
     */
    private void stoppedWhileHandling(PersistedEvent firstEvent, PersistedEvent lastEvent, Throwable failure) {
        var resumeFrom = holdResumePointAt(firstEvent);
        log.info("[{}-{}] The subscriber was stopped while handling the batch [#{} - #{}] was being retried or was in progress ({}) - " +
                         "the batch is not skipped: the resume point stays at #{} and the batch is handled again when the subscription is started again",
                 eventStoreSubscription.subscriberId(),
                 eventStoreSubscription.aggregateType(),
                 firstEvent.globalEventOrder(),
                 lastEvent.globalEventOrder(),
                 Exceptions.getRootCause(failure).toString(),
                 resumeFrom);
    }

    /**
     * Keep the resume point at the first event of a batch and stop later batches from advancing it. See
     * {@code PersistedEventSubscriber#holdResumePointAt} - the same reasoning applies, per batch.
     *
     * @return the resume point after holding it
     */
    private GlobalEventOrder holdResumePointAt(PersistedEvent firstEventOfBatch) {
        synchronized (resumePointLock) {
            resumePointHeld = true;
            if (resumePoint.getResumeFromAndIncluding().longValue() > firstEventOfBatch.globalEventOrder().longValue()) {
                resumePoint.setResumeFromAndIncluding(firstEventOfBatch.globalEventOrder());
            }
            return resumePoint.getResumeFromAndIncluding();
        }
    }

    /**
     * Events this subscriber was handed but will never handle - collected for a batch when it is stopped. One of them
     * below the resume point is a gap fill: the resume point had moved past it (gap fills are delivered after higher
     * events), and an event store that does not honour the {@link SubscriberAcknowledgement} resolved its gap once it
     * handed the event on, so a restarted subscription resuming where this one got to would never see it. Then the resume
     * point is held at the lowest of them, as for a batch the stop interrupted - redelivering what lies between is
     * at-least-once. Events at or above the resume point are read again anyway; for them nothing changes. An event store
     * that honours the acknowledgement keeps the gap of such a fill open instead, so nothing is held then.
     */
    private void holdResumePointBelow(List<PersistedEvent> unhandledEvents) {
        if (unhandledEvents.isEmpty() || acknowledgement.isHonoured()) {
            // Honoured: the event store resolves a gap fill's gap only once it was acknowledged, so the gap of one never
            // handled here stays open and the restarted subscription is handed it again
            return;
        }
        var lowest = unhandledEvents.stream().min(Comparator.comparing(PersistedEvent::globalEventOrder)).get();
        synchronized (resumePointLock) {
            if (resumePoint.getResumeFromAndIncluding().longValue() <= lowest.globalEventOrder().longValue()) {
                return;
            }
            var resumeFrom = holdResumePointAt(lowest);
            log.info("[{}-{}] The subscriber was stopped with {} event(s) collected for its next batch, among them #{} below its resume point (a gap fill) - " +
                             "the resume point stays at #{}, so the restarted subscription handles it",
                     eventStoreSubscription.subscriberId(),
                     eventStoreSubscription.aggregateType(),
                     unhandledEvents.size(),
                     lowest.globalEventOrder(),
                     resumeFrom);
        }
    }
}