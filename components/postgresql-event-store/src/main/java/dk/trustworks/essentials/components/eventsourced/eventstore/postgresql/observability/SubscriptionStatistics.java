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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An immutable point-in-time snapshot of the runtime statistics collected for a single
 * {@link EventStoreSubscription}, as recorded by {@link StatisticsCollectingEventStoreSubscriptionObserver}
 * and held by a {@link SubscriptionStatisticsRegistry}.
 * <p>
 * <b>The statistics are local to the JVM that collected them.</b> A subscription that runs on another instance of the
 * same application is invisible here, and an exclusive subscription only produces event-handling statistics on the
 * instance that currently holds the {@link dk.trustworks.essentials.components.foundation.fencedlock.FencedLock}.
 * Absence of throughput on one instance therefore does not mean the subscription is stalled - see
 * {@link #lock()} and the subscription's own {@code isActive()}.
 * <p>
 * Counters are cumulative since {@link #statisticsSince()}, which is the instant the subscription was first observed
 * in this JVM. {@link EventStoreSubscription#resetFrom(GlobalEventOrder, java.util.function.Consumer)} does
 * <b>not</b> reset them - it is recorded in {@link #reset()} instead, so that a replay is visible rather than hidden.
 *
 * @param subscriberId    the subscriber the statistics were collected for
 * @param aggregateType   the aggregate type the subscriber subscribes to
 * @param statisticsSince when the collection of these statistics started in this JVM
 * @param lifecycle       start/stop statistics
 * @param eventHandling   event handling throughput, timing and failure statistics
 * @param polling         event-store polling statistics. Only the polling path updates these - a subscription served
 *                        by CDC leaves them at zero
 * @param lock            {@link dk.trustworks.essentials.components.foundation.fencedlock.FencedLock} statistics,
 *                        only relevant for exclusive subscriptions
 * @param reset           resume-point reset (replay) statistics
 */
public record SubscriptionStatistics(
        SubscriberId subscriberId,
        AggregateType aggregateType,
        Instant statisticsSince,
        Lifecycle lifecycle,
        EventHandling eventHandling,
        Polling polling,
        Lock lock,
        Reset reset
) {
    public SubscriptionStatistics {
        requireNonNull(subscriberId, "No subscriberId provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(statisticsSince, "No statisticsSince provided");
        requireNonNull(lifecycle, "No lifecycle provided");
        requireNonNull(eventHandling, "No eventHandling provided");
        requireNonNull(polling, "No polling provided");
        requireNonNull(lock, "No lock provided");
        requireNonNull(reset, "No reset provided");
    }

    /**
     * Start/stop statistics for a subscription.
     *
     * @param starts        how many times the subscription has been started
     * @param stops         how many times the subscription has been stopped
     * @param lastStartedAt when the subscription was last started, or {@code null} if it never started in this JVM
     * @param lastStoppedAt when the subscription was last stopped, or {@code null} if it never stopped in this JVM
     */
    public record Lifecycle(
            long starts,
            long stops,
            Instant lastStartedAt,
            Instant lastStoppedAt
    ) {
    }

    /**
     * Event handling throughput, timing and failure statistics.
     *
     * @param eventsPublishedToSubscriber   how many events the {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore}
     *                                      published to the subscriber's {@code Flux} sink
     * @param eventsHandled                 how many events the subscriber's event handler completed
     * @param failures                      how many events the subscriber's event handler failed to handle
     * @param lastEventHandledAt            when an event was last handled, or {@code null} if none was
     * @param lastEventHandledGlobalOrder   the {@link GlobalEventOrder} of the last event handled, or {@code null} if none was
     * @param averageEventHandlingTime      mean event-handler duration across {@link #eventsHandled}, or {@code null} if no event was handled
     * @param maxEventHandlingTime          slowest observed event-handler duration, or {@code null} if no event was handled
     * @param lastFailureAt                 when handling last failed, or {@code null} if it never did
     * @param lastFailureReason             exception type and message of the last handling failure, or {@code null} if it never failed.
     *                                      Only the rendered text is retained - never the {@link Throwable} itself
     * @param lastNumberOfEventsRequested   the most recent number of events requested from the subscription {@code Flux} (reactive demand)
     */
    public record EventHandling(
            long eventsPublishedToSubscriber,
            long eventsHandled,
            long failures,
            Instant lastEventHandledAt,
            GlobalEventOrder lastEventHandledGlobalOrder,
            Duration averageEventHandlingTime,
            Duration maxEventHandlingTime,
            Instant lastFailureAt,
            String lastFailureReason,
            long lastNumberOfEventsRequested
    ) {
    }

    /**
     * Event-store polling statistics. Only the polling path updates these, so a subscription that is served over CDC
     * leaves them at zero - that is expected and not a sign of a stalled subscription.
     *
     * @param polls                                how many times the event store was queried for this subscriber
     * @param pollsWithoutEvents                   how many of those queries returned no events
     * @param skippedPolls                         how many polls were skipped entirely because no new events were persisted
     * @param lastPollAt                           when the event store was last polled, or {@code null} if it never was
     * @param lastPollDuration                     how long the most recent poll took, or {@code null} if it never was polled
     * @param consecutiveNoPersistedEventsReturned the most recently reported number of consecutive polls returning no events
     * @param gapReconciliations                   how many times transient {@link GlobalEventOrder} gaps were reconciled after a poll
     */
    public record Polling(
            long polls,
            long pollsWithoutEvents,
            long skippedPolls,
            Instant lastPollAt,
            Duration lastPollDuration,
            int consecutiveNoPersistedEventsReturned,
            long gapReconciliations
    ) {
    }

    /**
     * {@link dk.trustworks.essentials.components.foundation.fencedlock.FencedLock} statistics for an exclusive
     * subscription. A high {@link #acquisitions()}/{@link #releases()} count relative to uptime means lock ownership
     * is flapping, typically because the lock time-to-live is shorter than the time the subscription needs to resume.
     *
     * @param acquisitions   how many times the lock was acquired by this instance
     * @param releases       how many times the lock was released by this instance
     * @param currentlyHeld  whether this instance currently holds the lock
     * @param lastAcquiredAt when the lock was last acquired, or {@code null} if it never was
     * @param lastReleasedAt when the lock was last released, or {@code null} if it never was
     */
    public record Lock(
            long acquisitions,
            long releases,
            boolean currentlyHeld,
            Instant lastAcquiredAt,
            Instant lastReleasedAt
    ) {
    }

    /**
     * Resume-point reset (replay) statistics. Throughput and lag read very differently during a replay, so a recent
     * reset is the first thing to check when a subscription looks far behind.
     *
     * @param resets           how many times the subscription's resume point was reset
     * @param lastResetAt      when the resume point was last reset, or {@code null} if it never was
     * @param lastResetToGlobalOrder the {@link GlobalEventOrder} the subscription was last reset to, or {@code null} if it never was reset
     */
    public record Reset(
            long resets,
            Instant lastResetAt,
            GlobalEventOrder lastResetToGlobalOrder
    ) {
    }
}
