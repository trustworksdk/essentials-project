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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.SubscriptionStatistics;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Runtime statistics for a single event-store subscription.
 * <p>
 * <b>The statistics describe the queried instance only.</b> Subscription resume points come from the database and are
 * therefore shared by every instance of the application, but these counters are collected in memory by the instance
 * that runs the subscription. Two consequences:
 * <ul>
 *     <li>A subscription that runs on another instance has no statistics here at all.</li>
 *     <li>An exclusive subscription only handles events on the instance that currently holds its fenced lock, so zero
 *     throughput on the other instances is expected - check {@link #lock()} rather than reading it as a stall.</li>
 * </ul>
 * Counters are cumulative since {@link #statisticsSince()}, which is when the subscription was first observed in this
 * instance. Resetting a subscription's resume point does not clear them; it is reported in {@link #reset()} instead.
 *
 * @param subscriberId    the subscriber the statistics were collected for
 * @param aggregateType   the aggregate type the subscriber subscribes to
 * @param statisticsSince when the collection of these statistics started in this instance
 * @param lifecycle       start/stop statistics
 * @param eventHandling   event handling throughput, timing and failure statistics
 * @param polling         event-store polling statistics - zero for a subscription served over Change Data Capture
 * @param lock            fenced-lock statistics, only relevant for exclusive subscriptions
 * @param reset           resume-point reset (replay) statistics
 */
public record ApiSubscriptionStatistics(
        SubscriberId subscriberId,
        AggregateType aggregateType,
        OffsetDateTime statisticsSince,
        ApiSubscriptionLifecycleStatistics lifecycle,
        ApiSubscriptionEventHandlingStatistics eventHandling,
        ApiSubscriptionPollingStatistics polling,
        ApiSubscriptionLockStatistics lock,
        ApiSubscriptionResetStatistics reset
) {

    public static ApiSubscriptionStatistics from(SubscriptionStatistics statistics) {
        requireNonNull(statistics, "No statistics provided");
        return new ApiSubscriptionStatistics(
                statistics.subscriberId(),
                statistics.aggregateType(),
                toOffsetDateTime(statistics.statisticsSince()),
                ApiSubscriptionLifecycleStatistics.from(statistics.lifecycle()),
                ApiSubscriptionEventHandlingStatistics.from(statistics.eventHandling()),
                ApiSubscriptionPollingStatistics.from(statistics.polling()),
                ApiSubscriptionLockStatistics.from(statistics.lock()),
                ApiSubscriptionResetStatistics.from(statistics.reset()));
    }

    /**
     * @param instant the instant to convert, may be null
     * @return the instant at UTC, or null if no instant was given
     */
    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    /**
     * @param duration the duration to convert, may be null
     * @return the duration in milliseconds, or null if no duration was given
     */
    static Long toMillis(Duration duration) {
        return duration != null ? duration.toMillis() : null;
    }

    @Override
    public String toString() {
        return "ApiSubscriptionStatistics{" +
                "subscriberId=" + subscriberId +
                ", aggregateType=" + aggregateType +
                ", statisticsSince=" + statisticsSince +
                ", lifecycle=" + lifecycle +
                ", eventHandling=" + eventHandling +
                ", polling=" + polling +
                ", lock=" + lock +
                ", reset=" + reset +
                '}';
    }
}
