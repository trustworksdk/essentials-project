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
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.types.*;
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
 * moves the stored resume points itself. Details: {@link StoppedEverywhereReset}. Every reset method is {@code final} here
 * and routes onto {@link StoppedEverywhereReset}'s shared reset, as {@link PartitionedEventProcessor}'s do, so a subclass
 * cannot skip the stopped-everywhere check - a {@link ViewEventProcessor} switched to this class that customised its reset
 * moves that code to {@link #onSubscriptionsReset(AggregateType, GlobalEventOrder)}, which the shared reset calls per
 * aggregate type. {@code PartitionedProcessorResetRoutingTest} fails on a {@code reset*}/{@code doReset*} method of a
 * superclass that is not overridden {@code final} in each partitioned processor that inherits it.
 * <p>
 * <b>Switching an existing {@link ViewEventProcessor} to this class</b> keeps its resume point, lock and queue names.
 * Stop the view on every instance first, and deploy the new class everywhere before starting it again: a cluster that
 * mixes the two would handle one view both inline and partitioned.
 * <p>
 * <b>Drain the queue before switching.</b> The queue name stays, but the key does not: {@link ViewEventProcessor}
 * queues a failed event under the plain aggregate id, this class under {@code <aggregateType>|<aggregateId>}. A message
 * the old processor left in {@code <processorName>:queue} - a pending redelivery or a dead letter - is therefore not
 * ordered with the events this class queues for the same aggregate: newer events are handled first, and the old message
 * is applied on top of them when it is redelivered or resurrected. {@link #awaitProcessed(AggregateType, GlobalEventOrder, Duration)}
 * does not see it either. Before the switch, let {@link DurableQueues#getTotalMessagesQueuedFor(QueueName)} reach
 * {@code 0} and resolve every dead letter ({@link DurableQueues#getTotalDeadLetterMessagesQueuedFor(QueueName)}).
 * Nothing enforces this at start-up.
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
    /**
     * {@link MessageMetaData} key of a forwarded event's {@link AggregateType}, set when {@link #forwardsEventPayloads()}.
     */
    public static final String AGGREGATE_TYPE_METADATA_KEY = "PARTITIONED_VIEW_AGGREGATE_TYPE";
    /**
     * {@link MessageMetaData} key of a forwarded event's {@link EventId}, set when {@link #forwardsEventPayloads()}: the
     * handler runs with {@link CausationContext} bound to it, as it does for a forwarded reference.
     */
    public static final String EVENT_ID_METADATA_KEY       = "PARTITIONED_VIEW_EVENT_ID";

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
                    public boolean handOffFailedBatch(List<PersistedEvent> events, Throwable failure, SubscriptionErrorPolicy subscriptionErrorPolicy) {
                        return forwardFailedBatchOneEventPerUnitOfWork(events, failure, subscriptionErrorPolicy, event -> forwardBatch(List.of(event)));
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
            // As ViewEventProcessor decides it: an event whose Java type is not known is forwarded, so a failure to resolve
            // it shows as a dead letter instead of a silently missing event
            if (ViewEventProcessor.hasNoHandlerFor(patternMatchingMessageHandlerDelegate, event)) {
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

    @SuppressWarnings("PMD.LooseCoupling") // MessageMetaData is the type OrderedMessage takes as its meta data, not a choice of Map implementation
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
        if (forwardsEventPayloads()) {
            try {
                var payload = event.event().deserialize();
                meta.put(AGGREGATE_TYPE_METADATA_KEY, aggregateType.toString());
                meta.put(EVENT_ID_METADATA_KEY, event.eventId().toString());
                return new EventPayloadOrderedMessage(payload, key, event.eventOrder().longValue(), meta);
            } catch (RuntimeException e) {
                // Forwarded as a reference: the consumer resolves it again and, if it still cannot be deserialized, dead-letters it
                logger.debug("[{}:{}] Could not deserialize the event with event-order '{}' - forwarding a reference to it instead", aggregateType, key, event.eventOrder(), e);
                meta.remove(AGGREGATE_TYPE_METADATA_KEY);
                meta.remove(EVENT_ID_METADATA_KEY);
            }
        }
        return new EventReferenceOrderedMessage(aggregateType, key, event.eventOrder(), meta);
    }

    /**
     * Forward each event's payload instead of a reference to it. Default {@code false}: a reference, which the consumer
     * resolves by loading the event from the event store - small queue rows, the event store authoritative, but a query
     * and a deserialization per event on the handling side. {@code true}: the reader deserializes the event once and the
     * queue carries it - no load on the handling side, larger queue rows and more WAL. An event that cannot be deserialized
     * is still forwarded as a reference.
     *
     * @return whether to forward event payloads
     */
    protected boolean forwardsEventPayloads() {
        return false;
    }

    /**
     * An event forwarded with its payload ({@link #forwardsEventPayloads()}). Identified by key and order, like a reference,
     * so a re-forward of one still queued after a reader failover is absorbed by the queue.
     */
    public static final class EventPayloadOrderedMessage extends OrderedMessage implements IdentifiedByKeyAndOrder {
        @SuppressWarnings("PMD.LooseCoupling") // MessageMetaData is the type OrderedMessage's constructor takes, not a choice of Map implementation
        EventPayloadOrderedMessage(Object payload, String key, long order, MessageMetaData metaData) {
            super(payload, key, order, metaData);
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
        if (msg instanceof OrderedMessage orderedMessage && !AbstractEventProcessor.EventReferenceOrderedMessage.isEventReference(orderedMessage)
                && orderedMessage.getMetaData().get(AGGREGATE_TYPE_METADATA_KEY) != null) {
            // A forwarded payload: hand the handlers the plain id, bound to the event as its cause, as for a reference
            var prefix  = orderedMessage.getMetaData().get(AGGREGATE_TYPE_METADATA_KEY) + KEY_SEPARATOR;
            var key     = orderedMessage.getKey();
            var plain   = OrderedMessage.of(orderedMessage.getPayload(), key.startsWith(prefix) ? key.substring(prefix.length()) : key,
                                            orderedMessage.getOrder(), orderedMessage.getMetaData());
            var eventId = orderedMessage.getMetaData().get(EVENT_ID_METADATA_KEY);
            if (eventId != null) {
                CausationContext.where(EventId.of(eventId)).run(() -> queuedMessageConsumer.accept(plain));
            } else {
                queuedMessageConsumer.accept(plain);
            }
            return;
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
     * forwarded once it is full or {@link #getForwardMaxLatency()} has passed. The {@code SubscriptionErrorPolicy} retries
     * and stops at a batch as a whole; a policy that skips first forwards the failed batch's events again one per unit of
     * work, each tried once more on its own, without the policy's retries, so the skip loses only the events that fail on
     * their own as well. An event that fails there with an I/O or connection failure is tried once more at once in a new
     * unit of work; the replay stops only when that retry fails the same way and the database does not answer a probe
     * either (see {@link EventProcessor#getForwardBatchSize()} for when a skip still loses up to a batch).
     *
     * @return the most events forwarded in one unit of work; values below {@code 1} count as {@code 1}
     */
    protected int getForwardBatchSize() {
        return DEFAULT_FORWARD_BATCH_SIZE;
    }

    /**
     * The longest a partial batch waits for more events before it is forwarded. Default {@link #DEFAULT_BATCH_MAX_LATENCY}.
     *
     * @return the maximum time a partial batch waits
     */
    protected Duration getForwardMaxLatency() {
        return DEFAULT_BATCH_MAX_LATENCY;
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
    public final void resetAllSubscriptions() {
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
    public final void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding,
                                         boolean resetDurableQueue) {
        resetSubscriptions(resetAggregateSubscriptionsFromAndIncluding, resetDurableQueue, durableQueues::purgeQueue);
    }

    /**
     * Reset the given aggregate types' subscriptions without purging the queue - only while the processor is stopped on
     * every instance, see {@link #resetSubscriptions(Map, boolean, Consumer)}.
     *
     * @throws IllegalStateException if the processor runs on this or another instance, or that cannot be checked
     */
    @Override
    protected final void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding) {
        super.resetSubscriptions(resetAggregateSubscriptionsFromAndIncluding);
    }

    /**
     * Every aggregate type the processor reacts to - not only those this instance has subscriptions for, as it may never
     * have read.
     *
     * @throws IllegalStateException if the processor runs on this or another instance, or that cannot be checked
     */
    @Override
    protected final void resetAllSubscriptions(Consumer<QueueName> resetDurableQueueCallback) {
        resetSubscriptions(StoppedEverywhereReset.everyAggregateTypeFromTheFirstEvent(this), true, resetDurableQueueCallback);
    }

    /**
     * The one path every reset of this processor takes: refused unless the processor is stopped on every instance, then
     * the stored resume points are moved, the queue purged (if asked) and {@link #onSubscriptionsReset(AggregateType, GlobalEventOrder)}
     * called per aggregate type - see {@link StoppedEverywhereReset}.
     *
     * @throws IllegalStateException if the processor runs on this or another instance, or that cannot be checked
     */
    @Override
    protected final void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding,
                                            boolean resetDurableQueue,
                                            Consumer<QueueName> resetDurableQueueCallback) {
        StoppedEverywhereReset.resetProcessor(this, this::resetTarget, resetAggregateSubscriptionsFromAndIncluding, resetDurableQueue,
                                              resetDurableQueueCallback, false);
    }

    /**
     * Reset every subscription to the first event and purge the queue although departed instances still hold part of
     * it - see {@link StoppedEverywhereReset}. <b>Only once you have made sure their processes are gone</b>: such an
     * instance either crashed, or stopped while a handler was still running, and that handler would act on an event from
     * before the reset after it. Everything else is still checked: the processor stopped here, no reader, no live consumer.
     *
     * @throws IllegalStateException if the processor runs on this or another instance, or that cannot be checked
     */
    public final void resetAllSubscriptionsIgnoringDepartedInstances() {
        StoppedEverywhereReset.resetProcessor(this, this::resetTarget, StoppedEverywhereReset.everyAggregateTypeFromTheFirstEvent(this), true,
                                              durableQueues::purgeQueue, true);
    }

    /**
     * The reader lock is the processor's own ({@code <processorName>:lock}); the queue is {@code <processorName>:queue}.
     */
    private StoppedEverywhereReset.ResetTarget resetTarget(String processorName) {
        var queueName = QueueName.of(processorName + ":queue");
        return new StoppedEverywhereReset.ResetTarget(fencedLockManager,
                                                      List.of(LockName.of(processorName + ":lock")),
                                                      queueName,
                                                      "queue '" + queueName + "'",
                                                      () -> durableQueues.getQueueConsumers(queueName));
    }

    /**
     * Resets the subscription's aggregate type through {@link #resetSubscriptions(Map, boolean, Consumer)}: refused unless
     * the processor is stopped on every instance.
     *
     * @throws IllegalStateException if the processor runs on this or another instance, or that cannot be checked
     */
    @Override
    protected final void doResetSubscription(EventStoreSubscription eventStoreSubscription,
                                             GlobalEventOrder resubscribeFromAndIncluding,
                                             boolean resetDurableQueue,
                                             Consumer<QueueName> resetDurableQueueCallback) {
        resetSubscriptions(StoppedEverywhereReset.subscriptionsAggregateType(eventStoreSubscription, resubscribeFromAndIncluding), resetDurableQueue, resetDurableQueueCallback);
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
