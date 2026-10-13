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
package dk.trustworks.essentials.components.foundation.messaging.queue;

import dk.trustworks.essentials.components.foundation.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.interceptor.InterceptorChain.newInterceptorChainForOperation;

/**
 * Centralized message fetcher that fetches messages from queues using a single polling thread
 * and distributes them to {@link DurableQueueConsumer} worker threads.
 * <p>
 * This design:
 * - Reduces database load by having a single polling thread per {@link DurableQueues} instance
 * - Maintains ordering guarantees for ordered messages
 * - Respects each consumer's thread allocation
 * - Supports dynamic thread allocation
 * - Optimizes polling frequency based on queue activity
 */
public class CentralizedMessageFetcher implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(CentralizedMessageFetcher.class);
    private final DurableQueues                                              durableQueues;
    private final ScheduledExecutorService                                   scheduler;
    private final ConcurrentMap<QueueName, DurableQueueConsumerRegistration> consumerRegistrations;
    private final AtomicBoolean                                              started;
    private final long                                                       pollingIntervalMs;
    private final boolean                                                    useBatchedFetch;
    private final int                                                        batchedFetchSwitchThreshold;
    private final List<DurableQueuesInterceptor>                             interceptors;

    /**
     * Messages currently being processed per queue with their key
     * Map: QueueName -> (Set of {@link OrderedMessage#getKey()}'s currently being processed)
     */
    private final ConcurrentMap<QueueName, Set<String>> inProcessOrderedKeys;

    /**
     * Create a new CentralizedMessageFetcher that always uses per-queue fetching.
     *
     * @param durableQueues     the DurableQueues instance to work with
     * @param pollingIntervalMs the polling interval in milliseconds
     */
    public CentralizedMessageFetcher(DurableQueues durableQueues,
                                     long pollingIntervalMs,
                                     List<DurableQueuesInterceptor> interceptors) {
        this(durableQueues, pollingIntervalMs, interceptors, false, 0);
    }

    /**
     * Create a new CentralizedMessageFetcher
     *
     * @param durableQueues               the DurableQueues instance to work with
     * @param pollingIntervalMs           the polling interval in milliseconds
     * @param useBatchedFetch             opt in to batched fetching. When {@code false} (the default elsewhere) every poll
     *                                    uses per-queue fetching regardless of how many queues are active, and
     *                                    {@code batchedFetchSwitchThreshold} is ignored
     * @param batchedFetchSwitchThreshold only consulted when {@code useBatchedFetch} is {@code true}: use per-queue fetch
     *                                    for active queue counts &lt;= threshold, batched fetch above it
     */
    public CentralizedMessageFetcher(DurableQueues durableQueues,
                                     long pollingIntervalMs,
                                     List<DurableQueuesInterceptor> interceptors,
                                     boolean useBatchedFetch,
                                     int batchedFetchSwitchThreshold) {
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues provided");
        this.pollingIntervalMs = pollingIntervalMs;
        this.interceptors = requireNonNull(interceptors, "interceptors is missing");
        requireTrue(batchedFetchSwitchThreshold >= 0, "batchedFetchSwitchThreshold must be >= 0");
        this.useBatchedFetch = useBatchedFetch;
        this.batchedFetchSwitchThreshold = batchedFetchSwitchThreshold;
        this.consumerRegistrations = new ConcurrentHashMap<>();
        this.inProcessOrderedKeys = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r);
            thread.setName("DurableQueues-CentralizedMessageFetcher");
            thread.setDaemon(true);
            return thread;
        });
        this.started = new AtomicBoolean(false);
    }

    /**
     * Register a consumer with the centralized fetcher
     *
     * @param queueName            the queue name to consume from
     * @param consumer             the consumer instance
     * @param messageHandler       the message handler
     * @param workerPool           the worker pool to use
     * @param maxParallelConsumers the maximum number of parallel consumers
     */
    public void registerConsumer(QueueName queueName,
                                 DurableQueueConsumer consumer,
                                 QueuedMessageHandler messageHandler,
                                 ExecutorService workerPool,
                                 int maxParallelConsumers) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(consumer, "No consumer provided");
        requireNonNull(messageHandler, "No messageHandler provided");
        requireNonNull(workerPool, "No workerPool provided");
        requireTrue(maxParallelConsumers > 0, "maxParallelConsumers must be > 0");

        log.debug("[{}] Registering consumer with max {} parallel consumers",
                  queueName,
                  maxParallelConsumers);

        consumerRegistrations.putIfAbsent(queueName,
                                          new DurableQueueConsumerRegistration(
                                                  queueName,
                                                  consumer,
                                                  messageHandler,
                                                  workerPool,
                                                  maxParallelConsumers));

        inProcessOrderedKeys.putIfAbsent(queueName, ConcurrentHashMap.newKeySet());
    }

    /**
     * Unregister a consumer
     *
     * @param queueName the queue name to unregister
     */
    public void unregisterConsumer(QueueName queueName) {
        requireNonNull(queueName, "No queueName provided");

        log.debug("[{}] Unregistering consumer", queueName);
        consumerRegistrations.remove(queueName);
        inProcessOrderedKeys.remove(queueName);
    }

    @Override
    public void start() {
        if (started.compareAndSet(false, true)) {
            log.info("Starting CentralizedMessageFetcher with polling interval {} ms", pollingIntervalMs);

            scheduler.scheduleAtFixedRate(() -> pollOnce(null), 0, pollingIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * One poll, on the scheduler's single thread - so the periodic polls and the refill polls never overlap.
     *
     * @param onlyQueue the queue a refill poll is for, or {@code null} for the periodic poll of every queue
     */
    private void pollOnce(QueueName onlyQueue) {
        if (!started.get()) {
            return;
        }
        try {
            fetchAndDistributeMessages(onlyQueue);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            if (IOExceptionUtil.isIOException(e)) {
                log.debug("I/O issue while polling queues", e);
            } else {
                log.error("Error during centralized message fetching", e);
            }
        }
    }

    /**
     * Refill a saturated queue as soon as enough of its workers are free, rather than at the next polling interval.
     * <p>
     * A poll claims at most a queue's free worker slots, so polling only once per interval capped a queue at
     * {@code parallelConsumers / pollingInterval} messages per second however fast its handler was - 400 msg/s for 8
     * consumers at 20 ms. A queue whose last poll filled every free slot most likely has more waiting, so it is polled
     * again once half its workers are free. Waiting for half, rather than for the first free one, batches the claims:
     * one claim statement per message would cost the database more than the interval saved. Never more than the free
     * slots are claimed, so this cannot over-fetch (see {@link #calculateAvailableWorkerSlotsPerQueue}).
     */
    private void refillIfSaturated(DurableQueueConsumerRegistration registration) {
        var freeSlots = registration.maxParallelConsumers - registration.activeWorkers.get();
        if (freeSlots >= registration.refillThreshold && registration.saturated.compareAndSet(true, false)) {
            if (!started.get()) {
                return;
            }
            try {
                scheduler.execute(() -> pollOnce(registration.queueName));
            } catch (RejectedExecutionException e) {
                // Stopped in between - nothing left to refill
            }
        }
    }

    @Override
    public void stop() {
        if (started.compareAndSet(true, false)) {
            log.info("Stopping CentralizedMessageFetcher");
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Retrieves the polling interval in milliseconds used for fetching messages.
     *
     * @return the polling interval in milliseconds
     */
    public long getPollingIntervalMs() {
        return pollingIntervalMs;
    }

    /**
     * Main method that fetches and distributes messages to workers
     */
    private void fetchAndDistributeMessages(QueueName onlyQueue) {
        if (consumerRegistrations.isEmpty()) {
            log.trace("No consumers registered, skipping fetch and distribute");
            return;
        }

        // Collect active queue names and determine available worker slots
        var availableWorkerSlotsPerQueue = calculateAvailableWorkerSlotsPerQueue();
        if (onlyQueue != null) {
            availableWorkerSlotsPerQueue.keySet().retainAll(Set.of(onlyQueue));
        }

        // Skip if no capacity available
        if (availableWorkerSlotsPerQueue.values().stream().mapToInt(Integer::intValue).sum() == 0) {
            log.trace("No worker capacity available, skipping fetch and distribute");
            return;
        }

        // Prepare excluded keys map for batch fetching
        var excludeKeysPerQueue = new HashMap<QueueName, Set<String>>();
        availableWorkerSlotsPerQueue.forEach((queueName, slots) -> {
            if (slots > 0) {
                excludeKeysPerQueue.put(queueName, new HashSet<>(inProcessOrderedKeys.getOrDefault(queueName, ConcurrentHashMap.newKeySet())));
            }
        });

        try {
            // Ask the DurableQueues implementation if it supports batch fetching
            if (durableQueues instanceof BatchMessageFetchingCapableDurableQueues batchCapableDurableQueues) {
                List<QueuedMessage> messages;
                var queueNames = availableWorkerSlotsPerQueue.keySet();
                if (useBatchedFetch && queueNames.size() > batchedFetchSwitchThreshold) {
                    messages = batchCapableDurableQueues.fetchNextBatchOfMessagesBatched(
                            queueNames,
                            excludeKeysPerQueue,
                            availableWorkerSlotsPerQueue);
                } else {
                    messages = batchCapableDurableQueues.fetchNextBatchOfMessages(
                            queueNames,
                            excludeKeysPerQueue,
                            availableWorkerSlotsPerQueue);
                }

                // Debug logging when using batch fetching
                if (log.isDebugEnabled()) {
                    var messageCountsByQueue = messages.stream()
                                                       .collect(Collectors.groupingBy(QueuedMessage::getQueueName, Collectors.counting()));
                    log.debug("Batch fetched {} messages across {} queues: {}",
                              messages.size(),
                              availableWorkerSlotsPerQueue.size(),
                              messageCountsByQueue);
                }

                // Process all messages without duplicates and with error handling for each
                for (var message : messages) {
                    var queueName = message.getQueueName();
                    try {
                        processMessage(queueName, message);
                    } catch (Exception e) {
                        // Log errors but continue processing other messages
                        log.error("[{}:{}] Error processing message in batch: {}",
                                  queueName, message.getId(), e.getMessage(), e);
                    }
                }
                // After dispatching, so the workers the messages occupy are counted
                markSaturatedQueues(availableWorkerSlotsPerQueue,
                                    messages.stream().collect(Collectors.groupingBy(QueuedMessage::getQueueName, Collectors.counting())));
            } else {
                // Fall back to per-queue fetching for non-batch-capable implementations
                availableWorkerSlotsPerQueue.forEach((queueName, availableSlots) -> {
                    if (availableSlots <= 0) {
                        return;
                    }

                    var keysBeingProcessed = inProcessOrderedKeys.getOrDefault(queueName, Collections.emptySet());

                    try {
                        var claimed = 0L;
                        for (int i = 0; i < availableSlots; i++) {
                            var messageOpt = durableQueues.getNextMessageReadyForDelivery(
                                    new GetNextMessageReadyForDelivery(queueName, new ArrayList<>(keysBeingProcessed)));

                            if (messageOpt.isEmpty()) {
                                log.trace("[{}] No more messages to process for this queue. Slot: {} (1-based)", queueName, i + 1);
                                break;
                            }

                            claimed++;
                            processMessage(queueName, messageOpt.get());
                        }
                        markSaturatedQueues(Map.of(queueName, availableSlots), Map.of(queueName, claimed));
                    } catch (Exception e) {
                        if (IOExceptionUtil.isIOException(e)) {
                            log.debug("[{}] Error fetching messages: {}", queueName, e.getMessage(), e);
                        } else {
                            log.error("[{}] Error fetching messages: {}", queueName, e.getMessage(), e);
                        }
                    }
                });
            }
        } catch (Exception e) {
            if (IOExceptionUtil.isIOException(e)) {
                log.debug("Error during batch message fetching: {}", e.getMessage(), e);
            } else {
                log.error("Error during batch message fetching: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Process a message by submitting it to the appropriate worker pool
     */
    private void processMessage(QueueName queueName, QueuedMessage message) {
        // Track ordered message keys
        var sharedKeysBeingProcessed = inProcessOrderedKeys.computeIfAbsent(queueName, _queueName -> ConcurrentHashMap.newKeySet());

        // Add the key if this is an ordered message
        if (message.getMessage() instanceof OrderedMessage orderedMessage) {
            sharedKeysBeingProcessed.add(orderedMessage.getKey());
        }

        var registration = consumerRegistrations.get(queueName);
        if (registration == null) {
            // The consumer was cancelled between the poll that claimed this message and now. There is no
            // RedeliveryPolicy left to take a delay from, so retry after one polling interval — by which time
            // either a consumer has registered again or the message is simply claimed and released once more.
            log.warn("[{}] Received message {} for unregistered consumer - will retry the message in {} ms",
                     queueName,
                     message.getId(),
                     pollingIntervalMs);
            // No worker will release the key, and a consumer registering again would otherwise never be given it
            releaseOrderedKey(queueName, message);
            durableQueues.retryMessage(message.getId(),
                                       null,
                                       Duration.ofMillis(pollingIntervalMs));
            return;
        }

        registration.activeWorkers.incrementAndGet();
        log.debug("[{}] Submitting message {} to worker pool",
                  queueName,
                  message.getId());

        try {
            // A consumer stopping shuts its worker pool down before it unregisters, and workers finishing in between
            // fire refill polls - so a poll can claim a message for a pool that no longer takes work
            if (!registration.consumer.isStarted()) {
                throw new RejectedExecutionException("The consumer began stopping before the message reached a worker");
            }
            submitToWorker(registration, queueName, message);
        } catch (RejectedExecutionException e) {
            registration.activeWorkers.decrementAndGet();
            releaseOrderedKey(queueName, message);
            handBackUndelivered(queueName, message, e);
        }
    }

    private void submitToWorker(DurableQueueConsumerRegistration registration, QueueName queueName, QueuedMessage message) {
        registration.workerPool.submit(() -> {
            var handlerStartedAtNanos = System.nanoTime();
            try {
                var operation = new HandleQueuedMessage(message, registration.messageHandler);
                newInterceptorChainForOperation(operation,
                                                interceptors,
                                                (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                                () -> {
                                                    registration.messageHandler.handle(message);
                                                    return (Void) null;
                                                })
                        .proceed();

                if (message.isManuallyMarkedForRedelivery()) {
                    log.debug("[{}:{}] Message handler manually requested redelivery",
                              queueName,
                              message.getId());

                    try {
                        durableQueues.retryMessage(message.getId(),
                                                   null,
                                                   message.getRedeliveryDelay());
                        durableQueues.getMessageObserver().messageRedeliveryRequested(message);
                    } catch (Exception ex) {
                        // If retry fails due to connectivity issues, then it will be picked up by resetMessagesStuckBeingDelivered
                        log.warn("[{}:{}] Could not manually mark message for redelivery: {}",
                                 queueName,
                                 message.getId(),
                                 ex.getMessage());
                    }
                } else {
                    log.debug("[{}:{}] Message handled successfully, acknowledging",
                              queueName,
                              message.getId());

                    try {
                        // Acknowledged already when an interceptor did it inside the handler's transaction
                        var     acknowledgedWithHandler = operation.getAcknowledgedWithHandler();
                        boolean acknowledged            = acknowledgedWithHandler != null
                                                          ? acknowledgedWithHandler
                                                          : durableQueues.acknowledgeMessageAsHandled(message.getId());
                        if (!acknowledged) {
                            // Message was already acknowledged or deleted
                            log.debug("[{}:{}] Message acknowledgment reported message already handled or deleted",
                                      queueName,
                                      message.getId());
                        } else {
                            // After the acknowledgement, so the count means "delivered and removed"
                            durableQueues.getMessageObserver()
                                         .messageHandled(message, Duration.ofNanos(System.nanoTime() - handlerStartedAtNanos));
                        }
                    } catch (Exception ex) {
                        // If acknowledgment fails due to connectivity issues, the message will be
                        // picked up by resetMessagesStuckBeingDelivered after the handling timeout
                        log.warn("[{}:{}] Failed to acknowledge message: {}",
                                 queueName,
                                 message.getId(),
                                 ex.getMessage());
                    }
                }
            } catch (Throwable e) {
                rethrowIfCriticalError(e);

                try {
                    if (!registration.consumer.isStarted()) {
                        handBackAfterConsumerStopped(queueName, message, e);
                        return;
                    }
                    var decision = classify(registration, message, e);
                    if (decision.isDeadLetter()) {
                        log.error("[{}:{}] Marking message as dead letter. {}. Error: {}",
                                  queueName,
                                  message.getId(),
                                  decision.describe(),
                                  e.getMessage(),
                                  e);

                        durableQueues.markAsDeadLetterMessage(message.getId(), e);
                        durableQueues.getMessageObserver().messageDeadLettered(message, e, decision.outcome());
                    } else {
                        // Redelivery
                        var redeliveryDelay = registration.consumer.getRedeliveryPolicy()
                                                                   .calculateNextRedeliveryDelay(message.getRedeliveryAttempts());

                        log.debug("[{}:{}] Will retry message with delay {}: {}",
                                  queueName,
                                  message.getId(),
                                  redeliveryDelay,
                                  e.getMessage());

                        durableQueues.retryMessage(message.getId(), e, redeliveryDelay);
                        durableQueues.getMessageObserver().messageRetried(message, e, redeliveryDelay);
                    }
                } catch (Exception retryEx) {
                    log.error("[{}:{}] Error handling message failure: {}",
                              queueName,
                              message.getId(),
                              retryEx.getMessage(),
                              retryEx);
                }
            } finally {
                releaseOrderedKey(queueName, message);
                registration.activeWorkers.decrementAndGet();
                refillIfSaturated(registration);
            }
        });
    }

    /**
     * Let the next poll claim messages with this message's key again (a no-op for an unordered message)
     */
    private void releaseOrderedKey(QueueName queueName, QueuedMessage message) {
        if (message.getMessage() instanceof OrderedMessage orderedMessage) {
            var currentKeysBeingProcessed = inProcessOrderedKeys.get(queueName);
            if (currentKeysBeingProcessed != null) {
                currentKeysBeingProcessed.remove(orderedMessage.getKey());
            }
        }
    }

    /**
     * Mark every queue whose poll claimed all of its free slots, so its next free workers trigger a refill poll. Then
     * check at once: handlers fast enough to have finished before the mark was set would otherwise leave the queue
     * waiting for the next polling interval.
     */
    private void markSaturatedQueues(Map<QueueName, Integer> availableWorkerSlotsPerQueue, Map<QueueName, Long> claimedPerQueue) {
        availableWorkerSlotsPerQueue.forEach((queueName, availableSlots) -> {
            var claimed = claimedPerQueue.getOrDefault(queueName, 0L);
            if (availableSlots <= 0 || claimed < availableSlots) {
                return;
            }
            var registration = consumerRegistrations.get(queueName);
            if (registration != null) {
                registration.saturated.set(true);
                refillIfSaturated(registration);
            }
        });
    }

    /**
     * Calculate available worker slots for each queue
     *
     * @return Map of queue name to available worker slots
     */
    private Map<QueueName, Integer> calculateAvailableWorkerSlotsPerQueue() {
        var result = new HashMap<QueueName, Integer>();

        // Process registrations in a thread-safe way
        for (Map.Entry<QueueName, DurableQueueConsumerRegistration> entry : consumerRegistrations.entrySet()) {
            var queueName = entry.getKey();
            var reg       = entry.getValue();

            if (reg == null) {
                // Skip if registration is somehow null
                log.warn("Registration for queue '{}' is null, skipping", queueName);
                continue;
            }
            if (!reg.consumer.isStarted()) {
                // Stopping: its worker pool no longer takes work, so claiming for it would only hand messages back
                continue;
            }

            // Calculate available slots based on active workers.
            // Note: Using Math.max(0, ...) is intentional - do not change to Math.max(1, ...) since it causes
            // Over-fetching when all workers are busy, which in term causes messages to pile up in the worker pool queue.
            // If messages wait longer than messageHandlingTimeout here, they are reset as "stuck" and
            // become available for other instances to fetch, potentially leading to duplicate consumption.
            // See Bug #19: DurableQueues messages consumed multiple times with multiple pods.
            int activeWorkers  = reg.activeWorkers.get();
            int availableSlots = Math.max(0, reg.maxParallelConsumers - activeWorkers);

            // Add result to map
            result.put(queueName, availableSlots);
        }

        return result;
    }

    /**
     * Decide what to do with a message whose handler threw, delegating to {@link MessageDeliveryClassifier} so
     * this fetcher and {@link DefaultDurableQueueConsumer} cannot drift apart. The policy is the one of the
     * registration the message was delivered under, which the worker holds even after the consumer unregisters.
     */
    private MessageDeliveryDecision classify(DurableQueueConsumerRegistration registration, QueuedMessage queuedMessage, Throwable e) {
        return MessageDeliveryClassifier.classify(queuedMessage, e, registration.consumer.getRedeliveryPolicy());
    }

    /**
     * Return a message whose handler failed after its consumer began stopping, without classifying the failure.
     * <p>
     * Stopping a consumer interrupts its worker pool, so a handler in the middle of I/O fails with an interrupt it
     * did nothing to cause (a Kafka send, for instance, throws {@code InterruptException}). Classifying that failure
     * could dead-letter a message that never had a fair attempt: the consumer used to do exactly that, because it
     * classified after unregistering and treated the missing registration as a permanent error. The message is
     * handed back for immediate redelivery instead, and whichever consumer takes it next classifies its next
     * failure as usual. The hand-back counts as a redelivery attempt, because {@code retryMessage} always counts one.
     * <p>
     * The write runs with the thread's interrupt flag cleared and restores it afterwards: a handler that caught the
     * interrupt usually re-sets the flag (Kafka does), and the MongoDB driver refuses to check out a connection on an
     * interrupted thread, which would leave the message marked as being delivered until the stuck-message reset.
     */
    private void handBackAfterConsumerStopped(QueueName queueName, QueuedMessage message, Throwable e) {
        log.info("[{}:{}] Handler failed after its consumer began stopping; returning the message for redelivery. Error: {}",
                 queueName,
                 message.getId(),
                 e.getMessage());
        handBack(message, e);
    }

    /**
     * Return a message a poll claimed but no worker would take, because its consumer began stopping in between.
     * Without this the message stayed marked as being delivered until the stuck-message reset, a whole
     * {@code messageHandlingTimeout} later. Like {@link #handBackAfterConsumerStopped}, it is returned for immediate
     * redelivery and the hand-back counts as a redelivery attempt.
     */
    private void handBackUndelivered(QueueName queueName, QueuedMessage message, RejectedExecutionException e) {
        log.info("[{}:{}] Consumer began stopping before the message reached a worker; returning the message for redelivery. Reason: {}",
                 queueName,
                 message.getId(),
                 e.getMessage());
        handBack(message, e);
    }

    /**
     * Return a message for immediate redelivery, with the thread's interrupt flag cleared for the write - see
     * {@link #handBackAfterConsumerStopped}
     */
    private void handBack(QueuedMessage message, Throwable e) {
        var wasInterrupted = Thread.interrupted();
        try {
            durableQueues.retryMessage(message.getId(), e, Duration.ZERO);
            durableQueues.getMessageObserver().messageRetried(message, e, Duration.ZERO);
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean containsConsumerFor(QueueName queueName) {
        requireNonNull(queueName, "No queueName provided");
        return consumerRegistrations.containsKey(queueName);
    }

    /**
     * Internal class to track consumer registrations
     */
    private static class DurableQueueConsumerRegistration {
        private final QueueName            queueName;
        private final DurableQueueConsumer consumer;
        private final QueuedMessageHandler messageHandler;
        private final ExecutorService      workerPool;
        private final int                  maxParallelConsumers;
        private final AtomicInteger        activeWorkers     = new AtomicInteger(0);
        /**
         * The last poll filled every free slot - see {@link CentralizedMessageFetcher#refillIfSaturated}
         */
        private final AtomicBoolean        saturated         = new AtomicBoolean(false);
        /**
         * How many workers must be free before a saturated queue is polled again: half of them, at least one
         */
        private final int                  refillThreshold;

        private DurableQueueConsumerRegistration(QueueName queueName,
                                                 DurableQueueConsumer consumer,
                                                 QueuedMessageHandler messageHandler,
                                                 ExecutorService workerPool,
                                                 int maxParallelConsumers) {
            this.queueName = requireNonNull(queueName, "No queueName provided");
            this.consumer = requireNonNull(consumer, "No consumer provided");
            this.messageHandler = requireNonNull(messageHandler, "No messageHandler provided");
            this.workerPool = requireNonNull(workerPool, "No workerPool provided");
            this.maxParallelConsumers = maxParallelConsumers;
            this.refillThreshold = Math.max(1, (maxParallelConsumers + 1) / 2);
        }
    }
}
