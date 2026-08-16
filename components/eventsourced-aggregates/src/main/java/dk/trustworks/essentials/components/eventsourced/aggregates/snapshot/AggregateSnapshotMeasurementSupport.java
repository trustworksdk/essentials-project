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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.measurement.MeasurementTaker;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Provides a set of methods for recording measurements related to aggregate snapshot operations,
 * such as loading, saving, deleting, serializing, and deserializing snapshots. It integrates
 * with a Micrometer-based measurement system through a {@link MeasurementTaker} instance.
 * <p>
 * The functionality is centered around generating metrics for performance analysis and operational
 * monitoring of aggregate snapshot-related tasks.
 */
class AggregateSnapshotMeasurementSupport {
    static final String METRIC_PREFIX = "essentials.aggregate_snapshot";

    private final MeasurementTaker measurementTaker;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    AggregateSnapshotMeasurementSupport(Optional<MeterRegistry> meterRegistryOptional) {
        this.measurementTaker = MeasurementTaker.builder()
                                                .setMeterRegistry(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"))
                                                .build();
    }

    <T> T recordLoadSnapshot(AggregateType aggregateType,
                             Class<?> aggregateImplType,
                             Supplier<T> block) {
        return context("load_snapshot",
                       "Time taken to load a matching aggregate snapshot",
                       aggregateType,
                       aggregateImplType)
                .record(block);
    }

    <T> T recordLoadAllSnapshots(AggregateType aggregateType,
                                 Class<?> aggregateImplType,
                                 boolean includeSnapshotPayload,
                                 Supplier<T> block) {
        return context("load_all_snapshots",
                       "Time taken to load all aggregate snapshots",
                       aggregateType,
                       aggregateImplType)
                .tag("include_snapshot_payload", Boolean.toString(includeSnapshotPayload))
                .record(block);
    }

    <T> T recordFindMostRecentLastIncludedEventOrder(AggregateType aggregateType,
                                                     Class<?> aggregateImplType,
                                                     Supplier<T> block) {
        return context("find_most_recent_last_included_event_order",
                       "Time taken to resolve the most recent snapshot event order",
                       aggregateType,
                       aggregateImplType)
                .record(block);
    }

    void recordSaveSnapshot(AggregateType aggregateType,
                            Class<?> aggregateImplType,
                            Runnable block) {
        context("save_snapshot",
                "Time taken to persist an aggregate snapshot",
                aggregateType,
                aggregateImplType)
                .record(run(block));
    }

    void recordDeleteAllSnapshots(Class<?> aggregateImplType, Runnable block) {
        context("delete_all_snapshots",
                "Time taken to delete all aggregate snapshots for an aggregate implementation type",
                null,
                aggregateImplType)
                .record(run(block));
    }

    void recordDeleteSnapshots(AggregateType aggregateType,
                               Class<?> aggregateImplType,
                               String deleteMode,
                               Runnable block) {
        context("delete_snapshots",
                "Time taken to delete aggregate snapshots",
                aggregateType,
                aggregateImplType)
                .tag("delete_mode", deleteMode)
                .record(run(block));
    }

    <T> T recordSerializeSnapshot(AggregateType aggregateType,
                                  Class<?> aggregateImplType,
                                  Supplier<T> block) {
        return context("serialize_snapshot",
                       "Time taken to serialize an aggregate snapshot payload",
                       aggregateType,
                       aggregateImplType)
                .record(block);
    }

    void recordDeserializeSnapshot(AggregateType aggregateType,
                                   Class<?> aggregateImplType,
                                   String outcome,
                                   Duration duration) {
        context("deserialize_snapshot",
                "Time taken to deserialize an aggregate snapshot payload",
                aggregateType,
                aggregateImplType)
                .tag("outcome", outcome)
                .record(duration);
    }

    private MeasurementTaker.FluentMeasurementContext context(String metricName,
                                                              String description,
                                                              AggregateType aggregateType,
                                                              Class<?> aggregateImplType) {
        return measurementTaker.context(METRIC_PREFIX + "." + metricName)
                               .description(description)
                               .optionalTag("aggregate_type", aggregateType != null ? aggregateType.toString() : null)
                               .optionalTag("aggregate_impl_type", aggregateImplType != null ? aggregateImplType.getName() : null);
    }

    private Supplier<Void> run(Runnable block) {
        requireNonNull(block, "No block provided");
        return () -> {
            block.run();
            return null;
        };
    }
}
