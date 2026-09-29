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
import reactor.core.scheduler.Schedulers;
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
     * Guards the resume point against a batch completing concurrently with {@link #stopAt(PersistedEvent, Throwable)}
     */
    private final Object                                resumePointLock = new Object();
    private volatile boolean                            stoppedByErrorPolicy;

    // Thread-safe priority queue of events being collected for a batch, sorted by global event order
    private final ConcurrentLinkedQueue<PersistedEvent> eventQueue;
    private final AtomicInteger                         queueSize;
    private final Lock                                  processingLock;
    private final ScheduledExecutorService              scheduler;
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
        this.subscriptionErrorPolicy = requireNonNull(subscriptionErrorPolicy, "No subscriptionErrorPolicy provided");
        this.eventHandler = requireNonNull(eventHandler, "No eventHandler provided");
        this.eventStoreSubscription = requireNonNull(eventStoreSubscription, "No eventStoreSubscription provided");
        this.onErrorHandler = requireNonNull(onErrorHandler, "No errorHandler provided");
        this.forwardToEventHandlerRetryBackoffSpec = requireNonNull(forwardToEventHandlerRetryBackoffSpec, "No retryBackoffSpec provided");
        this.eventStorePollingBatchSize = eventStorePollingBatchSize;
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.maxBatchSize = maxBatchSize;
        this.maxLatency = requireNonNull(maxLatency, "No maxLatency provided");

        requireTrue(eventStorePollingBatchSize > 0, "eventStorePollingBatchSize must be > 0");
        requireTrue(maxBatchSize > 0, "maxBatchSize must be > 0");

        // Verify that the provided eventStoreSubscription supports resume-points
        eventStoreSubscription.currentResumePoint().orElseThrow(() ->
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
        if (stoppedByErrorPolicy) {
            // Events already requested before the stop still arrive - they are left for the restarted subscription
            log.debug("[{}-{}] Ignoring event #{} - the subscriber was stopped by the {} SubscriptionErrorPolicy",
                      eventStoreSubscription.subscriberId(),
                      eventStoreSubscription.aggregateType(),
                      event.globalEventOrder(),
                      SubscriptionErrorPolicy.Mode.STOP);
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
        // Process any remaining events in the batch when the stream completes
        if (!eventQueue.isEmpty()) {
            processBatchIfNotAlreadyProcessing();
        }

        // Clean up the scheduler
        cancelScheduledProcessing();
        scheduler.shutdown();

        super.hookOnComplete();
    }

    @Override
    protected void hookOnCancel() {
        // Clean up the scheduler
        cancelScheduledProcessing();
        scheduler.shutdown();

        super.hookOnCancel();
    }

    @Override
    protected void hookOnError(Throwable throwable) {
        // Clean up the scheduler
        cancelScheduledProcessing();
        scheduler.shutdown();

        super.hookOnError(throwable);
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
        // Process the batch in a unit of work
        Mono.fromCallable(() -> {
                // Acquire the sequential processing lock to ensure batches are processed in order
                batchProcessingSequenceLock.lock();
                try {
                    if (stoppedByErrorPolicy) {
                        // A batch collected before an earlier batch stopped the subscriber - left for the restarted subscription
                        log.debug("[{}-{}] Ignoring batch of {} events (global event order: [#{} - #{}]) - the subscriber was stopped by the {} SubscriptionErrorPolicy",
                                  eventStoreSubscription.subscriberId(),
                                  eventStoreSubscription.aggregateType(),
                                  immutableBatch.size(),
                                  firstEvent.globalEventOrder(),
                                  lastEvent.globalEventOrder(),
                                  SubscriptionErrorPolicy.Mode.STOP);
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
                                            .withUnitOfWork(unitOfWork -> eventHandler.handleBatch(immutableBatch)),
                            subscriptionErrorPolicy,
                            forwardToEventHandlerRetryBackoffSpec,
                            policyRetriesPerformed,
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
            .subscribeOn(Schedulers.single())
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
                // Runs after the error consumer below, so a STOP has already been recorded when the batch fails
                synchronized (resumePointLock) {
                    if (stoppedByErrorPolicy) {
                        return;
                    }
                    // Update the resume point to after the last event in the batch - advance (not set),
                    // since gap-filled batches can complete out of order and must not rewind it
                    eventStoreSubscription.currentResumePoint().get()
                                          .advanceResumeFromAndIncluding(lastEvent.globalEventOrder().increment());
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
                           if (subscriptionErrorPolicy.stopsOnError()) {
                               stopAt(firstEvent, failure.getCause() != null ? failure.getCause() : failure);
                           } else {
                               onErrorHandler.accept(lastEvent, failure.getCause());
                           }
                       });
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
        GlobalEventOrder resumeFrom;
        synchronized (resumePointLock) {
            stoppedByErrorPolicy = true;
            var resumePoint = eventStoreSubscription.currentResumePoint().get();
            if (resumePoint.getResumeFromAndIncluding().longValue() > firstEventOfFailedBatch.globalEventOrder().longValue()) {
                resumePoint.setResumeFromAndIncluding(firstEventOfFailedBatch.globalEventOrder());
            }
            resumeFrom = resumePoint.getResumeFromAndIncluding();
        }
        cancelScheduledProcessing();
        log.error(msg("[{}-{}] Stopping the subscription because handling the batch starting at #{} failed and the SubscriptionErrorPolicy is {}. " +
                              "The resume point stays at #{}, so no event is skipped: no further events are handled until the subscription is started again " +
                              "(restart, resetFrom or unsubscribe/subscribe), and it then resumes at this batch",
                      eventStoreSubscription.subscriberId(),
                      eventStoreSubscription.aggregateType(),
                      firstEventOfFailedBatch.globalEventOrder(),
                      SubscriptionErrorPolicy.Mode.STOP,
                      resumeFrom), cause);
        Schedulers.boundedElastic().schedule(this::dispose);
    }
}