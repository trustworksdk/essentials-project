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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public final class AggregateSnapshotProfilingHarness {
    public <AGGREGATE, EVENT, SNAPSHOT> AggregateSnapshotProfileReport profile(AggregateSnapshotProfilingAdapter<AGGREGATE, EVENT, SNAPSHOT> adapter,
                                                                               AggregateSnapshotProfilingSettings settings) {
        requireNonNull(adapter, "No adapter provided");
        requireNonNull(settings, "No settings provided");

        var replayMeasurements = new ArrayList<AggregateSnapshotProfileReport.ReplayScenarioMeasurement>();
        var snapshotMeasurements = new ArrayList<AggregateSnapshotProfileReport.SnapshotScenarioMeasurement>();

        for (var eventCount : settings.eventCounts()) {
            var eventHistory = adapter.createEventHistory(eventCount);
            var replayTimes = measure(settings.warmupIterations(),
                                      settings.measuredIterations(),
                                      () -> adapter.rehydrateFromEvents(eventHistory));
            replayMeasurements.add(new AggregateSnapshotProfileReport.ReplayScenarioMeasurement(eventCount,
                                                                                                average(replayTimes),
                                                                                                min(replayTimes),
                                                                                                max(replayTimes)));

            for (var snapshotInterval : settings.snapshotIntervals()) {
                if (snapshotInterval >= eventCount) continue;

                var snapshotSourceEvents = eventHistory.subList(0, snapshotInterval);
                var remainingEvents = eventHistory.subList(snapshotInterval, eventHistory.size());
                var sourceAggregate = adapter.rehydrateFromEvents(snapshotSourceEvents);

                var snapshotCreationTimes = measure(settings.warmupIterations(),
                                                   settings.measuredIterations(),
                                                   () -> adapter.createSnapshot(sourceAggregate));
                var snapshot = adapter.createSnapshot(sourceAggregate);
                var replayFromSnapshotTimes = measure(settings.warmupIterations(),
                                                      settings.measuredIterations(),
                                                      () -> adapter.rehydrateFromSnapshot(snapshot, remainingEvents));

                snapshotMeasurements.add(new AggregateSnapshotProfileReport.SnapshotScenarioMeasurement(eventCount,
                                                                                                        snapshotInterval,
                                                                                                        average(snapshotCreationTimes),
                                                                                                        average(replayFromSnapshotTimes),
                                                                                                        remainingEvents.size()));
            }
        }

        return new AggregateSnapshotProfileReport(adapter.aggregateName(),
                                                  settings,
                                                  List.copyOf(replayMeasurements),
                                                  List.copyOf(snapshotMeasurements),
                                                  determineRecommendation(settings, replayMeasurements, snapshotMeasurements));
    }

    private Optional<AggregateSnapshotProfileReport.SnapshotRecommendation> determineRecommendation(AggregateSnapshotProfilingSettings settings,
                                                                                                    List<AggregateSnapshotProfileReport.ReplayScenarioMeasurement> replayMeasurements,
                                                                                                    List<AggregateSnapshotProfileReport.SnapshotScenarioMeasurement> snapshotMeasurements) {
        return replayMeasurements.stream()
                                 .filter(replay -> replay.averageReplayTime().compareTo(settings.targetReplayTime()) > 0)
                                 .findFirst()
                                 .flatMap(replay -> snapshotMeasurements.stream()
                                                                       .filter(snapshot -> snapshot.eventCount() == replay.eventCount())
                                                                       .filter(snapshot -> snapshot.averageReplayFromSnapshotTime().compareTo(replay.averageReplayTime()) < 0)
                                                                       .min(Comparator.comparing(AggregateSnapshotProfileReport.SnapshotScenarioMeasurement::averageReplayFromSnapshotTime)
                                                                                      .thenComparing(AggregateSnapshotProfileReport.SnapshotScenarioMeasurement::averageSnapshotCreationTime))
                                                                       .map(best -> new AggregateSnapshotProfileReport.SnapshotRecommendation(replay.eventCount(),
                                                                                                                                              best.snapshotInterval(),
                                                                                                                                              replay.averageReplayTime(),
                                                                                                                                              best.averageReplayFromSnapshotTime(),
                                                                                                                                              best.averageSnapshotCreationTime())));
    }

    private List<Duration> measure(int warmupIterations,
                                   int measuredIterations,
                                   Runnable operation) {
        for (int i = 0; i < warmupIterations; i++) {
            operation.run();
        }

        var measurements = new ArrayList<Duration>(measuredIterations);
        for (int i = 0; i < measuredIterations; i++) {
            long startedAt = System.nanoTime();
            operation.run();
            measurements.add(Duration.ofNanos(System.nanoTime() - startedAt));
        }
        return measurements;
    }

    private Duration average(List<Duration> durations) {
        long totalNanos = durations.stream().mapToLong(Duration::toNanos).sum();
        return Duration.ofNanos(totalNanos / durations.size());
    }

    private Duration min(List<Duration> durations) {
        return durations.stream().min(Comparator.naturalOrder()).orElseThrow();
    }

    private Duration max(List<Duration> durations) {
        return durations.stream().max(Comparator.naturalOrder()).orElseThrow();
    }
}
