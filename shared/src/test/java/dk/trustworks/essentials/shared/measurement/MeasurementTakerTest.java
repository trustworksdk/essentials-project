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

package dk.trustworks.essentials.shared.measurement;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the construction surface that lets {@code Optional<MeterRegistry>} disappear from constructors:
 * {@link MeasurementTaker#none()} as the neutral default, and the plain-value / {@code Optional} setter pair on
 * {@link MeasurementTaker.Builder}.
 */
class MeasurementTakerTest {

    @Test
    void none_records_nothing_but_still_runs_the_measured_block() {
        var blockRan = new boolean[1];

        var result = MeasurementTaker.none()
                                     .context("essentials.test")
                                     .description("A block measured by nobody")
                                     .record(() -> {
                                         blockRan[0] = true;
                                         return "the-result";
                                     });

        assertThat(blockRan[0]).isTrue();
        assertThat(result).isEqualTo("the-result");
    }

    @Test
    void none_returns_a_cached_instance() {
        assertThat(MeasurementTaker.none()).isSameAs(MeasurementTaker.none());
    }

    @Test
    void none_tolerates_recordTime_without_any_recorder() {
        MeasurementTaker.none().recordTime(MeasurementContext.builder("essentials.test").build(),
                                           Duration.ofMillis(5));
    }

    @Test
    void a_null_meter_registry_means_no_micrometer_recording() {
        var recordingRecorder = new RecordingMeasurementRecorder();

        var measurementTaker = MeasurementTaker.builder()
                                               .setMeterRegistry((MeterRegistry) null)
                                               .addRecorder(recordingRecorder)
                                               .build();

        measurementTaker.recordTime(MeasurementContext.builder("essentials.test").build(), Duration.ofMillis(1));

        // Only the explicitly added recorder saw it — the null registry added nothing rather than throwing
        assertThat(recordingRecorder.recorded).hasSize(1);
    }

    @Test
    void a_present_meter_registry_is_recorded_to() {
        var meterRegistry = new SimpleMeterRegistry();

        var measurementTaker = MeasurementTaker.builder()
                                               .setMeterRegistry(meterRegistry)
                                               .build();

        measurementTaker.recordTime(MeasurementContext.builder("essentials.test.metric").build(),
                                    Duration.ofMillis(42));

        assertThat(meterRegistry.find("essentials.test.metric").timer()).isNotNull();
    }

    @Test
    void the_optional_overload_agrees_with_the_plain_value_one() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        var fromOptional = MeasurementTaker.builder()
                                           .setMeterRegistry(Optional.of(meterRegistry))
                                           .build();

        fromOptional.recordTime(MeasurementContext.builder("essentials.test.from_optional").build(),
                                Duration.ofMillis(7));

        assertThat(meterRegistry.find("essentials.test.from_optional").timer()).isNotNull();
    }

    @Test
    void an_empty_optional_registry_records_nothing() {
        var measurementTaker = MeasurementTaker.builder()
                                               .setMeterRegistry(Optional.empty())
                                               .build();

        // No recorders at all, so this is a no-op rather than a failure
        measurementTaker.recordTime(MeasurementContext.builder("essentials.test").build(), Duration.ofMillis(1));
    }

    @Test
    void a_null_optional_registry_is_rejected() {
        assertThatThrownBy(() -> MeasurementTaker.builder().setMeterRegistry((Optional<MeterRegistry>) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_logging_recorder_can_be_configured_from_a_class() {
        var measurementTaker = MeasurementTaker.builder()
                                               .setLoggingRecorder(MeasurementTakerTest.class, LogThresholds.defaultThresholds())
                                               .build();

        // Nothing to assert on the log output itself — this pins that the convenience overload builds a usable taker
        measurementTaker.recordTime(MeasurementContext.builder("essentials.test").build(), Duration.ofMillis(1));
    }

    @Test
    void the_deprecated_micrometer_setter_still_agrees_with_its_replacement() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        @SuppressWarnings("removal")
        var measurementTaker = MeasurementTaker.builder()
                                               .withOptionalMicrometerMeasurementRecorder(Optional.of(meterRegistry))
                                               .build();

        measurementTaker.recordTime(MeasurementContext.builder("essentials.test.bridge").build(),
                                    Duration.ofMillis(3));

        assertThat(meterRegistry.find("essentials.test.bridge").timer()).isNotNull();
    }

    private static final class RecordingMeasurementRecorder implements MeasurementRecorder {
        private final List<MeasurementContext> recorded = new ArrayList<>();

        @Override
        public void record(MeasurementContext context, Duration duration) {
            recorded.add(context);
        }
    }
}
