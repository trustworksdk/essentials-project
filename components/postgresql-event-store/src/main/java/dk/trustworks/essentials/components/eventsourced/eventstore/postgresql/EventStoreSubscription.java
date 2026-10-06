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
     * stopped this subscription? True once a stopping policy
     * ({@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy.Mode#STOP},
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy.Mode#RETRY_N_THEN_STOP})
     * gave up on an event: the subscription handles no further events and its resume point stays at the failed event until
     * the subscription is resumed - by itself after a delay (the policy's
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy#autoResume()},
     * on by default), or by {@link #resumeIfStoppedByErrorPolicy()} - or started again (application restart, fenced-lock
     * hand-over, {@link #resetFrom(GlobalEventOrder, Consumer)}, or unsubscribe + subscribe), which resets it to false. An
     * event that keeps failing makes it true again after every resume.
     * <p>
     * This is the state to alert on - a stopped subscription is otherwise indistinguishable from a healthy one with no
     * new events, and a subscription that stays stopped (or keeps stopping) across its automatic resumes is stuck on an
     * event that needs a fix. It is exported as the level-triggered gauge
     * {@value dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.SubscriptionStoppedMicrometerMonitor#SUBSCRIPTION_STOPPED_METRIC}
     * ({@code 1} while stopped, and through every resume until the failed event is handled - see
     * {@link #isRecoveringFromErrorPolicyStop()}) by
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

    /**
     * Has this subscription been resumed after its
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy}
     * stopped it ({@link #isStoppedByErrorPolicy()}), but not yet got past the event it stopped at? True from the resume -
     * automatic or by {@link #resumeIfStoppedByErrorPolicy()} - until the subscription is done with that event or a later
     * one (handled, handed off to its event handler, or skipped), and false again once the subscription is stopped,
     * unsubscribed, loses its fenced lock or is {@link #resetFrom(GlobalEventOrder, Consumer) reset}. Never true at the
     * same time as {@link #isStoppedByErrorPolicy()}.
     * <p>
     * A resumed subscription is retrying the failed event, which is not the same as having recovered: an event that keeps
     * failing stops the subscription again moments later. That is why the
     * {@value dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.SubscriptionStoppedMicrometerMonitor#SUBSCRIPTION_STOPPED_METRIC}
     * gauge reports {@code 1} while either this or {@link #isStoppedByErrorPolicy()} is true: it stays at {@code 1}
     * through the automatic resumes of a poison event, so an alert with a {@code for:} duration is not reset by every resume.
     * <p>
     * The default returns false, for subscriptions that are not governed by a
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy}
     * (the in-transaction subscriptions).
     *
     * @return true if the subscription was resumed after a stop by its error policy and has not got past the failed event yet
     */
    default boolean isRecoveringFromErrorPolicyStop() {
        return false;
    }

    /**
     * Is this subscription either stopped by its
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy}
     * ({@link #isStoppedByErrorPolicy()}) or resumed but not yet past the event it stopped at
     * ({@link #isRecoveringFromErrorPolicyStop()})? This is the value of the
     * {@value dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.SubscriptionStoppedMicrometerMonitor#SUBSCRIPTION_STOPPED_METRIC}
     * gauge.
     * <p>
     * Use this rather than combining the two yourself while the subscription is running: a resumed subscription that
     * fails at the event again turns {@link #isStoppedByErrorPolicy()} true and {@link #isRecoveringFromErrorPolicyStop()}
     * false at the same moment, so {@code isStoppedByErrorPolicy() || isRecoveringFromErrorPolicyStop()} can read the
     * first before that moment and the second after it, and answer false for a subscription that is stuck. The
     * subscriptions governed by a {@code SubscriptionErrorPolicy} answer this from a single read of their state.
     * <p>
     * The default combines the two, for subscriptions whose state does not change underneath the call.
     *
     * @return true while the subscription is stopped by its error policy, or resumed and not yet past the failed event
     */
    default boolean isStoppedOrRecoveringFromErrorPolicyStop() {
        return isStoppedByErrorPolicy() || isRecoveringFromErrorPolicyStop();
    }

    /**
     * Resume a subscription that its
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy}
     * stopped ({@link #isStoppedByErrorPolicy()}), without restarting the application: typically once the cause of the
     * failure has been fixed. The subscription calls this itself after a delay when the policy resumes automatically
     * ({@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy#autoResume()});
     * calling it by hand resumes at once and cancels the pending automatic resume. Delivery restarts at the subscription's resume point, which the stop held at the failed
     * event (the first event of the failed batch), so the failed event is handled again first, and nothing after it is
     * skipped. If it fails again, the policy applies again - and may stop the subscription at the same event again.
     * <p>
     * The resume point is saved before delivery restarts, and an exclusive subscription keeps its fenced lock throughout -
     * the lock is neither released nor handed over. Works the same whether the events are delivered by polling or by CDC.
     * <p>
     * Only the instance that runs the stopped subscription can resume it - for an exclusive subscription the instance
     * holding its fenced lock. Anywhere else, and for a subscription that is not stopped, this is a no-op that returns
     * false.
     * <p>
     * The default returns false, for subscriptions that are not governed by a
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy}
     * (the in-transaction subscriptions).
     *
     * @return true if the subscription was stopped by its error policy and has been resumed; false if it was not stopped
     * by its error policy (or is not running in this instance), in which case nothing was done
     */
    default boolean resumeIfStoppedByErrorPolicy() {
        return false;
    }
}
