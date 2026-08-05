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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public final class AggregateSnapshotProfileReportTextRenderer {
    public String render(AggregateSnapshotProfileReport report) {
        requireNonNull(report, "No report provided");

        var text = new StringBuilder();
        text.append("Aggregate Snapshot Profile").append('\n');
        text.append("Aggregate: ").append(report.aggregateName()).append('\n');
        text.append("Target replay time: ").append(format(report.settings().targetReplayTime())).append('\n');
        text.append('\n');

        text.append("Replay Measurements").append('\n');
        for (var measurement : report.replayMeasurements()) {
            text.append("  - events=").append(measurement.eventCount())
                .append(", avg=").append(format(measurement.averageReplayTime()))
                .append(", min=").append(format(measurement.fastestReplayTime()))
                .append(", max=").append(format(measurement.slowestReplayTime()))
                .append('\n');
        }
        text.append('\n');

        text.append("Snapshot Measurements").append('\n');
        for (var measurement : report.snapshotMeasurements()) {
            text.append("  - events=").append(measurement.eventCount())
                .append(", interval=").append(measurement.snapshotInterval())
                .append(", snapshot=").append(format(measurement.averageSnapshotCreationTime()))
                .append(", replay-from-snapshot=").append(format(measurement.averageReplayFromSnapshotTime()))
                .append(", tail-events=").append(measurement.replayedTailEventCount())
                .append('\n');
        }
        text.append('\n');

        text.append("Recommendation").append('\n');
        if (report.recommendation().isPresent()) {
            var recommendation = report.recommendation().get();
            text.append("  Enable snapshotting around event count ")
                .append(recommendation.eventCountThreshold())
                .append(" with interval ")
                .append(recommendation.recommendedSnapshotInterval())
                .append(" (baseline=").append(format(recommendation.baselineReplayTime()))
                .append(", replay-from-snapshot=").append(format(recommendation.replayFromSnapshotTime()))
                .append(", snapshot=").append(format(recommendation.snapshotCreationTime()))
                .append(')')
                .append('\n');
        } else {
            text.append("  No recommendation produced for the configured thresholds").append('\n');
        }

        return text.toString();
    }

    private String format(Duration duration) {
        long nanos = duration.toNanos();
        if (nanos < 1_000) {
            return nanos + "ns";
        }

        double micros = nanos / 1_000.0;
        if (micros < 1_000) {
            return String.format(java.util.Locale.ROOT, "%.2fus", micros);
        }

        double millis = nanos / 1_000_000.0;
        if (millis < 1_000) {
            return String.format(java.util.Locale.ROOT, "%.2fms", millis);
        }

        double seconds = nanos / 1_000_000_000.0;
        return String.format(java.util.Locale.ROOT, "%.2fs", seconds);
    }
}
