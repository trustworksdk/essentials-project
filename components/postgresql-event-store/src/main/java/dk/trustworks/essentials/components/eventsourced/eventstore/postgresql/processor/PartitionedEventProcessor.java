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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;

import java.util.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * An {@link EventProcessor} whose {@code @MessageHandler}s run on <b>every</b> instance, each instance handling its share
 * of the aggregates, instead of on the one instance that holds the {@link Inbox}'s lock.
 * <p>
 * <b>What it does:</b> one instance reads the events (the exclusive subscription, as in {@link EventProcessor}) and
 * forwards them to the processor's {@link Inbox} in batches ({@link #getForwardBatchSize()} defaults to
 * {@value #DEFAULT_FORWARD_BATCH_SIZE}). Every instance consumes the {@link Inbox}
 * ({@link MessageConsumptionMode#GlobalCompetingConsumers}); the queue implementation gives each aggregate to one instance
 * at a time, so one aggregate's events are still handled one at a time, in order.
 * <p>
 * <b>What it does not do:</b> keep order <i>across</i> aggregates; or start on a queue implementation that keeps an
 * ordered key in order per node only ({@link Inboxes#keepsKeyOrderAcrossInstances()} {@code false}, e.g.
 * {@code PostgresqlDurableQueues}): {@link #start()} refuses, as competing consumers there could handle one aggregate's
 * events out of order on two instances. Use the shard-owned queue engine
 * ({@code essentials.shard-owned-queue.durable-queues-enabled=true}) - in every environment the processor runs in, tests
 * included.
 * <p>
 * <b>Be aware:</b>
 * <ul>
 *     <li>Handlers run on several instances at once, for different aggregates. Not for a handler that keeps in-memory
 *     state across aggregates, or calls something that accepts one caller only - use {@link EventProcessor} for that.</li>
 *     <li>Handlers of different aggregates that write one shared stream conflict on it more often; the {@link Inbox}
 *     redelivers them.</li>
 *     <li>Handlers must be idempotent: a failover can deliver a message twice (true of every processor, more likely here).</li>
 *     <li><b>A reset runs only while the processor is stopped on every instance</b>, unlike {@link EventProcessor}'s live
 *     reset on the instance that reads: a handler still running on another instance would otherwise act on an event from
 *     before the reset after it. Refused while the processor is started on this instance, while an instance holds a
 *     reader lock, and while the queue implementation counts any instance consuming the {@link Inbox}; a starting
 *     instance cannot register its consumer while a reset runs. It can run on any instance. Details:
 *     {@link StoppedEverywhereReset}. {@link #start()} needs the {@link EventStoreSubscriptionManager}'s
 *     {@link FencedLockManager} for it.</li>
 * </ul>
 * The consumption mode is fixed ({@link #getInboxMessageConsumptionMode()} is final): the contract is the class.
 * <p>
 * Every reset override delegates to {@link StoppedEverywhereReset}'s shared reset, as {@link PartitionedViewEventProcessor}'s
 * do: a reset overload added to {@link AbstractEventProcessor} must be overridden onto it in both classes, or it resets live.
 */
public abstract class PartitionedEventProcessor extends EventProcessor {
    /**
     * The default {@link #getForwardBatchSize()}: forwarding pays per batch, not per event, and only pays together with
     * more consumers - which a partitioned processor has by running on every instance.
     */
    public static final int DEFAULT_FORWARD_BATCH_SIZE = 100;

    /**
     * @param eventProcessorDependencies the dependencies of an {@link EventProcessor}
     */
    protected PartitionedEventProcessor(EventProcessorDependencies eventProcessorDependencies) {
        super(eventProcessorDependencies);
    }

    /**
     * @see EventProcessor#EventProcessor(EventStoreSubscriptionManager, Inboxes, DurableLocalCommandBus, List)
     */
    protected PartitionedEventProcessor(EventStoreSubscriptionManager eventStoreSubscriptionManager,
                                        Inboxes inboxes,
                                        DurableLocalCommandBus commandBus,
                                        List<MessageHandlerInterceptor> messageHandlerInterceptors) {
        super(eventStoreSubscriptionManager, inboxes, commandBus, messageHandlerInterceptors);
    }

    /**
     * Always {@link MessageConsumptionMode#GlobalCompetingConsumers}: every instance handles the {@link Inbox}.
     */
    @Override
    protected final MessageConsumptionMode getInboxMessageConsumptionMode() {
        return MessageConsumptionMode.GlobalCompetingConsumers;
    }

    /**
     * Starts as {@link EventProcessor#start()} does, holding the processor's reset lock while it registers its consumer of
     * the {@link Inbox}, so a reset either counts this instance or this start waits for it (see {@link StoppedEverywhereReset}).
     *
     * @throws IllegalStateException if the {@link EventStoreSubscriptionManager} has no {@link FencedLockManager}, or a reset
     *                               is still in progress after {@link StoppedEverywhereReset#START_WAITS_FOR_RESET}
     */
    @Override
    public void start() {
        if (isStarted()) {
            return;
        }
        var processorName = requireNonNull(getProcessorName(), "getProcessorName() returned null");
        StoppedEverywhereReset.startConsumingExclusiveOfAReset(processorName, lockManager(processorName), StoppedEverywhereReset.START_WAITS_FOR_RESET, super::start);
    }

    private FencedLockManager lockManager(String processorName) {
        return eventStoreSubscriptionManager.getFencedLockManager()
                                            .orElseThrow(() -> new IllegalStateException(msg("[{}] A PartitionedEventProcessor needs the EventStoreSubscriptionManager's FencedLockManager " +
                                                                                             "to keep a reset and a start apart, and {} exposes none",
                                                                                             processorName, eventStoreSubscriptionManager.getClass().getName())));
    }

    /**
     * Every aggregate type the processor reacts to - not only those this instance has subscriptions for, as it may never
     * have read.
     */
    @Override
    protected void resetAllSubscriptions(Consumer<QueueName> resetDurableQueueCallback) {
        resetSubscriptions(StoppedEverywhereReset.everyAggregateTypeFromTheFirstEvent(this), true, resetDurableQueueCallback);
    }

    /**
     * The one path every reset of this processor takes: refused unless the processor is stopped on every instance, then
     * the stored resume points are moved, the {@link Inbox} purged (if asked) and
     * {@link #onSubscriptionsReset(AggregateType, GlobalEventOrder)} called per aggregate type - see {@link StoppedEverywhereReset}.
     * <p>
     * The purge calls {@code resetDurableQueueCallback} on an instance that started the processor; on one that never did,
     * it has no {@link Inbox} for the callback to act on, and purges the {@link Inbox} itself.
     */
    @Override
    protected void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding,
                                      boolean resetDurableQueue,
                                      Consumer<QueueName> resetDurableQueueCallback) {
        StoppedEverywhereReset.resetProcessor(this, this::resetTarget, resetAggregateSubscriptionsFromAndIncluding, resetDurableQueue,
                                              purgingWith(resetDurableQueueCallback), false);
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
        StoppedEverywhereReset.resetProcessor(this, this::resetTarget, StoppedEverywhereReset.everyAggregateTypeFromTheFirstEvent(this), true,
                                              purgingWith(queueName -> purgeInbox()), true);
    }

    /**
     * The reader locks are the exclusive subscription's, one per aggregate type the processor reacts to; the queue is the
     * {@link Inbox}.
     */
    private StoppedEverywhereReset.ResetTarget resetTarget(String processorName) {
        var inboxName = InboxName.of(processorName);
        var readerLocks = requireNonNull(reactsToEventsRelatedToAggregateTypes(), "reactsToEventsRelatedToAggregateTypes() returned null")
                .stream()
                .map(aggregateType -> EventStoreSubscriptionManager.exclusiveSubscriptionLockName(resolveSubscriberId(aggregateType, processorName), aggregateType))
                .toList();
        return new StoppedEverywhereReset.ResetTarget(lockManager(processorName),
                                                      readerLocks,
                                                      inboxName.asQueueName(),
                                                      "inbox '" + inboxName + "'",
                                                      this::getInboxConsumers);
    }

    /**
     * The purge calls {@code resetDurableQueueCallback} on an instance that started the processor; on one that never did,
     * it has no {@link Inbox} for the callback to act on, and purges the {@link Inbox} itself.
     */
    private Consumer<QueueName> purgingWith(Consumer<QueueName> resetDurableQueueCallback) {
        requireNonNull(resetDurableQueueCallback, "resetDurableQueueCallback is null");
        return queueName -> {
            if (getInbox() != null) {
                resetDurableQueueCallback.accept(queueName);
            } else {
                purgeInbox();
            }
        };
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
        resetSubscriptions(StoppedEverywhereReset.subscriptionsAggregateType(eventStoreSubscription, resubscribeFromAndIncluding), resetDurableQueue, resetDurableQueueCallback);
    }

    /**
     * Default {@value #DEFAULT_FORWARD_BATCH_SIZE}; override to tune.
     */
    @Override
    protected int getForwardBatchSize() {
        return DEFAULT_FORWARD_BATCH_SIZE;
    }
}
