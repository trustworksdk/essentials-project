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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.SubscriptionStatisticsRegistry.SubscriptionKey;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;

import java.time.*;
import java.util.concurrent.atomic.*;

/**
 * The mutable counters behind a {@link SubscriptionStatistics} snapshot - one instance per tracked subscription.
 * <p>
 * Written from the subscription hot path, so every mutator must stay allocation-free and lock-free:
 * {@link LongAdder} for counters, plain volatile writes for "last seen" values and a manual compare-and-set loop for
 * the maximum durations. Timestamps are kept as epoch milliseconds where {@code 0} means "never happened", which is
 * what the snapshot turns into a {@code null} {@link Instant}.
 */
class MutableSubscriptionStatistics {
    /** Longer failure texts are truncated - the registry must not become a log. */
    private static final int MAX_FAILURE_REASON_LENGTH = 512;

    private final SubscriptionKey key;
    private final Clock           clock;
    private final long            statisticsSinceEpochMillis;

    private final LongAdder starts              = new LongAdder();
    private final LongAdder stops               = new LongAdder();
    private final LongAdder eventsPublished     = new LongAdder();
    private final LongAdder eventsHandled       = new LongAdder();
    private final LongAdder failures            = new LongAdder();
    private final LongAdder polls               = new LongAdder();
    private final LongAdder pollsWithoutEvents  = new LongAdder();
    private final LongAdder skippedPolls        = new LongAdder();
    private final LongAdder gapReconciliations  = new LongAdder();
    private final LongAdder lockAcquisitions    = new LongAdder();
    private final LongAdder lockReleases        = new LongAdder();
    private final LongAdder resets              = new LongAdder();

    private final LongAdder  totalEventHandlingNanos = new LongAdder();
    private final AtomicLong maxEventHandlingNanos   = new AtomicLong();

    private volatile long   lastStartedAtEpochMillis;
    private volatile long   lastStoppedAtEpochMillis;
    private volatile long   lastEventHandledAtEpochMillis;
    private volatile long   lastEventHandledGlobalOrder;
    private volatile long   lastFailureAtEpochMillis;
    private volatile String lastFailureReason;
    private volatile long   lastNumberOfEventsRequested;
    private volatile long   lastPollAtEpochMillis;
    private volatile long   lastPollDurationNanos;
    private volatile int    consecutiveNoPersistedEventsReturned;
    private volatile boolean lockCurrentlyHeld;
    private volatile long    lastLockAcquiredAtEpochMillis;
    private volatile long    lastLockReleasedAtEpochMillis;
    private volatile long    lastResetAtEpochMillis;
    private volatile long    lastResetToGlobalOrder;

    MutableSubscriptionStatistics(SubscriptionKey key, Clock clock) {
        this.key = key;
        this.clock = clock;
        this.statisticsSinceEpochMillis = clock.millis();
    }

    void recordStarted() {
        starts.increment();
        lastStartedAtEpochMillis = clock.millis();
    }

    void recordStopped() {
        stops.increment();
        lastStoppedAtEpochMillis = clock.millis();
    }

    void recordEventPublishedToSubscriber() {
        eventsPublished.increment();
    }

    void recordEventHandled(GlobalEventOrder globalEventOrder, Duration handleEventDuration) {
        eventsHandled.increment();
        lastEventHandledAtEpochMillis = clock.millis();
        if (globalEventOrder != null) {
            lastEventHandledGlobalOrder = globalEventOrder.longValue();
        }
        if (handleEventDuration != null) {
            var nanos = handleEventDuration.toNanos();
            totalEventHandlingNanos.add(nanos);
            recordMax(maxEventHandlingNanos, nanos);
        }
    }

    void recordEventHandlingFailed(Throwable cause) {
        failures.increment();
        lastFailureAtEpochMillis = clock.millis();
        lastFailureReason = renderFailureReason(cause);
    }

    void recordEventsRequested(long numberOfEventsRequested) {
        lastNumberOfEventsRequested = numberOfEventsRequested;
    }

    void recordEventStorePolled(boolean returnedEvents, Duration pollDuration) {
        polls.increment();
        if (!returnedEvents) {
            pollsWithoutEvents.increment();
        }
        lastPollAtEpochMillis = clock.millis();
        if (pollDuration != null) {
            lastPollDurationNanos = pollDuration.toNanos();
        }
    }

    void recordSkippedPoll(int consecutiveNoPersistedEventsReturned) {
        skippedPolls.increment();
        this.consecutiveNoPersistedEventsReturned = consecutiveNoPersistedEventsReturned;
    }

    void recordConsecutiveNoPersistedEventsReturned(int consecutiveNoPersistedEventsReturned) {
        this.consecutiveNoPersistedEventsReturned = consecutiveNoPersistedEventsReturned;
    }

    void recordGapsReconciled() {
        gapReconciliations.increment();
    }

    void recordLockAcquired() {
        lockAcquisitions.increment();
        lockCurrentlyHeld = true;
        lastLockAcquiredAtEpochMillis = clock.millis();
    }

    void recordLockReleased() {
        lockReleases.increment();
        lockCurrentlyHeld = false;
        lastLockReleasedAtEpochMillis = clock.millis();
    }

    void recordReset(GlobalEventOrder resetToGlobalOrder) {
        resets.increment();
        lastResetAtEpochMillis = clock.millis();
        if (resetToGlobalOrder != null) {
            lastResetToGlobalOrder = resetToGlobalOrder.longValue();
        }
    }

    /**
     * @return an immutable snapshot of the counters as of now. Counters are read one at a time, so a snapshot taken
     * while events are flowing is eventually consistent across fields - never torn within a field
     */
    SubscriptionStatistics snapshot() {
        var handled = eventsHandled.sum();
        return new SubscriptionStatistics(
                key.subscriberId(),
                key.aggregateType(),
                Instant.ofEpochMilli(statisticsSinceEpochMillis),
                new SubscriptionStatistics.Lifecycle(
                        starts.sum(),
                        stops.sum(),
                        instantOrNull(lastStartedAtEpochMillis),
                        instantOrNull(lastStoppedAtEpochMillis)),
                new SubscriptionStatistics.EventHandling(
                        eventsPublished.sum(),
                        handled,
                        failures.sum(),
                        instantOrNull(lastEventHandledAtEpochMillis),
                        globalEventOrderOrNull(lastEventHandledGlobalOrder),
                        handled > 0 ? Duration.ofNanos(totalEventHandlingNanos.sum() / handled) : null,
                        handled > 0 ? Duration.ofNanos(maxEventHandlingNanos.get()) : null,
                        instantOrNull(lastFailureAtEpochMillis),
                        lastFailureReason,
                        lastNumberOfEventsRequested),
                new SubscriptionStatistics.Polling(
                        polls.sum(),
                        pollsWithoutEvents.sum(),
                        skippedPolls.sum(),
                        instantOrNull(lastPollAtEpochMillis),
                        lastPollAtEpochMillis > 0 ? Duration.ofNanos(lastPollDurationNanos) : null,
                        consecutiveNoPersistedEventsReturned,
                        gapReconciliations.sum()),
                new SubscriptionStatistics.Lock(
                        lockAcquisitions.sum(),
                        lockReleases.sum(),
                        lockCurrentlyHeld,
                        instantOrNull(lastLockAcquiredAtEpochMillis),
                        instantOrNull(lastLockReleasedAtEpochMillis)),
                new SubscriptionStatistics.Reset(
                        resets.sum(),
                        instantOrNull(lastResetAtEpochMillis),
                        globalEventOrderOrNull(lastResetToGlobalOrder)));
    }

    private static void recordMax(AtomicLong target, long candidate) {
        long current;
        while ((current = target.get()) < candidate && !target.compareAndSet(current, candidate)) {
            // Another thread moved the maximum in between - re-read and retry
        }
    }

    private static String renderFailureReason(Throwable cause) {
        if (cause == null) {
            return null;
        }
        var reason = cause.getClass().getSimpleName() + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
        return reason.length() > MAX_FAILURE_REASON_LENGTH ? reason.substring(0, MAX_FAILURE_REASON_LENGTH) : reason;
    }

    private static Instant instantOrNull(long epochMillis) {
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis) : null;
    }

    private static GlobalEventOrder globalEventOrderOrNull(long globalEventOrder) {
        return globalEventOrder > 0 ? GlobalEventOrder.of(globalEventOrder) : null;
    }
}
