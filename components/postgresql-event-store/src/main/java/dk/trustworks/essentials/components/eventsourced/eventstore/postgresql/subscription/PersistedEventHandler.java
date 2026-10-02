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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link PersistedEvent} Event handler interface for use with the {@link EventStoreSubscriptionManager}'s:
 * <ul>
 *     <li>{@link EventStoreSubscriptionManager#exclusivelySubscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, Optional, FencedLockAwareSubscriber, PersistedEventHandler)} </li>
 *     <li>{@link EventStoreSubscriptionManager#subscribeToAggregateEventsAsynchronously(SubscriberId, AggregateType, GlobalEventOrder, Optional, PersistedEventHandler)}</li>
 * </ul>
 *
 * @see PatternMatchingPersistedEventHandler
 */
public interface PersistedEventHandler {
    /**
     * This method will be called if {@link EventStoreSubscription#resetFrom(GlobalEventOrder, Consumer)} is called
     *
     * @param eventStoreSubscription           the {@link EventStoreSubscription} where {@link EventStoreSubscription#resetFrom(GlobalEventOrder, Consumer)} was called (useful if a {@link PersistedEventHandler} listens to multiple streams)
     * @param resetFromAndIncludingGlobalOrder the value provided to {@link EventStoreSubscription#resetFrom(GlobalEventOrder, Consumer)}. This {@link GlobalEventOrder} will become the new starting point in the
     *                                         EventStream associated with the {@link EventStoreSubscription#aggregateType()}
     */
    default void onResetFrom(EventStoreSubscription eventStoreSubscription, GlobalEventOrder resetFromAndIncludingGlobalOrder) {
    }

    default int handleWithBackPressure(PersistedEvent event) {
        handle(event);
        return 1;
    }

    /**
     * This method will be called in a {@link UnitOfWork} when ever a {@link PersistedEvent} is published
     *
     * @param event the event published
     */
    void handle(PersistedEvent event);

    /**
     * Offer a failed event back to the handler instead of letting the {@link SubscriptionErrorPolicy} give up on it.
     * <p>
     * Called by the asynchronous subscriptions ({@link PersistedEventSubscriber}) on the delivery thread when handling
     * <code>event</code> failed and the {@link SubscriptionErrorPolicy} has run out of options: after its retries, in place of
     * skipping the event ({@link SubscriptionErrorPolicy.Mode#SKIP}, {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP}) or
     * stopping at it ({@link SubscriptionErrorPolicy.Mode#STOP}). The {@link UnitOfWork} the event was handled in has been
     * rolled back by then and none is active, so a handler that takes the event over - e.g. by queueing it for redelivery
     * - must do so in a {@link UnitOfWork} of its own.
     * <p>
     * Not called for a failure the subscriber's I/O retry handles, nor when the subscription was stopped while the event
     * was being handled (the restarted subscription handles the event again).
     *
     * @param event   the event whose handling failed
     * @param failure the failure the {@link SubscriptionErrorPolicy} gave up on
     * @return true if the handler took the event over: the subscriber then carries on as if the event had been handled -
     * the policy does not skip or stop, the failure is not reported to the observer, and the resume point moves past the
     * event. false (the default) to let the {@link SubscriptionErrorPolicy} give up as usual, which it also does if this
     * method throws
     */
    default boolean handOffFailedEvent(PersistedEvent event, Throwable failure) {
        return false;
    }
}