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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionResumePoint;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import java.time.OffsetDateTime;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a subscription to an aggregate type for a specific subscriber.
 * <p>
 * The subscription is described from two independent sources, and they have different scopes:
 * <ul>
 *     <li>{@link #currentGlobalOrder()} and {@link #lastUpdated()} come from the durable resume point in the database,
 *     which is shared by every instance of the application.</li>
 *     <li>{@link #runningInThisInstance()} and everything it gates come from the subscription object living in the
 *     queried instance. They are null when the subscription runs on another instance.</li>
 * </ul>
 * A durable resume point is only kept for asynchronous subscriptions, and only written periodically, so
 * {@link #inMemoryGlobalOrder()} can be ahead of {@link #currentGlobalOrder()} and an in-transaction subscription has
 * no durable resume point at all - see {@link #durableResumePointPresent()}.
 *
 * @param subscriberId              Unique identifier for the subscriber.
 * @param aggregateType             The type of aggregate being subscribed to.
 * @param currentGlobalOrder        The global order position of the durable resume point, or 0 if no durable resume
 *                                  point exists. See {@link #durableResumePointPresent()}.
 * @param lastUpdated               When the durable resume point was last updated. Null if no durable resume point exists.
 * @param durableResumePointPresent Whether a durable resume point exists for this subscription in the database.
 * @param runningInThisInstance     Whether the subscription is registered with the subscription manager of the queried
 *                                  instance. When false, every property below is null.
 * @param active                    Whether the subscription is currently active - for an exclusive subscription this
 *                                  means it holds its fenced lock. Null when the subscription does not run in this instance.
 * @param exclusive                 Whether only one instance at a time may run this subscription. Null when the
 *                                  subscription does not run in this instance.
 * @param inTransaction             Whether events are handled in the same transaction that persisted them. Null when
 *                                  the subscription does not run in this instance.
 * @param tenant                    The tenant the subscription is restricted to, if any. Null when the subscription is
 *                                  not tenant-restricted or does not run in this instance.
 * @param inMemoryGlobalOrder       The in-memory resume point of the running subscription, which can be ahead of
 *                                  {@link #currentGlobalOrder()}. Null when the subscription does not run in this
 *                                  instance or has no resume point.
 */
public record ApiSubscription(
        SubscriberId subscriberId,
        AggregateType aggregateType,
        long currentGlobalOrder,
        OffsetDateTime lastUpdated,
        boolean durableResumePointPresent,
        boolean runningInThisInstance,
        Boolean active,
        Boolean exclusive,
        Boolean inTransaction,
        String tenant,
        Long inMemoryGlobalOrder
) {

    /**
     * Describe a subscription known only from its durable resume point, i.e. without any knowledge of whether it runs
     * in this instance.<br>
     * {@link DefaultEventStoreApi} adds the live state of the subscriptions running in the queried instance - this DTO
     * deliberately does not reference the runtime subscription types, so that it stays loadable by consumers that only
     * have the API types on their classpath.
     *
     * @param subscriptionResumePoint the durable resume point
     * @return the subscription
     */
    public static ApiSubscription from(SubscriptionResumePoint subscriptionResumePoint) {
        requireNonNull(subscriptionResumePoint, "No subscriptionResumePoint provided");
        return new ApiSubscription(
                subscriptionResumePoint.getSubscriberId(),
                subscriptionResumePoint.getAggregateType(),
                subscriptionResumePoint.getResumeFromAndIncluding().longValue(),
                subscriptionResumePoint.getLastUpdated(),
                true,
                false,
                null,
                null,
                null,
                null,
                null);
    }

    @Override
    public String toString() {
        return "ApiSubscription{" +
                "subscriberId=" + subscriberId +
                ", aggregateType=" + aggregateType +
                ", currentGlobalOrder=" + currentGlobalOrder +
                ", lastUpdated=" + lastUpdated +
                ", durableResumePointPresent=" + durableResumePointPresent +
                ", runningInThisInstance=" + runningInThisInstance +
                ", active=" + active +
                ", exclusive=" + exclusive +
                ", inTransaction=" + inTransaction +
                ", tenant=" + tenant +
                ", inMemoryGlobalOrder=" + inMemoryGlobalOrder +
                '}';
    }
}
