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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.Exceptions;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * A view projector whose {@code @MessageHandler}s run on <b>every</b> instance, each instance handling its share of the
 * aggregates - the view counterpart of {@link PartitionedEventProcessor}.
 * <p>
 * <b>What it does:</b> one instance - the one holding the processor's {@link FencedLock} ({@code <processorName>:lock}) -
 * reads the events and forwards them, in batches of up to {@link #getForwardBatchSize()} (default
 * {@value #DEFAULT_FORWARD_BATCH_SIZE}), to the processor's durable queue ({@code <processorName>:queue}): one
 * {@link DurableQueues#queueMessages(QueueName, List)} per batch, in the unit of work that also moves the resume point.
 * Every instance consumes the queue; the queue implementation gives each aggregate to one instance at a time, so one
 * aggregate's events are handled one at a time, in {@link EventOrder} order. Each queued message is handled in a
 * {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork} of its own, together with its
 * acknowledgement.
 * <p>
 * <b>What it does not do</b> - where it differs from {@link ViewEventProcessor}:
 * <ul>
 *     <li><b>Order across aggregates.</b> Events of different aggregates are handled concurrently, on several instances.
 *     A view that relies on one aggregate's row being written before another's (a parent row, a "latest N across all
 *     accounts" feed), or that read-modify-writes rows shared by many aggregates (totals), must stay a
 *     {@link ViewEventProcessor}. Views keyed by the aggregate are what this class is for.</li>
 *     <li><b>Handle inline.</b> Every event goes through the queue: event-to-view latency is the reader's commit plus
 *     the queue's hand-off to the instance owning the aggregate (sub-millisecond locally, a few ms across instances).</li>
 *     <li><b>Start on a queue implementation that keeps an ordered key in order per node only</b>
 *     ({@link DurableQueues#keepsKeyOrderAcrossInstances()} {@code false}, e.g. {@code PostgresqlDurableQueues}):
 *     {@link #start()} refuses. Use the shard-owned queue engine
 *     ({@code essentials.shard-owned-queue.durable-queues-enabled=true}) in every environment the processor runs in,
 *     tests included.</li>
 *     <li><b>{@link dk.trustworks.essentials.components.foundation.messaging.UnitOfWorkMode#NONE} handlers</b> are
 *     refused, as by {@link ViewEventProcessor}.</li>
 *     <li><b>{@link MessageMetaData#FENCED_LOCK_TOKEN}</b> is not stamped on the messages: the handlers run on instances
 *     that do not hold the lock, so they cannot fence on it.</li>
 * </ul>
 * <p>
 * <b>Handlers must be idempotent.</b> A message can be handled more than once: after the reader's instance dies, the
 * next reader forwards again from the last saved resume point (a re-forward of a message still queued is absorbed by
 * the queue, one already handled is delivered again); and the queue can redeliver when an aggregate moves to another
 * instance mid-handler. Guard each view row with the event's order, e.g. store {@link OrderedMessage#getOrder()} with
 * the row and ignore an event whose order is not above it.
 * <p>
 * <b>The queue key is the aggregate id qualified with the {@link AggregateType}</b> ({@code <aggregateType>|<aggregateId>},
 * see {@link #qualifiedKey(AggregateType, String)}), so two aggregate types sharing an id do not share a key. The
 * handlers see the plain serialized aggregate id as {@link OrderedMessage#getKey()}, as in the other processors; the
 * qualified key shows in the queue's own tooling (dead letters, {@code resurrectKey}).
 * <p>
 * <b>Is the view current?</b> The resume point means <i>forwarded</i>, not <i>handled</i>: how far behind the view is
 * equals the reader's lag plus the queue's depth ({@code <processorName>:queue}).
 * {@link #awaitProcessed(AggregateType, GlobalEventOrder, Duration)} checks both, on the instance that holds the lock.
 * <p>
 * <b>Reset</b> ({@link #resetAllSubscriptions()}, {@link #resetSubscriptions(Map, boolean)}) only while the processor is
 * stopped on <i>every</i> instance: otherwise a handler still running on another instance could write a row from before
 * the reset into the reset view. Checked, not assumed: the reset refuses while the processor is started on this
 * instance, while any instance holds its reader lock, and while the queue implementation counts any instance consuming
 * the queue; and a starting instance cannot register its consumer while a reset runs. It can run on any instance, as it
 * moves the stored resume points itself. Details: {@link StoppedEverywhereReset}.
 * <p>
 * <b>Switching an existing {@link ViewEventProcessor} to this class</b> keeps its resume point, lock and queue names.
 * Stop the view on every instance first, and deploy the new class everywhere before starting it again: a cluster that
 * mixes the two would handle one view both inline and partitioned.
 */
public abstract class PartitionedViewEventProcessor extends AbstractEventProcessor {
    /**
     * The default {@link #getForwardBatchSize()}, as {@link PartitionedEventProcessor#DEFAULT_FORWARD_BATCH_SIZE}.
     */
    public static final int    DEFAULT_FORWARD_BATCH_SIZE = PartitionedEventProcessor.DEFAULT_FORWARD_BATCH_SIZE;
    /**
     * Separates the {@link AggregateType} from the aggregate id in the queue key - see {@link #qualifiedKey(AggregateType, String)}.
     */
    public static final String KEY_SEPARATOR              = "|";

    private final Logger                        logger              = LoggerFactory.getLogger(this.getClass());
    private final PatternMatchingMessageHandler patternMatchingMessageHandlerDelegate;
    private final FencedLockManager             fencedLockManager;
    private final DurableQueues                 durableQueues;
    private final Consumer<Message>             queuedMessageConsumer;
    /**
     * Makes the lock-acquired and lock-released callbacks mutually exclusive, as in {@link ViewEventProcessor}: a release
     * delivered while the acquired callback still subscribes must find every subscription it created.
     */
    private final Object                        lockCallbackMonitor = new Object();
    private       DurableQueueConsumer          durableQueueConsumer;
    private       LockName                      lockName;
    private       UnhandledEventTypes           unhandledEventTypes;

    /**
     * @param dependencies the dependencies of a {@link ViewEventProcessor}, which this class shares
     */
    protected PartitionedViewEventProcessor(ViewEventProcessorDependencies dependencies) {
        this(requireNonNull(dependencies, "dependencies is null").eventStoreSubscriptionManager(),
             dependencies.fencedLockManager(),
             dependencies.durableQueues(),
             dependencies.commandBus(),
             dependencies.messageHandlerInterceptors());
    }

    /**
     * @param subscriptionManager manages the reader's event store subscriptions
     * @param fencedLockManager   provides the reader's lock
     * @param durableQueues       the queue the events are forwarded to and consumed from - must
     *                            {@link DurableQueues#keepsKeyOrderAcrossInstances() keep key order across instances}
     * @param commandBus          where {@code @Handler}/{@code @CmdHandler} methods of the subclass are registered
     * @param interceptors        intercept the calls to the {@code @MessageHandler} methods
     */
    protected PartitionedViewEventProcessor(EventStoreSubscriptionManager subscriptionManager,
                                            FencedLockManager fencedLockManager,
                                            DurableQueues durableQueues,
                                            DurableLocalCommandBus commandBus,
                                            List<MessageHandlerInterceptor> interceptors) {
        super(subscriptionManager, commandBus, interceptors);
        this.fencedLockManager = requireNonNull(fencedLockManager, "fencedLockManager is null");
        this.durableQueues = requireNonNull(durableQueues, "durableQueues is null");
        patternMatchingMessageHandlerDelegate = new PatternMatchingMessageHandler(this, getMessageHandlerInterceptors());
        patternMatchingMessageHandlerDelegate.allowUnmatchedMessages();
        queuedMessageConsumer = handleQueuedMessageConsumer(patternMatchingMessageHandlerDelegate);
    }

    /**
     * The queue key of an aggregate's events: {@code <aggregateType>|<serializedAggregateId>}.
     *
     * @param aggregateType         the aggregate type
     * @param serializedAggregateId the aggregate id, serialized by the aggregate type's {@link AggregateIdSerializer}
     * @return the key the aggregate's events are queued under
     */
    public static String qualifiedKey(AggregateType aggregateType, String serializedAggregateId) {
        requireNonNull(aggregateType, "aggregateType is null");
        requireNonNull(serializedAggregateId, "serializedAggregateId is null");
        return aggregateType.value() + KEY_SEPARATOR + serializedAggregateId;
    }

    @Override
    public void start() {
        if (started) return;
        var processorName              = requireNonNull(getProcessorName(), "getProcessorName() returned null");
        var subscribeToEventsRelatedTo = requireNonNull(reactsToEventsRelatedToAggregateTypes(), "reactsToEventsRelatedToAggregateTypes() returned null");
        // Both checks before 'started' is set, so a rejected processor reports isStarted() == false and a second start() throws again
        if (!durableQueues.keepsKeyOrderAcrossInstances()) {
            throw new IllegalStateException(msg("[{}] PartitionedViewEventProcessor needs a queue implementation that keeps an ordered message key in order across " +
                                                "instances, which {} does not: with more than one instance, one aggregate's events could be handled out of order. " +
                                                "Use the shard-owned queue engine (essentials.shard-owned-queue.durable-queues-enabled=true), or extend ViewEventProcessor",
                                                processorName, durableQueues.getClass().getSimpleName()));
        }
        if (patternMatchingMessageHandlerDelegate.hasNonTransactionalMessageHandlers()) {
            throw new IllegalStateException(msg("PartitionedViewEventProcessor '{}' declares one or more @MessageHandler methods with UnitOfWorkMode.NONE, which it doesn't support - " +
                                                "it handles every message inside a single UnitOfWork. Use a PartitionedEventProcessor for handlers that need to perform blocking I/O",
                                                processorName));
        }
        started = true;
        startOrRollBack(() -> {
            logger.info("🎑⚙️  [{}] Starting PartitionedViewEventProcessor - will subscribe to events related to these AggregatesType's: {}",
                        processorName,
                        subscribeToEventsRelatedTo);
            this.durableQueueName = QueueName.of(processorName + ":queue");
            this.lockName = LockName.of(processorName + ":lock");
            UnhandledEventTypes.logViewSummary(processorName, this, patternMatchingMessageHandlerDelegate);
            warnOnceIfEventStoreIsNoAwaitedGapInspector();
            this.unhandledEventTypes = new UnhandledEventTypes(processorName, eventStore, this, patternMatchingMessageHandlerDelegate, UnhandledEventTypes.Reporting.WARN);

            // Every instance consumes; the reader below runs only where the lock is
            var redeliveryPolicy = getDurableQueueRedeliveryPolicy();
            logRedeliveryPolicy(processorName, redeliveryPolicy, failoverRedeliveryPolicy(durableQueues.maxUnservedAfterInstanceLoss()));
            // Registered while holding the reset lock: a reset either counts this consumer, or this start waits for it
            StoppedEverywhereReset.startConsumingExclusiveOfAReset(processorName, fencedLockManager, StoppedEverywhereReset.START_WAITS_FOR_RESET,
                                                                   () -> durableQueueConsumer = durableQueues.consumeFromQueue(durableQueueName,
                                                                                                                               redeliveryPolicy,
                                                                                                                               getNumberOfParallelQueuedMessageConsumers(),
                                                                                                                               queuedMessage -> eventStore.getUnitOfWorkFactory()
                                                                                                                                                          .usingUnitOfWork(uow -> handleQueuedMessage(queuedMessage))));
            logger.info("🎑⚙️  [{}] Consuming '{}' with {} thread(s) on this instance: every instance handles its share of the aggregates",
                        processorName, durableQueueName, getNumberOfParallelQueuedMessageConsumers());

            fencedLockManager.acquireLockAsync(lockName,
                                               LockCallback.builder()
                                                           .onLockAcquired(lock -> onLockAcquired(processorName, subscribeToEventsRelatedTo))
                                                           .onLockReleased(lock -> onLockReleased(processorName))
                                                           .build());
        }, () -> {
            var consumer = durableQueueConsumer;
            durableQueueConsumer = null;
            if (consumer != null) {
                consumer.cancel();
            }
        }, () -> started = false);
    }

    private void onLockAcquired(String processorName, List<AggregateType> subscribeToEventsRelatedTo) {
        synchronized (lockCallbackMonitor) {
            logger.info("FencedLock '{}' for PartitionedViewEventProcessor '{}' was ACQUIRED - will read the events and forward them to '{}'", lockName, processorName, durableQueueName);
            // A failing subscribe unsubscribes the ones before it and throws: the lock manager releases the lock and it is acquired again later
            var forwardBatchSize = Math.max(1, getForwardBatchSize());
            var forwardMaxLatency = requireNonNull(getForwardMaxLatency(), "getForwardMaxLatency() returned null");
            eventStoreSubscriptions = subscribeEachOrUnsubscribe(subscribeToEventsRelatedTo, aggregateType -> {
                var subscription = subscribeForwardingInBatches(resolveSubscriberId(aggregateType, processorName), aggregateType, forwardBatchSize, forwardMaxLatency, processorName);
                logger.info("🎑⚙️ [{}] Created '{}' subscription forwarding in batches of up to {}: {}", processorName, aggregateType, forwardBatchSize, subscription);
                return subscription;
            });
        }
    }

    private void onLockReleased(String processorName) {
        synchronized (lockCallbackMonitor) {
            var subscriptions = eventStoreSubscriptions;
            if (subscriptions == null) {
                return;
            }
            logger.info("FencedLock '{}' for PartitionedViewEventProcessor '{}' was RELEASED - will unsubscribe {} subscriber(s)", lockName, processorName, subscriptions.size());
            // Unsubscribed, not just stopped - see AbstractEventProcessor#unsubscribeEach. The (unsubscribed) subscriptions are
            // kept: a reset of the stopped processor resets their resume points
            Exceptions.forEachThenThrowFirst(subscriptions, (EventStoreSubscription subscription) -> {
                try {
                    subscription.unsubscribe();
                } catch (Throwable failure) {
                    logger.error("FencedLock '{}' for PartitionedViewEventProcessor '{}' was RELEASED - failed to unsubscribe subscriber {}", lockName, processorName, subscription, failure);
                    throw failure;
                }
            });
        }
    }

    private EventStoreSubscription subscribeForwardingInBatches(SubscriberId subscriberId,
                                                                AggregateType aggregateType,
                                                                int forwardBatchSize,
                                                                Duration forwardMaxLatency,
                                                                String processorName) {
        // Non-exclusive: the processor's own lock makes it exclusive, as for ViewEventProcessor - same subscriber id and
        // lock name, so a view switched to this class keeps its resume point
        return eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(
                subscriberId,
                aggregateType,
                resolveStartSubscriptionFromGlobalEventOrder().apply(aggregateType),
                Optional.empty(),
                forwardBatchSize,
                forwardMaxLatency,
                new BatchedPersistedEventHandler() {
                    @Override
                    public int handleBatch(List<PersistedEvent> events) {
                        forwardBatch(events);
                        return forwardBatchSize;
                    }

                    @Override
                    public Optional<SubscriptionErrorPolicy> subscriptionErrorPolicy() {
                        return getSubscriptionErrorPolicy();
                    }

                    @Override
                    public String toString() {
                        return processorName;
                    }
                });
    }

    /**
     * Queue the batch's events this processor has a handler for, in one {@link DurableQueues#queueMessages(QueueName, List)},
     * inside the batch's unit of work - which also moves the resume point, so the batch is forwarded and the resume point
     * moved together, or neither. The events arrive in global order, so each aggregate's events are queued in ascending
     * {@link EventOrder}, which the queue's ordered lane requires of its producer.
     *
     * @param events the batch, in the order the subscription delivered it
     */
    void forwardBatch(List<PersistedEvent> events) {
        var messages = new ArrayList<OrderedMessage>(events.size());
        for (var event : events) {
            if (hasNoHandlerFor(event)) {
                if (unhandledEventTypes != null) {
                    unhandledEventTypes.report(event);
                }
                continue;
            }
            messages.add(toQueuedMessage(event));
        }
        if (!messages.isEmpty()) {
            durableQueues.queueMessages(durableQueueName, messages);
        }
    }

    private OrderedMessage toQueuedMessage(PersistedEvent event) {
        var aggregateType = event.aggregateType();
        var key           = qualifiedKey(aggregateType, resolveAggregateIdSerializer(aggregateType).serialize(event.aggregateId()));
        MessageMetaData meta;
        try {
            meta = new MessageMetaData(event.metaData().deserialize());
        } catch (RuntimeException e) {
            // The event itself is resolved again by the consumer, which reports a failure to deserialize it
            logger.debug("[{}:{}] Could not deserialize the meta data of the event with event-order '{}' - forwarding it without", aggregateType, key, event.eventOrder(), e);
            meta = new MessageMetaData();
        }
        return new EventReferenceOrderedMessage(aggregateType, key, event.eventOrder(), meta);
    }

    /**
     * True if no {@link MessageHandler} method matches the event's type - as {@link ViewEventProcessor} decides it. An
     * event whose Java type is not known is forwarded, so a failure to resolve it shows as a dead letter instead of a
     * silently missing event.
     */
    private boolean hasNoHandlerFor(PersistedEvent event) {
        try {
            return event.event().getEventType().isPresent() &&
                    !patternMatchingMessageHandlerDelegate.handlesMessageWithPayload(event.event().getEventTypeAsJavaClass().get());
        } catch (RuntimeException unresolvableType) {
            return false;
        }
    }

    private void handleQueuedMessage(QueuedMessage queuedMessage) {
        // No QueuedMessage#getId(): an engine is entitled not to have one on its push path - see ViewEventProcessor
        var msg = queuedMessage.getMessage();
        if (msg instanceof OrderedMessage orderedMessage && AbstractEventProcessor.EventReferenceOrderedMessage.isEventReference(orderedMessage)
                && orderedMessage.getPayload() instanceof AggregateType aggregateType) {
            // The reference resolver reads the key as the aggregate id, and the handlers expect it too: hand both the plain id
            var prefix = aggregateType.value() + KEY_SEPARATOR;
            var key    = orderedMessage.getKey();
            if (key.startsWith(prefix)) {
                msg = OrderedMessage.of(aggregateType, key.substring(prefix.length()), orderedMessage.getOrder(), orderedMessage.getMetaData());
            }
        }
        queuedMessageConsumer.accept(msg);
    }

    @Override
    public void stop() {
        if (!started) return;
        started = false;
        logger.info("🎑⚙️  [{}] Stopping PartitionedViewEventProcessor", getProcessorName());
        try {
            // Releases the lock, if held here, which unsubscribes the reader
            fencedLockManager.cancelAsyncLockAcquiring(lockName);
        } finally {
            var consumer = durableQueueConsumer;
            durableQueueConsumer = null;
            if (consumer != null) {
                consumer.cancel();
            }
        }
        logger.info("🎑⚙️  [{}] PartitionedViewEventProcessor Stopped", getProcessorName());
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    /**
     * The most events forwarded to the queue in one unit of work. Default {@value #DEFAULT_FORWARD_BATCH_SIZE}. A batch is
     * forwarded once it is full or {@link #getForwardMaxLatency()} has passed.
     *
     * @return the most events forwarded in one unit of work; values below {@code 1} count as {@code 1}
     */
    protected int getForwardBatchSize() {
        return DEFAULT_FORWARD_BATCH_SIZE;
    }

    /**
     * The longest a partial batch waits for more events before it is forwarded. Default 20 ms.
     *
     * @return the maximum time a partial batch waits
     */
    protected Duration getForwardMaxLatency() {
        return Duration.ofMillis(20);
    }

    /**
     * Default: {@link #failoverRedeliveryPolicy(Optional)} with the queue's window, as {@link ViewEventProcessor}.
     */
    @Override
    protected RedeliveryPolicy getDurableQueueRedeliveryPolicy() {
        return failoverRedeliveryPolicy(durableQueues.maxUnservedAfterInstanceLoss()).policy();
    }

    /**
     * @return the {@link DurableQueues} the events are forwarded to and consumed from
     */
    public DurableQueues getDurableQueues() {
        return durableQueues;
    }

    @Override
    protected boolean hasQueuedMessagesForAggregate(AggregateType aggregateType, String serializedAggregateId) {
        var queueName = durableQueueName;
        if (queueName == null) {
            return true;
        }
        return durableQueues.hasOrderedMessageQueuedForKey(queueName, qualifiedKey(aggregateType, serializedAggregateId));
    }

    @Override
    protected Optional<QueuedMessageCounts> getQueuedMessageCountsForAwaitProcessed() {
        var queueName = durableQueueName;
        if (queueName == null) {
            return Optional.empty();
        }
        return Optional.of(durableQueues.getQueuedMessageCountsFor(queueName));
    }

    /**
     * Reset every subscription to the first event and purge the queue - only while the processor is stopped on every
     * instance, see "Reset" in the class javadoc.
     *
     * @throws IllegalStateException if the processor runs on this or another instance, or that cannot be checked
     */
    public void resetAllSubscriptions() {
        resetAllSubscriptions(durableQueues::purgeQueue);
    }

    /**
     * Reset the given aggregate types' subscriptions, and optionally purge the queue - only while the processor is
     * stopped on every instance, see "Reset" in the class javadoc.
     *
     * @param resetAggregateSubscriptionsFromAndIncluding the aggregate types to reset, each with the global event order to
     *                                                    restart from and including
     * @param resetDurableQueue                           purge the queue
     * @throws IllegalStateException if the processor runs on this or another instance, or that cannot be checked
     */
    public void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding,
                                   boolean resetDurableQueue) {
        resetSubscriptions(resetAggregateSubscriptionsFromAndIncluding, resetDurableQueue, durableQueues::purgeQueue);
    }

    /**
     * Every aggregate type the processor reacts to - not only those this instance has subscriptions for, as it may never
     * have read.
     */
    @Override
    protected void resetAllSubscriptions(Consumer<QueueName> resetDurableQueueCallback) {
        var resetParameters = new LinkedHashMap<AggregateType, GlobalEventOrder>();
        requireNonNull(reactsToEventsRelatedToAggregateTypes(), "reactsToEventsRelatedToAggregateTypes() returned null")
                .forEach(aggregateType -> resetParameters.put(aggregateType, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER));
        resetSubscriptions(resetParameters, true, resetDurableQueueCallback);
    }

    /**
     * The one path every reset of this processor takes: refused unless the processor is stopped on every instance, then
     * the stored resume points are moved, the queue purged (if asked) and {@link #onSubscriptionsReset(AggregateType, GlobalEventOrder)}
     * called per aggregate type - see {@link StoppedEverywhereReset}.
     */
    @Override
    protected void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding,
                                      boolean resetDurableQueue,
                                      Consumer<QueueName> resetDurableQueueCallback) {
        guardedReset(resetAggregateSubscriptionsFromAndIncluding, resetDurableQueue, resetDurableQueueCallback, false);
    }

    /**
     * Reset every subscription to the first event and purge the queue although departed instances still hold part of
     * it - see {@link StoppedEverywhereReset}. <b>Only once you have made sure their processes are gone</b>: such an
     * instance either crashed, or stopped while a handler was still running, and that handler would act on an event from
     * before the reset after it. Everything else is still checked: the processor stopped here, no reader, no live consumer.
     *
     * @throws IllegalStateException if the processor runs on this or another instance, or that cannot be checked
     */
    public void resetAllSubscriptionsIgnoringDepartedInstances() {
        var resetParameters = new LinkedHashMap<AggregateType, GlobalEventOrder>();
        requireNonNull(reactsToEventsRelatedToAggregateTypes(), "reactsToEventsRelatedToAggregateTypes() returned null")
                .forEach(aggregateType -> resetParameters.put(aggregateType, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER));
        guardedReset(resetParameters, true, durableQueues::purgeQueue, true);
    }

    private void guardedReset(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding,
                              boolean resetDurableQueue,
                              Consumer<QueueName> resetDurableQueueCallback,
                              boolean ignoreDepartedInstances) {
        requireNonNull(resetAggregateSubscriptionsFromAndIncluding, "resetAggregateSubscriptionsFromAndIncluding is null");
        requireNonNull(resetDurableQueueCallback, "resetDurableQueueCallback is null");
        var processorName = requireNonNull(getProcessorName(), "getProcessorName() returned null");
        var reactsTo      = requireNonNull(reactsToEventsRelatedToAggregateTypes(), "reactsToEventsRelatedToAggregateTypes() returned null");
        resetAggregateSubscriptionsFromAndIncluding.keySet().forEach(aggregateType -> {
            if (!reactsTo.contains(aggregateType)) {
                throw new IllegalArgumentException(msg("[{}] Cannot reset subscription for {} '{}' since the processor doesn't subscribe to events for this aggregate type",
                                                       processorName, AggregateType.class.getSimpleName(), aggregateType));
            }
        });
        var queueName = QueueName.of(processorName + ":queue");
        StoppedEverywhereReset.reset(processorName,
                                     started,
                                     fencedLockManager,
                                     List.of(LockName.of(processorName + ":lock")),
                                     "queue '" + queueName + "'",
                                     () -> durableQueues.getQueueConsumers(queueName),
                                     ignoreDepartedInstances,
                                     () -> {
                                         resetAggregateSubscriptionsFromAndIncluding.forEach((aggregateType, from) ->
                                                 eventStoreSubscriptionManager.resetStoredResumePoint(resolveSubscriberId(aggregateType, processorName), aggregateType, from));
                                         if (resetDurableQueue) {
                                             logger.info("[{}] Purging queue '{}'", processorName, queueName);
                                             resetDurableQueueCallback.accept(queueName);
                                         }
                                         resetAggregateSubscriptionsFromAndIncluding.forEach(this::onSubscriptionsReset);
                                     });
    }

    /**
     * Resets the subscription's aggregate type through {@link #resetSubscriptions(Map, boolean, Consumer)}: refused unless
     * the processor is stopped on every instance.
     */
    @Override
    protected void doResetSubscription(EventStoreSubscription eventStoreSubscription,
                                       GlobalEventOrder resubscribeFromAndIncluding,
                                       boolean resetDurableQueue,
                                       Consumer<QueueName> resetDurableQueueCallback) {
        requireNonNull(eventStoreSubscription, "eventStoreSubscription is null");
        requireNonNull(resubscribeFromAndIncluding, "resubscribeFromAndIncluding is null");
        resetSubscriptions(Map.of(eventStoreSubscription.aggregateType(), resubscribeFromAndIncluding), resetDurableQueue, resetDurableQueueCallback);
    }

    @Override
    public String toString() {
        return "🎑⚙️ " + this.getClass().getSimpleName() + " { " +
                "processorName='" + getProcessorName() + "'" +
                ", reactsToEventsRelatedToAggregateTypes=" + reactsToEventsRelatedToAggregateTypes() +
                ", started=" + started +
                " }";
    }
}
