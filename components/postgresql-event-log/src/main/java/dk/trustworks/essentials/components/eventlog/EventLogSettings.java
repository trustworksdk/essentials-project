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
 *                             {@link #probeWait(long)}
 * @param probeBackoffMax      most time between two such re-probes, however expensive the probe was
 * @param progressSaveInterval least time between two periodic resume-point saves of one subscription; zero saves
 *                             after every handled event
 * @param holeRangeThreshold   a gap of more than this many values (a sequence jump) is kept as one range and re-read
 *                             by the range branch, instead of as single values in the {@code = ANY} branch
 * @param genericPlans         run the tail reader's statements under {@code plan_cache_mode = force_generic_plan} with
 *                             server-side prepared statements, except those whose generic plan is not index-only
 *                             (P0.16; {@code EventLogGenericPlanIT} checks them). {@code false} plans every read custom
 */
public record EventLogSettings(Duration backstopInterval,
                               int tailPageSize,
                               Duration probeBackoffMin,
                               Duration probeBackoffMax,
                               Duration progressSaveInterval,
                               int holeRangeThreshold,
                               boolean genericPlans) {
    /**
     * A probe may take at most about 1 % of the reader's time while it waits for a candidate to retire (P0.12: the
     * running-set read costs about 0.55 µs per live backend, much more under write load)
     */
    static final int PROBE_COST_MULTIPLIER = 100;

    public EventLogSettings {
        requirePositive(backstopInterval, "backstopInterval");
        requireTrue(tailPageSize > 0, "tailPageSize must be > 0");
        requirePositive(probeBackoffMin, "probeBackoffMin");
        requirePositive(probeBackoffMax, "probeBackoffMax");
        requireTrue(probeBackoffMax.compareTo(probeBackoffMin) >= 0, "probeBackoffMax must be >= probeBackoffMin");
        requireNonNull(progressSaveInterval, "No progressSaveInterval provided");
        requireTrue(!progressSaveInterval.isNegative(), "progressSaveInterval must not be negative");
        requireTrue(holeRangeThreshold > 0, "holeRangeThreshold must be > 0");
    }

    private static void requirePositive(Duration duration, String name) {
        requireNonNull(duration, "No " + name + " provided");
        requireTrue(duration.isPositive(), name + " must be positive");
    }

    /**
     * Backstop 1 s, page 100 (P0.13), re-probe between 1 s and 10 s, save every second, ranges above 1 000 values,
     * generic plans on
     */
    public static EventLogSettings defaults() {
        return new EventLogSettings(Duration.ofSeconds(1), 100, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(1), 1_000, true);
    }

    public EventLogSettings withBackstopInterval(Duration backstopInterval) {
        var min = probeBackoffMin.compareTo(backstopInterval) > 0 ? probeBackoffMin : backstopInterval;
        return new EventLogSettings(backstopInterval, tailPageSize, min, probeBackoffMax.compareTo(min) < 0 ? min : probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans);
    }

    public EventLogSettings withTailPageSize(int tailPageSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans);
    }

    public EventLogSettings withProbeBackoff(Duration min, Duration max) {
        return new EventLogSettings(backstopInterval, tailPageSize, min, max, progressSaveInterval, holeRangeThreshold, genericPlans);
    }

    public EventLogSettings withProgressSaveInterval(Duration progressSaveInterval) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans);
    }

    public EventLogSettings withHoleRangeThreshold(int holeRangeThreshold) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans);
    }

    public EventLogSettings withGenericPlans(boolean genericPlans) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans);
    }

    /**
     * How long to wait before re-probing while a candidate is outstanding and the last probe read nothing new (P0.12
     * decision: back off with the probe's measured cost; with backstop wake-up only, never sooner than the backstop).
     *
     * @param lastProbeNanos what the last probe statement took
     */
    Duration probeWait(long lastProbeNanos) {
        var floor = probeBackoffMin.compareTo(backstopInterval) > 0 ? probeBackoffMin : backstopInterval;
        var cost  = Duration.ofNanos(Math.multiplyExact(Math.max(0, lastProbeNanos), (long) PROBE_COST_MULTIPLIER));
        if (cost.compareTo(floor) < 0) {
            return floor;
        }
        return cost.compareTo(probeBackoffMax) > 0 ? probeBackoffMax : cost;
    }
}
