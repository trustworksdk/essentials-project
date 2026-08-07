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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.profile;

import java.time.Duration;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.*;

public record AggregateSnapshotProfilingSettings(List<Integer> eventCounts,
                                                 List<Integer> snapshotIntervals,
                                                 int warmupIterations,
                                                 int measuredIterations,
                                                 Duration targetReplayTime) {
    public AggregateSnapshotProfilingSettings {
        requireNonNull(eventCounts, "No eventCounts provided");
        requireNonNull(snapshotIntervals, "No snapshotIntervals provided");
        requireFalse(eventCounts.isEmpty(), "eventCounts must not be empty");
        requireFalse(snapshotIntervals.isEmpty(), "snapshotIntervals must not be empty");
        requireTrue(eventCounts.stream().allMatch(count -> count > 0), "eventCounts must contain values > 0");
        requireTrue(snapshotIntervals.stream().allMatch(interval -> interval > 0), "snapshotIntervals must contain values > 0");
        requireTrue(warmupIterations >= 0, "warmupIterations must be >= 0");
        requireTrue(measuredIterations > 0, "measuredIterations must be > 0");
        requireNonNull(targetReplayTime, "No targetReplayTime provided");
        requireTrue(!targetReplayTime.isNegative() && !targetReplayTime.isZero(), "targetReplayTime must be > 0");
        eventCounts = List.copyOf(eventCounts);
        snapshotIntervals = List.copyOf(snapshotIntervals);
    }
}
