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
import java.util.*;

public record AggregateSnapshotProfileReport(String aggregateName,
                                             AggregateSnapshotProfilingSettings settings,
                                             List<ReplayScenarioMeasurement> replayMeasurements,
                                             List<SnapshotScenarioMeasurement> snapshotMeasurements,
                                             Optional<SnapshotRecommendation> recommendation) {

    public record ReplayScenarioMeasurement(int eventCount,
                                            Duration averageReplayTime,
                                            Duration fastestReplayTime,
                                            Duration slowestReplayTime) {
    }

    public record SnapshotScenarioMeasurement(int eventCount,
                                              int snapshotInterval,
                                              Duration averageSnapshotCreationTime,
                                              Duration averageReplayFromSnapshotTime,
                                              int replayedTailEventCount) {
    }

    public record SnapshotRecommendation(int eventCountThreshold,
                                         int recommendedSnapshotInterval,
                                         Duration baselineReplayTime,
                                         Duration replayFromSnapshotTime,
                                         Duration snapshotCreationTime) {
    }
}
