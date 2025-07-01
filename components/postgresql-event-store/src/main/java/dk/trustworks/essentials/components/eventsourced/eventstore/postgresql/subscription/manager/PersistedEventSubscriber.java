/*
 *
 *  * Copyright 2021-2025 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.manager;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.shared.time.StopWatch;
import org.reactivestreams.Subscription;
import org.slf4j.*;
import reactor.core.publisher.*;
import reactor.util.retry.*;

import java.time.Duration;
import java.util.function.BiConsumer;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public class PersistedEventSubscriber extends BaseSubscriber<PersistedEvent> {
    private static final Logger log = LoggerFactory.getLogger(EventStoreSubscriptionManager.DefaultEventStoreSubscriptionManager.PersistedEventSubscriber.class);

    private final PersistedEventHandler                 eventHandler;
    private final EventStoreSubscription                eventStoreSubscription;
    private final BiConsumer<PersistedEvent, Throwable> onErrorHandler;
    private final RetryBackoffSpec                      forwardToEventHandlerRetryBackoffSpec;
    private final long                                  eventStorePollingBatchSize;
    private final EventStore                            eventStore;

    /**
     * Subscribe with indefinite retries in relation to Exceptions where {@link IOExceptionUtil#isIOException(Throwable)} return true
     *
     * @param eventHandler               The event handler that {@link PersistedEvent}'s are forwarded to
     * @param eventStoreSubscription     the {@link EventStoreSubscription} (as created by {@link EventStoreSubscriptionManager})
     * @param onErrorHandler             The error handler called for any non-retryable Exceptions (as specified by the {@link RetryBackoffSpec})<br>
     *                                   <b>Note: Default behaviour needs to at least request one more event</b><br>
     *                                   Similar to:
     *                                   <pre>{@code
     *                                   void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
     *                                        log.error(msg("[{}-{}] (#{}) Skipping {} event because of error",
     *                                                        subscriberId,
     *                                                        aggregateType,
     *                                                        e.globalEventOrder(),
     *                                                        e.event().getEventTypeOrName().getValue()), cause);
     *                                        log.trace("[{}-{}] (#{}) Requesting 1 event from the EventStore",
     *                                                    subscriberId(),
     *                                                    aggregateType(),
     *                                                    e.globalEventOrder()
     *                                                    );
     *                                        eventStoreSubscription.request(1);
     *                                   }
     *                                   }</pre>
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
     *                                              void onErrorHandlingEvent(PersistedEvent e, Throwable cause) {
     *                                                   log.error(msg("[{}-{}] (#{}) Skipping {} event because of error",
     *                                                                   subscriberId,
     *                                                                   aggregateType,
     *                                                                   e.globalEventOrder(),
     *                                                                   e.event().getEventTypeOrName().getValue()), cause);
     *                                                   log.trace("[{}-{}] (#{}) Requesting 1 event from the EventStore",
     *                                                               subscriberId(),
     *                                                               aggregateType(),
     *                                                               e.globalEventOrder()
     *                                                               );
     *                                                   eventStoreSubscription.request(1);
     *                                              }
     *                                              }</pre>
     * @param forwardToEventHandlerRetryBackoffSpec The {@link RetryBackoffSpec} used.<br>
     *                                              Example:
     *                                              <pre>{@code
     *                                              Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(100)) // Initial delay of 100ms
     *                                                   .maxBackoff(Duration.ofSeconds(1)) // Maximum backoff of 1 second
     *                                                   .jitter(0.5)
     *                                                   .filter(IOExceptionUtil::isIOException)
     *                                              }
     *                                              </pre>
     * @param eventStorePollingBatchSize            The batch size used when polling events from the {@link EventStore}
     * @param eventStore                            The {@link EventStore} to use
     */
    public PersistedEventSubscriber(PersistedEventHandler eventHandler,
                                    EventStoreSubscription eventStoreSubscription,
                                    BiConsumer<PersistedEvent, Throwable> onErrorHandler,
                                    RetryBackoffSpec forwardToEventHandlerRetryBackoffSpec,
                                    long eventStorePollingBatchSize,
                                    EventStore eventStore) {
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
        this.eventStoreSubscription = requireNonNull(eventStoreSubscription, "No eventStoreSubscription provided");
        this.onErrorHandler = requireNonNull(onErrorHandler, "No errorHandler provided");
        this.forwardToEventHandlerRetryBackoffSpec = requireNonNull(forwardToEventHandlerRetryBackoffSpec, "No retryBackoffSpec provided");
        this.eventStorePollingBatchSize = eventStorePollingBatchSize;
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        // Verify that the provided eventStoreSubscription supports resume-points
        eventStoreSubscription.currentResumePoint().orElseThrow(() -> new IllegalArgumentException(msg("The provided {} doesn't support resume-points", eventStoreSubscription.getClass().getName())));
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
    protected void hookOnNext(PersistedEvent e) {
        Mono.fromCallable(() -> {
                log.trace("[{}-{}] (#{}) Forwarding {} event with eventId '{}', aggregateId: '{}', eventOrder: {} to EventHandler",
                          eventStoreSubscription.subscriberId(),
                          eventStoreSubscription.aggregateType(),
                          e.globalEventOrder(),
                          e.event().getEventTypeOrName().toString(),
                          e.eventId(),
                          e.aggregateId(),
                          e.eventOrder()
                         );
                return eventStore.getUnitOfWorkFactory()
                                 .withUnitOfWork(unitOfWork -> {
                                     var handleEventTiming = StopWatch.start("handleEvent (" + eventStoreSubscription.subscriberId() + ", " + eventStoreSubscription.aggregateType() + ")");
                                     var result            = eventHandler.handleWithBackPressure(e);
                                     eventStore.getEventStoreSubscriptionObserver().handleEvent(e,
                                                                                                eventHandler,
                                                                                                eventStoreSubscription,
                                                                                                handleEventTiming.stop().getDuration()
                                                                                               );
                                     return result;
                                 });
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
                eventStoreSubscription.currentResumePoint().get().setResumeFromAndIncluding(e.globalEventOrder().increment());
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
                           rethrowIfCriticalError(error);
                           eventStore.getEventStoreSubscriptionObserver().handleEventFailed(e,
                                                                                            eventHandler,
                                                                                            error,
                                                                                            eventStoreSubscription);
                           onErrorHandler.accept(e, error.getCause());
                       });
    }
}

