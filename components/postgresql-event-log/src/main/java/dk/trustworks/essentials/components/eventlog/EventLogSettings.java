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
package dk.trustworks.essentials.components.eventlog;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Settings of the event log's subscriptions (design {@code docs/plans/event-store-engine-phase2.md} §1.2, §3.3, §3.4).
 *
 * @param backstopInterval     tier-0 wake-up: how long a caught-up tail reader parks before it reads again (plan §11).
 *                             Phase 2 has no other wake-up
 * @param tailPageSize         most rows one tail read returns above the cursor; late commits re-read from holes do
 *                             not count against it
 * @param probeBackoffMin      least time between two re-probes while a candidate is outstanding and nothing new was
 *                             read; never below {@code backstopInterval}'s role as the only wake-up, see
 *                             {@link #probeWait(long, int)}
 * @param probeBackoffMax      most time between two such re-probes, however expensive the probe was
 * @param progressSaveInterval least time between two periodic resume-point saves of one subscription; zero saves
 *                             after every handled event
 * @param holeRangeThreshold   a gap of more than this many values (a sequence jump) is kept as one range and re-read
 *                             by the range branch, instead of as single values in the {@code = ANY} branch
 * @param genericPlans         run the tail reader's statements under {@code plan_cache_mode = force_generic_plan} with
 *                             server-side prepared statements, except those whose generic plan is not index-only
 *                             (P0.16; {@code EventLogGenericPlanIT} checks them). {@code false} plans every read custom
 * @param subscriptionBufferSize most events a subscription's inbox holds before the next batch overflows it and the
 *                             subscription detaches (phase 3 §2.3); at least {@code tailPageSize}. An empty inbox
 *                             accepts any batch
 * @param catchUpPageSize      most rows one catch-up or bridge statement returns (phase 3 §3.3, §3.4)
 * @param pageSize             most events one page transaction of an in-transaction subscription applies (phase 4
 *                             §3.2; P0.13: no gain above about 100 row by row)
 * @param retryAttempts        how many times an event that fails alone is retried before it is parked (phase 4 §4.3);
 *                             zero parks it on its first failure alone
 * @param retryBackoffInitial  the sleep before the first retry of an event that failed alone, doubled before each further
 *                             retry; also the first wait after an I/O failure, which is retried indefinitely (phase 4 §4.1)
 * @param retryBackoffMax      the most any such sleep or wait lasts
 */
public record EventLogSettings(Duration backstopInterval,
                               int tailPageSize,
                               Duration probeBackoffMin,
                               Duration probeBackoffMax,
                               Duration progressSaveInterval,
                               int holeRangeThreshold,
                               boolean genericPlans,
                               int subscriptionBufferSize,
                               int catchUpPageSize,
                               int pageSize,
                               int retryAttempts,
                               Duration retryBackoffInitial,
                               Duration retryBackoffMax) {
    /**
     * The probes of the whole cluster may take at most about 1 % of one core while candidates are outstanding: one
     * reader's probe costs at most 1 % of its wait ({@code wait >= cost * 100}), divided by the number of readers that
     * probe the same server (P0.12: the running-set read costs about 0.55-1.4 µs per live backend, much more under
     * write load, and every reader of every JVM pays it)
     */
    static final int PROBE_COST_MULTIPLIER = 100;

    /**
     * How many backends the back-off assumes per probing reader when it estimates the cluster's readers from the
     * backend count: P0.12's topology, a JVM with a pool of 10 and one tail reader. Phase-2 unit C measured that
     * {@code cost * 100} alone (no backstop floor) let 40 readers at 400 connections take 29 % of a core, and the 1 s
     * backstop floor alone 6.4 %; scaled by {@code backends / 10} it stays under 1 %.
     */
    static final int BACKENDS_PER_READER = 10;

    public EventLogSettings {
        requirePositive(backstopInterval, "backstopInterval");
        requireTrue(tailPageSize > 0, "tailPageSize must be > 0");
        requirePositive(probeBackoffMin, "probeBackoffMin");
        requirePositive(probeBackoffMax, "probeBackoffMax");
        requireTrue(probeBackoffMax.compareTo(probeBackoffMin) >= 0, "probeBackoffMax must be >= probeBackoffMin");
        requireNonNull(progressSaveInterval, "No progressSaveInterval provided");
        requireTrue(!progressSaveInterval.isNegative(), "progressSaveInterval must not be negative");
        requireTrue(holeRangeThreshold > 0, "holeRangeThreshold must be > 0");
        requireTrue(subscriptionBufferSize >= tailPageSize, "subscriptionBufferSize must be >= tailPageSize");
        requireTrue(catchUpPageSize > 0, "catchUpPageSize must be > 0");
        requireTrue(pageSize > 0, "pageSize must be > 0");
        requireTrue(retryAttempts >= 0, "retryAttempts must be >= 0");
        requirePositive(retryBackoffInitial, "retryBackoffInitial");
        requirePositive(retryBackoffMax, "retryBackoffMax");
        requireTrue(retryBackoffMax.compareTo(retryBackoffInitial) >= 0, "retryBackoffMax must be >= retryBackoffInitial");
    }

    private static void requirePositive(Duration duration, String name) {
        requireNonNull(duration, "No " + name + " provided");
        requireTrue(duration.isPositive(), name + " must be positive");
    }

    /**
     * Backstop 1 s, page 100 (P0.13), re-probe between 1 s and 10 s, save every second, ranges above 1 000 values,
     * generic plans on, inbox 1 000 events, catch-up page 500, page transaction 100 events, 3 retries backing off from
     * 100 ms to at most 10 s
     */
    public static EventLogSettings defaults() {
        return new EventLogSettings(Duration.ofSeconds(1), 100, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(1), 1_000, true, 1_000, 500, 100, 3, Duration.ofMillis(100), Duration.ofSeconds(10));
    }

    public EventLogSettings withBackstopInterval(Duration backstopInterval) {
        var min = probeBackoffMin.compareTo(backstopInterval) > 0 ? probeBackoffMin : backstopInterval;
        return new EventLogSettings(backstopInterval, tailPageSize, min, probeBackoffMax.compareTo(min) < 0 ? min : probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withTailPageSize(int tailPageSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withProbeBackoff(Duration min, Duration max) {
        return new EventLogSettings(backstopInterval, tailPageSize, min, max, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withProgressSaveInterval(Duration progressSaveInterval) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withHoleRangeThreshold(int holeRangeThreshold) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withGenericPlans(boolean genericPlans) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withSubscriptionBufferSize(int subscriptionBufferSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withCatchUpPageSize(int catchUpPageSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withPageSize(int pageSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withRetryAttempts(int retryAttempts) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax);
    }

    public EventLogSettings withRetryBackoff(Duration initial, Duration max) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, initial, max);
    }

    /**
     * @param retry the zero-based number of the retry (or of the consecutive I/O failure) about to happen
     * @return {@link #retryBackoffInitial()} doubled {@code retry} times, at most {@link #retryBackoffMax()}
     */
    Duration retryBackoff(int retry) {
        var shift = Math.min(Math.max(0, retry), 30);
        var nanos = retryBackoffInitial.toNanos();
        var max   = retryBackoffMax.toNanos();
        return Duration.ofNanos(nanos > (max >> shift) ? max : Math.min(max, nanos << shift));
    }

    /**
     * How long to wait before re-probing while a candidate is outstanding and the last probe read nothing new (P0.12
     * decision: back off with the probe's measured cost; with backstop wake-up only, never sooner than the backstop):
     * {@code cost * 100 * max(1, backends / 10)}, between {@code max(backstopInterval, probeBackoffMin)} and
     * {@code probeBackoffMax}. The second factor estimates how many readers probe the same server, so the cluster's
     * probes, not each reader's, stay near 1 % of one core ({@link #BACKENDS_PER_READER}). Beyond what
     * {@code probeBackoffMax} allows (about 400 backends at a 2.5 ms probe and a 10 s maximum) the budget is exceeded.
     *
     * @param lastProbeNanos what the last probe statement took
     * @param backends       how many backends the last running-set read saw
     */
    Duration probeWait(long lastProbeNanos, int backends) {
        var floor   = probeBackoffMin.compareTo(backstopInterval) > 0 ? probeBackoffMin : backstopInterval;
        var readers = Math.max(1L, backends / BACKENDS_PER_READER);
        var cost    = Duration.ofNanos(Math.multiplyExact(Math.multiplyExact(Math.max(0, lastProbeNanos), (long) PROBE_COST_MULTIPLIER), readers));
        if (cost.compareTo(floor) < 0) {
            return floor;
        }
        return cost.compareTo(probeBackoffMax) > 0 ? probeBackoffMax : cost;
    }
}
