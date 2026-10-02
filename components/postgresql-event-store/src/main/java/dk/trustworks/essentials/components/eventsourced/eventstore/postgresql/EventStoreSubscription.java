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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionResumePoint;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import org.reactivestreams.Subscription;

import java.util.Optional;
import java.util.function.Consumer;

public interface EventStoreSubscription extends Lifecycle, Subscription {
    /**
     * The unique id for the subscriber
     *
     * @return the unique id for the subscriber
     */
    SubscriberId subscriberId();

    /**
     * The type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     *
     * @return the type of aggregate that we're subscribing for {@link PersistedEvent}'s related to
     */
    AggregateType aggregateType();

    /**
     * Unsubscribe from the {@link EventStore}
     */
    void unsubscribe();

    /**
     * Unsubscribe - same as calling {@link #unsubscribe()}
     */
    @Override
    default void cancel() {
        unsubscribe();
    }

    /**
     * Is this subscription exclusive, i.e. governed by a {@link FencedLock}
     * @return if this subscription is exclusive, i.e. governed by a {@link FencedLock}
     */
    boolean isExclusive();

    /**
     * Is this a subscription that where event handling joins in on the {@link UnitOfWork}
     * that event was persisted in
     * @return if this subscription's event handling joins in on the {@link UnitOfWork} that event was persisted in
     */
    boolean isInTransaction();

    /**
     * Is the subscription asynchronous (i.e. NOT {@link #isInTransaction()})<br>
     * Default implementation returns <code>!isInTransaction()</code>
     * @return Is the subscription asynchronous (i.e. NOT {@link #isInTransaction()})
     */
    default boolean isAsynchronous() {
        return !isInTransaction();
    }

    /**
     * Reset the subscription point.<br>
     *
     * @param subscribeFromAndIncludingGlobalOrder this {@link GlobalEventOrder} will become the new starting point in the
     *                                             EventStream associated with the {@link #aggregateType()}
     * @param resetProcessor                       hook to add custom handling to perform when the subscriber is stopped during the reset process
     */
    void resetFrom(GlobalEventOrder subscribeFromAndIncludingGlobalOrder, Consumer<GlobalEventOrder> resetProcessor);

    /**
     * Get the subscriptions resume point (if supported by the subscription)
     *
     * @return the subscriptions resume point
     */
    Optional<SubscriptionResumePoint> currentResumePoint();

    /**
     * If {@link Optional#isPresent()} then only include events that belong to the specified {@link Tenant}, otherwise all Events matching the criteria are returned
     */
    Optional<Tenant> onlyIncludeEventsForTenant();

    /**
     * Is the Subscription active?
     * <ul>
     * <li>For an Exclusive Subscription, {@link #isActive()} reflects whether the subscriber has acquired the underlying {@link FencedLock}</li>
     * <li>For a Non-Exclusive subscription, {@link #isActive()} reflects whether the subscriber {@link Lifecycle#isStarted()}</li>
     * </ul>
     * A subscription that {@link #isStoppedByErrorPolicy()} stays active: see there for why
     */
    boolean isActive();

    /**
     * Has the {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy}
     * stopped this subscription? True once an event's handler failed under
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy.Mode#STOP}:
     * the subscription handles no further events and its resume point stays at the failed event until the subscription
     * is started again (application restart, fenced-lock hand-over, {@link #resetFrom(GlobalEventOrder, Consumer)}, or
     * unsubscribe + subscribe), which resets it to false.
     * <p>
     * This is the state to alert on - a stopped subscription is otherwise indistinguishable from a healthy one with no
     * new events. It is exported as the level-triggered gauge
     * {@value dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.SubscriptionStoppedMicrometerMonitor#SUBSCRIPTION_STOPPED_METRIC}
     * ({@code 1} while stopped) by
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.SubscriptionStoppedMicrometerMonitor};
     * alert on that gauge, not on the
     * {@value dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver#SUBSCRIPTION_STOPPED_BY_ERROR_POLICY_METRIC}
     * counter, which only records that a stop happened.
     * {@link #isActive()} deliberately stays unchanged by the stop: it answers "is the subscription running
     * in this instance" (for an exclusive subscription "does it hold the fenced lock"), and a stopped subscription still
     * is - it keeps its lock on purpose, so the event does not flap to another node that would fail the same way, and
     * the subscription manager's periodic checkpoint saves the resume points of active subscriptions only - it is what
     * persists the resume point the stop held at the failed event if the process later dies without a graceful stop.
     * <p>
     * The default returns false, for subscriptions that are not governed by a
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy}
     * (the in-transaction subscriptions).
     *
     * @return true if the {@code SubscriptionErrorPolicy} stopped this subscription and it has not been started again since
     */
    default boolean isStoppedByErrorPolicy() {
        return false;
    }
}
