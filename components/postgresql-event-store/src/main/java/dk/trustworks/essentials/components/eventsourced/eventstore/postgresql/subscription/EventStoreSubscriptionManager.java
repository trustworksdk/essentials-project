/*
 * Copyright 2021-2025 the original author or authors.
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

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EventJSON;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inbox;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.functional.tuple.Pair;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Provides support for durable {@link EventStore} subscriptions.<br>
 * The {@link EventStoreSubscriptionManager} will keep track of where in the Event Stream, for a given {@link AggregateType},
 * the individual subscribers are.<br>
 * If a subscription is added, the {@link EventStoreSubscriptionManager} will ensure that the subscriber resumes from where
 * it left of the last time, or ensure that they start from the specified <code>onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder</code>
 * in case it's the very first time the subscriber is subscribing
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public interface EventStoreSubscriptionManager extends Lifecycle {

    /**
     * The {@link EventStore} associated with the {@link EventStoreSubscriptionManager}
     *
     * @return the {@link EventStore} associated with the {@link EventStoreSubscriptionManager}
     */
    EventStore getEventStore();

    /**
     * Create a builder for the {@link EventStoreSubscriptionManager}
     *
     * @return a builder for the {@link EventStoreSubscriptionManager}
     */
    static EventStoreSubscriptionManagerBuilder builder() {
        return new EventStoreSubscriptionManagerBuilder();
    }

    /**
     * Create an asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param onlyIncludeEventsForTenant                              if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param eventHandler                                            the event handler that will receive the published {@link PersistedEvent}'s<br>
     *                                                                Exceptions thrown from the eventHandler will cause the event to be skipped. If you need a retry capability
     *                                                                please use {@link #subscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, Optional, Inbox)}
     * @return the subscription handle
     */
    EventStoreSubscription subscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                    AggregateType forAggregateType,
                                                                    GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                    Optional<Tenant> onlyIncludeEventsForTenant,
                                                                    PersistedEventHandler eventHandler);

    /**
     * Create an asynchronous batched subscription that will receive {@link PersistedEvent}s in batches after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem<br>
     * This subscription method is a batching alternative that processes events in batches
     * to improve throughput, at the cost of higher latency, and reduce database load. It should be used when high throughput is needed and the event handlers can
     * efficiently process batches of events.
     * <p>
     * Key features:
     * <ul>
     *   <li>Processes events in batches for improved throughput</li>
     *   <li>Tracks batch progress and updates resume points only after entire batch completes</li>
     *   <li>Only requests more events after batch processing completes</li>
     *   <li>Supports max latency for processing partial batches</li>
     * </ul>
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param onlyIncludeEventsForTenant                              if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param maxBatchSize                                            the maximum batch size before processing
     * @param maxLatency                                              the maximum time to wait before processing a partial batch
     * @param eventHandler                                            the event handler that will receive the published {@link PersistedEvent}'s in batches<br>
     *                                                                Exceptions thrown from the eventHandler will cause the events to be skipped.
     * @return the subscription handle
     */
    EventStoreSubscription batchSubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                         AggregateType forAggregateType,
                                                                         GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                         Optional<Tenant> onlyIncludeEventsForTenant,
                                                                         int maxBatchSize,
                                                                         Duration maxLatency,
                                                                         BatchedPersistedEventHandler eventHandler);

    /**
     * Create an asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param onlyIncludeEventsForTenant                              if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param forwardToInbox                                          The Inbox where the {@link PersistedEvent}'s will be forwarded to as an {@link OrderedMessage} containing
     *                                                                the {@link PersistedEvent#event()}'s {@link EventJSON#getJsonDeserialized()} as payload and the {@link PersistedEvent#aggregateId()} as {@link OrderedMessage#getKey()}
     *                                                                and the {@link PersistedEvent#eventOrder()} as {@link OrderedMessage#getOrder()}.<br>
     *                                                                This reuses the {@link Inbox} ability to retry event deliveries
     * @return the subscription handle
     */
    default EventStoreSubscription subscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                            AggregateType forAggregateType,
                                                                            GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                            Optional<Tenant> onlyIncludeEventsForTenant,
                                                                            Inbox forwardToInbox) {
        requireNonNull(forwardToInbox, "No forwardToInbox instance provided");
        return subscribeToAggregateEventsAsynchronously(subscriberId,
                                                        forAggregateType,
                                                        onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                        onlyIncludeEventsForTenant,
                                                        event -> forwardToInbox.addMessageReceived(OrderedMessage.of(event.event().getJsonDeserialized().get(),
                                                                                                                     event.aggregateId().toString(),
                                                                                                                     event.eventOrder().longValue())));
    }

    /**
     * Create an asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param forwardToInbox                                          The Inbox where the {@link PersistedEvent}'s will be forwarded to as an {@link OrderedMessage} containing
     *                                                                the {@link PersistedEvent#event()}'s {@link EventJSON#getJsonDeserialized()} as payload and the {@link PersistedEvent#aggregateId()} as {@link OrderedMessage#getKey()}
     *                                                                and the {@link PersistedEvent#eventOrder()} as {@link OrderedMessage#getOrder()}.<br>
     *                                                                This reuses the {@link Inbox} ability to retry event deliveries
     * @return the subscription handle
     */
    default EventStoreSubscription subscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                            AggregateType forAggregateType,
                                                                            GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                            Inbox forwardToInbox) {

        return subscribeToAggregateEventsAsynchronously(subscriberId,
                                                        forAggregateType,
                                                        onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                        Optional.empty(),
                                                        forwardToInbox);
    }


    /**
     * Create an asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param eventHandler                                            the event handler that will receive the published {@link PersistedEvent}'s<br>
     *                                                                Exceptions thrown from the eventHandler will cause the event to be skipped. If you need a retry capability
     *                                                                please use {@link #subscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, Inbox)}
     * @return the subscription handle
     */
    default EventStoreSubscription subscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                            AggregateType forAggregateType,
                                                                            GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                            PersistedEventHandler eventHandler) {
        return subscribeToAggregateEventsAsynchronously(subscriberId,
                                                        forAggregateType,
                                                        onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                        Optional.empty(),
                                                        eventHandler);
    }

    /**
     * Create an asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem
     *
     * @param subscriberId                                            The unique identifier for the subscriber.
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder A function that determines the global event order
     *                                                                to start subscribing from on the first subscription.
     * @param eventHandler                                            the event handler that will receive the published {@link PersistedEvent}'s<br>
     *                                                                Exceptions thrown from the eventHandler will cause the event to be skipped. If you need a retry capability
     *                                                                please use {@link #subscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, Inbox)}
     * @return A subscription object that manages the lifecycle of the subscription.
     */
    default EventStoreSubscription subscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                            AggregateType forAggregateType,
                                                                            Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                            PersistedEventHandler eventHandler) {
        return subscribeToAggregateEventsAsynchronously(subscriberId,
                                                        forAggregateType,
                                                        onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder.apply(forAggregateType),
                                                        Optional.empty(),
                                                        eventHandler);
    }

    /**
     * Create an exclusive asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem<br>
     * An exclusive subscription means that the {@link EventStoreSubscriptionManager} will acquire a distributed {@link FencedLock} to ensure that only one active subscriber in a cluster,
     * out of all subscribers that share the same <code>subscriberId</code>, is allowed to have an active subscribe at a time
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param onlyIncludeEventsForTenant                              if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param fencedLockAwareSubscriber                               Callback interface that will be called when the exclusive/fenced lock is acquired or released
     * @param eventHandler                                            the event handler that will receive the published {@link PersistedEvent}'s<br>
     *                                                                Exceptions thrown from the eventHandler will cause the event to be skipped. If you need a retry capability
     *                                                                please use {@link #exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, Optional, FencedLockAwareSubscriber, Inbox)}
     * @return the subscription handle
     */
    default EventStoreSubscription exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                                       AggregateType forAggregateType,
                                                                                       GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                       Optional<Tenant> onlyIncludeEventsForTenant,
                                                                                       FencedLockAwareSubscriber fencedLockAwareSubscriber,
                                                                                       PersistedEventHandler eventHandler) {
        return exclusivelySubscribeToAggregateEventsAsynchronously(
                subscriberId,
                forAggregateType,
                a -> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                onlyIncludeEventsForTenant,
                fencedLockAwareSubscriber,
                eventHandler
                                                                  );
    }

    /**
     * Create an exclusive asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem<br>
     * An exclusive subscription means that the {@link EventStoreSubscriptionManager} will acquire a distributed {@link FencedLock} to ensure that only one active subscriber in a cluster,
     * out of all subscribers that share the same <code>subscriberId</code>, is allowed to have an active subscribe at a time
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using the returned {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param onlyIncludeEventsForTenant                              if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param fencedLockAwareSubscriber                               Callback interface that will be called when the exclusive/fenced lock is acquired or released
     * @param eventHandler                                            the event handler that will receive the published {@link PersistedEvent}'s<br>
     *                                                                Exceptions thrown from the eventHandler will cause the event to be skipped. If you need a retry capability
     *                                                                please use {@link #exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, Optional, FencedLockAwareSubscriber, Inbox)}
     * @return the subscription handle
     */
    EventStoreSubscription exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                               AggregateType forAggregateType,
                                                                               Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                               Optional<Tenant> onlyIncludeEventsForTenant,
                                                                               FencedLockAwareSubscriber fencedLockAwareSubscriber,
                                                                               PersistedEventHandler eventHandler);

    /**
     * Create an exclusive asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem<br>
     * An exclusive subscription means that the {@link EventStoreSubscriptionManager} will acquire a distributed {@link FencedLock} to ensure that only one active subscriber in a cluster,
     * out of all subscribers that share the same <code>subscriberId</code>, is allowed to have an active subscribe at a time
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param fencedLockAwareSubscriber                               Callback interface that will be called when the exclusive/fenced lock is acquired or released
     * @param eventHandler                                            the event handler that will receive the published {@link PersistedEvent}'s<br>
     *                                                                Exceptions thrown from the eventHandler will cause the event to be skipped. If you need a retry capability
     *                                                                please use {@link #exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, FencedLockAwareSubscriber, Inbox)}
     * @return the subscription handle
     */
    default EventStoreSubscription exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                                       AggregateType forAggregateType,
                                                                                       GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                       FencedLockAwareSubscriber fencedLockAwareSubscriber,
                                                                                       PersistedEventHandler eventHandler) {
        return exclusivelySubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                   forAggregateType,
                                                                   onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                   Optional.empty(),
                                                                   fencedLockAwareSubscriber,
                                                                   eventHandler);
    }

    /**
     * Create an exclusive asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem<br>
     * An exclusive subscription means that the {@link EventStoreSubscriptionManager} will acquire a distributed {@link FencedLock} to ensure that only one active subscriber in a cluster,
     * out of all subscribers that share the same <code>subscriberId</code>, is allowed to have an active subscribe at a time
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param onlyIncludeEventsForTenant                              if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param fencedLockAwareSubscriber                               Callback interface that will be called when the exclusive/fenced lock is acquired or released
     * @param forwardToInbox                                          The Inbox where the {@link PersistedEvent}'s will be forwarded to as an {@link OrderedMessage} containing
     *                                                                the {@link PersistedEvent#event()}'s {@link EventJSON#getJsonDeserialized()} as payload and the {@link PersistedEvent#aggregateId()} as {@link OrderedMessage#getKey()}
     *                                                                and the {@link PersistedEvent#eventOrder()} as {@link OrderedMessage#getOrder()}.<br>
     *                                                                This reuses the {@link Inbox} ability to retry event deliveries
     * @return the subscription handle
     */
    default EventStoreSubscription exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                                       AggregateType forAggregateType,
                                                                                       GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                       Optional<Tenant> onlyIncludeEventsForTenant,
                                                                                       FencedLockAwareSubscriber fencedLockAwareSubscriber,
                                                                                       Inbox forwardToInbox) {
        requireNonNull(forwardToInbox, "No forwardToInbox instance provided");
        return exclusivelySubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                   forAggregateType,
                                                                   onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                   onlyIncludeEventsForTenant,
                                                                   fencedLockAwareSubscriber,
                                                                   event -> forwardToInbox.addMessageReceived(OrderedMessage.of(event.event().getJsonDeserialized().get(),
                                                                                                                                event.aggregateId().toString(),
                                                                                                                                event.eventOrder().longValue())));
    }

    /**
     * Create an exclusive asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem<br>
     * An exclusive subscription means that the {@link EventStoreSubscriptionManager} will acquire a distributed {@link FencedLock} to ensure that only one active subscriber in a cluster,
     * out of all subscribers that share the same <code>subscriberId</code>, is allowed to have an active subscribe at a time
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param fencedLockAwareSubscriber                               Callback interface that will be called when the exclusive/fenced lock is acquired or released
     * @param forwardToInbox                                          The Inbox where the {@link PersistedEvent}'s will be forwarded to as an {@link OrderedMessage} containing
     *                                                                the {@link PersistedEvent#event()}'s {@link EventJSON#getJsonDeserialized()} as payload and the {@link PersistedEvent#aggregateId()} as {@link OrderedMessage#getKey()}
     *                                                                and the {@link PersistedEvent#eventOrder()} as {@link OrderedMessage#getOrder()}.<br>
     *                                                                This reuses the {@link Inbox} ability to retry event deliveries
     * @return the subscription handle
     */
    default EventStoreSubscription exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                                       AggregateType forAggregateType,
                                                                                       GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                       FencedLockAwareSubscriber fencedLockAwareSubscriber,
                                                                                       Inbox forwardToInbox) {
        requireNonNull(forwardToInbox, "No forwardToInbox instance provided");
        return exclusivelySubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                   forAggregateType,
                                                                   onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                   Optional.empty(),
                                                                   fencedLockAwareSubscriber,
                                                                   forwardToInbox);
    }

    /**
     * Create an exclusive asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem<br>
     * An exclusive subscription means that the {@link EventStoreSubscriptionManager} will acquire a distributed {@link FencedLock} to ensure that only one active subscriber in a cluster,
     * out of all subscribers that share the same <code>subscriberId</code>, is allowed to have an active subscribe at a time
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param handler                                                 the event handler that will receive the published {@link PersistedEvent}'s and the callback interface will be called when the exclusive/fenced lock is acquired or released<br>
     *                                                                Exceptions thrown from the eventHandler will cause the event to be skipped. If you need a retry capability
     *                                                                please use {@link #exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, FencedLockAwareSubscriber, Inbox)}
     * @return the subscription handle
     */
    default <HANDLER extends PersistedEventHandler & FencedLockAwareSubscriber> EventStoreSubscription exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                                                                                                           AggregateType forAggregateType,
                                                                                                                                                           GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                                                                                           HANDLER handler) {
        return exclusivelySubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                   forAggregateType,
                                                                   onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                   Optional.empty(),
                                                                   handler);
    }

    /**
     * Create an exclusive asynchronous subscription that will receive {@link PersistedEvent} after they have been committed to the {@link EventStore}<br>
     * This ensures that the handling of events can occur in a separate transaction, than the one that persisted the events, thereby avoiding the dual write problem<br>
     * An exclusive subscription means that the {@link EventStoreSubscriptionManager} will acquire a distributed {@link FencedLock} to ensure that only one active subscriber in a cluster,
     * out of all subscribers that share the same <code>subscriberId</code>, is allowed to have an active subscribe at a time
     *
     * @param subscriberId                                            the unique id for the subscriber
     * @param forAggregateType                                        the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder If it's the first time the given <code>subscriberId</code> is subscribing then the subscription will be using this {@link GlobalEventOrder} as the starting point in the
     *                                                                EventStream associated with the <code>aggregateType</code>
     * @param onlyIncludeEventsForTenant                              if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param handler                                                 the event handler that will receive the published {@link PersistedEvent}'s and the callback interface will be called when the exclusive/fenced lock is acquired or released<br>
     *                                                                Exceptions thrown from the eventHandler will cause the event to be skipped. If you need a retry capability
     *                                                                please use {@link #exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, Optional, FencedLockAwareSubscriber, Inbox)}
     * @return the subscription handle
     */
    default <HANDLER extends PersistedEventHandler & FencedLockAwareSubscriber> EventStoreSubscription exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId subscriberId,
                                                                                                                                                           AggregateType forAggregateType,
                                                                                                                                                           GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                                                                                                           Optional<Tenant> onlyIncludeEventsForTenant,
                                                                                                                                                           HANDLER handler) {
        return exclusivelySubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                   forAggregateType,
                                                                   onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                   onlyIncludeEventsForTenant,
                                                                   handler,
                                                                   handler);
    }

    /**
     * Create an inline event subscription, that will receive {@link PersistedEvent}'s right after they're appended to the {@link EventStore} but before the associated
     * {@link UnitOfWork} is committed. This allows you to create transactional consistent event projections.
     *
     * @param subscriberId     the unique id for the subscriber
     * @param forAggregateType the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param eventHandler     the event handler that will receive the published {@link PersistedEvent}'s. Exceptions thrown by this handler will cause the {@link UnitOfWork} to rollback
     * @return the subscription handle
     */
    default EventStoreSubscription subscribeToAggregateEventsInTransaction(SubscriberId subscriberId,
                                                                           AggregateType forAggregateType,
                                                                           TransactionalPersistedEventHandler eventHandler) {
        return subscribeToAggregateEventsInTransaction(subscriberId,
                                                       forAggregateType,
                                                       Optional.empty(),
                                                       eventHandler);
    }


    /**
     * Create an exclusive in-transaction event subscription, that will receive {@link PersistedEvent}'s right after they're appended to the {@link EventStore} but before the associated
     * {@link UnitOfWork} is committed. This allows you to create transactional consistent event projections.
     *
     * @param subscriberId               the unique id for the subscriber
     * @param forAggregateType           the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onlyIncludeEventsForTenant if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param eventHandler               the event handler that will receive the published {@link PersistedEvent}'s. Exceptions thrown by this handler will cause the {@link UnitOfWork} to rollback
     * @return the subscription handle
     */
    EventStoreSubscription exclusivelySubscribeToAggregateEventsInTransaction(SubscriberId subscriberId,
                                                                              AggregateType forAggregateType,
                                                                              Optional<Tenant> onlyIncludeEventsForTenant,
                                                                              TransactionalPersistedEventHandler eventHandler);

    /**
     * Create an in-transaction event subscription, that will receive {@link PersistedEvent}'s right after they're appended to the {@link EventStore} but before the associated
     * {@link UnitOfWork} is committed. This allows you to create transactional consistent event projections.
     *
     * @param subscriberId               the unique id for the subscriber
     * @param forAggregateType           the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onlyIncludeEventsForTenant if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param eventHandler               the event handler that will receive the published {@link PersistedEvent}'s. Exceptions thrown by this handler will cause the {@link UnitOfWork} to rollback
     * @return the subscription handle
     */
    EventStoreSubscription subscribeToAggregateEventsInTransaction(SubscriberId subscriberId,
                                                                   AggregateType forAggregateType,
                                                                   Optional<Tenant> onlyIncludeEventsForTenant,
                                                                   TransactionalPersistedEventHandler eventHandler);

    /**
     * Create an in-transaction event subscription, that will receive {@link PersistedEvent}'s right after they're appended to the {@link EventStore} but before the associated
     * {@link UnitOfWork} is committed. This allows you to create transactional consistent event projections.
     *
     * @param subscriberId               the unique id for the subscriber
     * @param forAggregateType           the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param onlyIncludeEventsForTenant if {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     * @param forwardToInbox             The Inbox where the {@link PersistedEvent}'s will be forwarded to as an {@link OrderedMessage} containing
     *                                   the {@link PersistedEvent#event()}'s {@link EventJSON#getJsonDeserialized()} as payload and the {@link PersistedEvent#aggregateId()} as {@link OrderedMessage#getKey()}
     *                                   and the {@link PersistedEvent#eventOrder()} as {@link OrderedMessage#getOrder()}.<br>
     *                                   This reuses the {@link Inbox} ability to retry event deliveries
     * @return the subscription handle
     */
    default EventStoreSubscription subscribeToAggregateEventsInTransaction(SubscriberId subscriberId,
                                                                           AggregateType forAggregateType,
                                                                           Optional<Tenant> onlyIncludeEventsForTenant,
                                                                           Inbox forwardToInbox) {
        requireNonNull(forwardToInbox, "No forwardToInbox instance provided");
        return subscribeToAggregateEventsInTransaction(subscriberId,
                                                       forAggregateType,
                                                       onlyIncludeEventsForTenant,
                                                       (event, unitOfWork) -> forwardToInbox.addMessageReceived(OrderedMessage.of(event.event().getJsonDeserialized().get(),
                                                                                                                                  event.aggregateId().toString(),
                                                                                                                                  event.eventOrder().longValue())));

    }

    /**
     * Create an in-transaction event subscription, that will receive {@link PersistedEvent}'s right after they're appended to the {@link EventStore} but before the associated
     * {@link UnitOfWork} is committed. This allows you to create transactional consistent event projections.
     *
     * @param subscriberId     the unique id for the subscriber
     * @param forAggregateType the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     * @param forwardToInbox   The Inbox where the {@link PersistedEvent}'s will be forwarded to as an {@link OrderedMessage} containing
     *                         the {@link PersistedEvent#event()}'s {@link EventJSON#getJsonDeserialized()} as payload and the {@link PersistedEvent#aggregateId()} as {@link OrderedMessage#getKey()}
     *                         and the {@link PersistedEvent#eventOrder()} as {@link OrderedMessage#getOrder()}.<br>
     *                         This reuses the {@link Inbox} ability to retry event deliveries
     * @return the subscription handle
     */
    default EventStoreSubscription subscribeToAggregateEventsInTransaction(SubscriberId subscriberId,
                                                                           AggregateType forAggregateType,
                                                                           Inbox forwardToInbox) {
        requireNonNull(forwardToInbox, "No forwardToInbox instance provided");
        return subscribeToAggregateEventsInTransaction(subscriberId,
                                                       forAggregateType,
                                                       Optional.empty(),
                                                       forwardToInbox);

    }

    /**
     * Create a new {@link EventStoreSubscriptionManager} that can manage event subscriptions against the provided <code>eventStore</code>
     * Use {@link #builder()} instead
     *
     * @param eventStore                    the event store that the created {@link EventStoreSubscriptionManager} can manage event subscriptions against
     * @param eventStorePollingBatchSize    how many events should The {@link EventStore} maximum return when polling for events
     * @param eventStorePollingInterval     how often should the {@link EventStore} be polled for new events
     * @param fencedLockManager             the {@link FencedLockManager} that will be used to acquire a {@link FencedLock} for exclusive asynchronous subscriptions
     * @param snapshotResumePointsEvery     How often should active (for exclusive subscribers this means subscribers that have acquired a distributed lock) subscribers have their {@link SubscriptionResumePoint} saved
     * @param durableSubscriptionRepository The repository responsible for persisting {@link SubscriptionResumePoint}
     * @return the newly create {@link EventStoreSubscriptionManager}
     * @see PostgresqlFencedLockManager
     * @see PostgresqlDurableSubscriptionRepository
     * @see DefaultEventStoreSubscriptionManager
     */
    static EventStoreSubscriptionManager createFor(EventStore eventStore,
                                                   int eventStorePollingBatchSize,
                                                   Duration eventStorePollingInterval,
                                                   FencedLockManager fencedLockManager,
                                                   Duration snapshotResumePointsEvery,
                                                   DurableSubscriptionRepository durableSubscriptionRepository) {
        return builder().setEventStore(eventStore)
                        .setEventStorePollingBatchSize(eventStorePollingBatchSize)
                        .setEventStorePollingInterval(eventStorePollingInterval)
                        .setFencedLockManager(fencedLockManager)
                        .setSnapshotResumePointsEvery(snapshotResumePointsEvery)
                        .setDurableSubscriptionRepository(durableSubscriptionRepository)
                        .build();
    }

    void unsubscribe(EventStoreSubscription eventStoreSubscription);

    default boolean hasSubscription(EventStoreSubscription subscription) {
        requireNonNull(subscription, "No subscription provided");
        return hasSubscription(subscription.subscriberId(), subscription.aggregateType());
    }

    boolean hasSubscription(SubscriberId subscriberId, AggregateType aggregateType);

    Set<Pair<SubscriberId, AggregateType>> getActiveSubscriptions();

    /**
     * @return current event order for the given subscriber only of the subscriber has a registered resume point
     */
    Optional<GlobalEventOrder> getCurrentEventOrder(SubscriberId subscriberId, AggregateType aggregateType);

}
