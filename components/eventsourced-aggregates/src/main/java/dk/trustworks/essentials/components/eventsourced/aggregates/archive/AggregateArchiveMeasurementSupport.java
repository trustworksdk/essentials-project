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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.measurement.MeasurementTaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Optional;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

class AggregateArchiveMeasurementSupport {
    static final String METRIC_PREFIX = "essentials.aggregate_archive";

    private final MeasurementTaker        measurementTaker;
    private final Optional<MeterRegistry> meterRegistryOptional;

    AggregateArchiveMeasurementSupport(Optional<MeterRegistry> meterRegistryOptional) {
        this.meterRegistryOptional = requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided");
        this.measurementTaker = MeasurementTaker.builder()
                                                .withOptionalMicrometerMeasurementRecorder(meterRegistryOptional)
                                                .build();
    }

    <T> T recordArchiveGeneration(AggregateType aggregateType,
                                  Supplier<T> block) {
        return measurementTaker.context(METRIC_PREFIX + ".archive_generation")
                               .description("Time taken to archive a closed aggregate generation")
                               .tag("aggregate_type", aggregateType.toString())
                               .record(block);
    }

    void incrementArchiveOutcome(AggregateType aggregateType,
                                 String outcome) {
        meterRegistryOptional.ifPresent(meterRegistry -> Counter.builder(METRIC_PREFIX + ".archive_generation.outcome")
                                                                .description("Outcome of a closed aggregate generation archive attempt")
                                                                .tag("aggregate_type", aggregateType.toString())
                                                                .tag("outcome", outcome)
                                                                .register(meterRegistry)
                                                                .increment());
    }

    void recordArchivedEventCount(AggregateType aggregateType,
                                  long eventCount) {
        meterRegistryOptional.ifPresent(meterRegistry -> DistributionSummary.builder(METRIC_PREFIX + ".archived_event_count")
                                                                            .description("Number of persisted events exported for an archived generation")
                                                                            .tag("aggregate_type", aggregateType.toString())
                                                                            .baseUnit("events")
                                                                            .register(meterRegistry)
                                                                            .record(eventCount));
    }

    void recordArchivedBytes(AggregateType aggregateType,
                             long byteCount) {
        meterRegistryOptional.ifPresent(meterRegistry -> DistributionSummary.builder(METRIC_PREFIX + ".archived_bytes")
                                                                            .description("Number of bytes written for an archived generation artifact")
                                                                            .tag("aggregate_type", aggregateType.toString())
                                                                            .baseUnit("bytes")
                                                                            .register(meterRegistry)
                                                                            .record(byteCount));
    }
}
