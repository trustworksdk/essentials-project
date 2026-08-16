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
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A facade to record the execution time of a given code block using one or more {@link MeasurementRecorder} instances.
 * <p>
 * Example of fluent usage:
 * <pre>{@code
 * return measurementTaker.context("essentials.eventstore.append_to_stream")
 *                         .description("Time taken to append events to the event store")
 *                         .tag("aggregateType", operation.getAggregateType())
 *                         .record(chain::proceed);
 * }
 * </pre>
 * or
 * <pre>{@code
 * measurementTaker.recordTime(MeasurementContext.builder("essentials.invocation")
 *                                               .description("Time it takes to invoke a method")
 *                                               .tag("class", FunctionalInterfaceLoggingNameResolver.resolveLoggingName(invokeMethodsOn))
 *                                               .tag("method", methodLoggingName)
 *                                               .build(),
 *                             duration);
 * }
 * </pre>
 */
public class MeasurementTaker {
    /**
     * @see #none()
     */
    private static final MeasurementTaker NONE = new MeasurementTaker(List.of());

    private final List<MeasurementRecorder> recorders;

    private MeasurementTaker(List<MeasurementRecorder> recorders) {
        requireNonNull(recorders, "No recorders provided");
        this.recorders = Collections.unmodifiableList(recorders);
    }

    /**
     * Creates a new Builder for constructing a MeasurementTaker.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The no-op {@link MeasurementTaker}: a shared, immutable instance with zero {@link MeasurementRecorder}s, which
     * runs the measured block and records nothing.
     * <p>
     * This is the <b>neutral default</b> that makes {@code Optional<MeterRegistry>} unnecessary in constructors. A
     * component that wants to be measurable takes a plain {@code MeasurementTaker}; a caller with nothing to measure
     * to passes {@code MeasurementTaker.none()} rather than {@code Optional.empty()}, and the component needs no
     * branch for the absent case. The instance is cached because "no metrics configured" is the common case and it
     * carries no state.
     *
     * @return the shared no-op MeasurementTaker — never {@code null}
     */
    public static MeasurementTaker none() {
        return NONE;
    }

    /**
     * Whether this {@link MeasurementTaker} has any {@link MeasurementRecorder} at all — i.e. whether measuring
     * anything can have an effect.
     * <p>
     * {@link #record(MeasurementContext, Supplier)} and {@link #recordTime(MeasurementContext, Duration)} are already
     * safe on a taker with no recorders, so this is <em>not</em> needed for correctness. It exists for hot paths that
     * would otherwise pay to assemble a {@link MeasurementContext} — building the tag map, resolving handler names —
     * only to hand it to nobody. Such a caller can branch on this instead of on a separate
     * {@code recordExecutionTimeEnabled} flag:
     * <pre>{@code
     * if (measurementTaker.isRecording()) {
     *     return measurementTaker.context("essentials.queue.handle").tag(…).record(chain::proceed);
     * }
     * return chain.proceed();
     * }</pre>
     *
     * @return {@code true} if at least one recorder is configured; {@code false} for {@link #none()} and for any
     *         taker built with no recorders
     */
    public boolean isRecording() {
        return !recorders.isEmpty();
    }

    /**
     * Executes the supplied block of code, measures its execution time, and notifies all configured recorders.
     *
     * @param context the measurement context containing metric information
     * @param block   the code block whose execution time is to be measured
     * @param <T>     the type of result returned by the code block
     * @return the result of executing the code block
     */
    public <T> T record(MeasurementContext context, Supplier<T> block) {
        requireNonNull(context, "No context provided");
        requireNonNull(block, "No block provided");
        long start = System.nanoTime();
        try {
            return block.get();
        } finally {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            recorders.forEach(recorder -> recorder.record(context, elapsed));
        }
    }

    /**
     * Records an already measured duration.
     *
     * @param context the measurement context containing metric name, description and tags
     * @param elapsed the elapsed time to record
     */
    public void recordTime(MeasurementContext context, Duration elapsed) {
        requireNonNull(context, "No context provided");
        requireNonNull(elapsed, "No elapsed provided");
        recorders.forEach(recorder -> recorder.record(context, elapsed));
    }


    /**
     * Starts a fluent measurement configuration for the specified metric.
     *
     * @param metricName the name of the metric
     * @return a fluent context builder for further configuration
     */
    public FluentMeasurementContext context(String metricName) {
        return new FluentMeasurementContext(this, metricName);
    }

    /**
     * Fluent builder for constructing a MeasurementTaker.
     */
    public static class Builder {
        private final List<MeasurementRecorder> recorders = new ArrayList<>();

        /**
         * Adds a {@link MeasurementRecorder} to the configuration.
         *
         * @param recorder the recorder to add
         * @return this builder instance for fluent chaining
         */
        public Builder addRecorder(MeasurementRecorder recorder) {
            recorders.add(
                    requireNonNull(recorder, "No recorder provided")
                         );
            return this;
        }

        /**
         * Configures a {@link MicrometerMeasurementRecorder} for the given {@link MeterRegistry}.
         * <p>
         * A {@code null} registry means "no Micrometer metrics" and is accepted deliberately: this is the plain-value
         * setter that lets a caller hold a nullable field instead of an {@code Optional} one. Use
         * {@link #setMeterRegistry(Optional)} at a Spring {@code @Bean} boundary, where an {@code Optional} injection
         * point is idiomatic.
         *
         * @param meterRegistry the MeterRegistry to record to, or {@code null} for no Micrometer recording
         * @return this builder instance for fluent chaining
         */
        public Builder setMeterRegistry(MeterRegistry meterRegistry) {
            if (meterRegistry != null) {
                addRecorder(new MicrometerMeasurementRecorder(meterRegistry));
            }
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry(MeterRegistry)}, for Spring {@code @Bean} methods that
         * receive an {@code Optional<MeterRegistry>} injection point and unwrap it on the spot.
         *
         * @param meterRegistry an Optional MeterRegistry instance — empty means no Micrometer recording
         * @return this builder instance for fluent chaining
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
            requireNonNull(meterRegistry, "No meterRegistry provided");
            return setMeterRegistry(meterRegistry.orElse(null));
        }

        /**
         * Configures a {@link LoggingMeasurementRecorder} logging under the given class's logger name.
         * <p>
         * This is the half of the assembly that was being hand-written at every call site, always as
         * {@code addRecorder(new LoggingMeasurementRecorder(LoggerFactory.getLogger(getClass()), thresholds))}.
         * Pass {@code getClass()} to keep logging under the runtime type, exactly as those sites did.
         *
         * @param loggerOwner the class whose name the logger is created under
         * @param thresholds  the thresholds deciding which log level each measured duration is reported at
         * @return this builder instance for fluent chaining
         */
        public Builder setLoggingRecorder(Class<?> loggerOwner, LogThresholds thresholds) {
            requireNonNull(loggerOwner, "No loggerOwner provided");
            return setLoggingRecorder(LoggerFactory.getLogger(loggerOwner), thresholds);
        }

        /**
         * Configures a {@link LoggingMeasurementRecorder} against an already-resolved {@link Logger}.
         *
         * @param logger     the logger to report measurements to
         * @param thresholds the thresholds deciding which log level each measured duration is reported at
         * @return this builder instance for fluent chaining
         */
        public Builder setLoggingRecorder(Logger logger, LogThresholds thresholds) {
            requireNonNull(logger, "No logger provided");
            requireNonNull(thresholds, "No thresholds provided");
            return addRecorder(new LoggingMeasurementRecorder(logger, thresholds));
        }

        /**
         * Optionally configures a MeterRegistry.
         * If the provided {@code Optional<MeterRegistry>} is non-empty,
         * a {@link MicrometerMeasurementRecorder} is added.
         *
         * @param meterRegistryOptional an Optional MeterRegistry instance
         * @return this builder instance for fluent chaining
         * @deprecated Use {@link #setMeterRegistry(MeterRegistry)}, or {@link #setMeterRegistry(Optional)} at a Spring
         *         {@code @Bean} boundary. Renamed to match the project-wide {@code setXxx} builder convention; the
         *         behaviour is unchanged and this method delegates.
         */
        @Deprecated(forRemoval = true, since = "0.40.x")
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder withOptionalMicrometerMeasurementRecorder(Optional<MeterRegistry> meterRegistryOptional) {
            requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided");
            return setMeterRegistry(meterRegistryOptional);
        }

        /**
         * Builds the MeasurementTaker instance.
         *
         * @return a new MeasurementTaker with the configured recorders
         */
        public MeasurementTaker build() {
            return new MeasurementTaker(recorders);
        }
    }

    /**
     * A fluent builder for constructing a measurement context and recording its execution.
     * <p>
     * Example usage:
     * <pre>
     *     return measurementTaker.context("essentials.eventstore.append_to_stream")
     *                             .description("Time taken to append events to the event store")
     *                             .tag("aggregateType", operation.getAggregateType())
     *                             .record(chain::proceed);
     * </pre>
     * </p>
     */
    public static class FluentMeasurementContext {
        private final MeasurementTaker           measurementTaker;
        private final MeasurementContext.Builder contextBuilder;

        private FluentMeasurementContext(MeasurementTaker measurementTaker, String metricName) {
            this.measurementTaker = requireNonNull(measurementTaker, "No measurementTaker provided");
            this.contextBuilder = MeasurementContext.builder(metricName);
        }

        /**
         * Sets the description for the measurement.
         *
         * @param description the description text
         * @return this FluentMeasurementContext instance for fluent chaining
         */
        public FluentMeasurementContext description(String description) {
            contextBuilder.description(description);
            return this;
        }

        /**
         * Adds a tag to the measurement.
         *
         * @param key   the tag key
         * @param value the tag value
         * @return this FluentMeasurementContext instance for fluent chaining
         */
        public FluentMeasurementContext tag(String key, CharSequence value) {
            contextBuilder.tag(key, value);
            return this;
        }

        /**
         * Adds a tag to the measurement.
         *
         * @param key   the tag key
         * @param value the tag value
         * @return this FluentMeasurementContext instance for fluent chaining
         */
        public FluentMeasurementContext tag(String key, String value) {
            contextBuilder.tag(key, value);
            return this;
        }

        /**
         * Adds a tag to the measurement.
         *
         * @param key   the tag key
         * @param value the tag value
         * @return this FluentMeasurementContext instance for fluent chaining
         */
        public FluentMeasurementContext tag(String key, int value) {
            contextBuilder.tag(key, value);
            return this;
        }

        /**
         * Executes the supplied code block, measuring its execution time using the built measurement context.
         *
         * @param block the code block to execute and measure
         * @param <T>   the type of result returned by the code block
         * @return the result of executing the code block
         */
        public <T> T record(Supplier<T> block) {
            requireNonNull(block, "No block provided");
            return measurementTaker.record(contextBuilder.build(), block);
        }

        /**
         * Record the recorded duration using the built measurement context.
         *
         * @param recordedDuration the recorded duration to use for the measurement
         * @return the measurementTaker instance for fluent chaining
         */
        public FluentMeasurementContext record(Duration recordedDuration) {
            requireNonNull(recordedDuration, "No Duration provided");
            measurementTaker.recordTime(contextBuilder.build(), recordedDuration);
            return this;
        }

        /**
         * Adds an optional tag to the measurement. If the value is null then the tag isn't added
         *
         * @param key   the tag key
         * @param value the tag value
         * @return this FluentMeasurementContext instance for fluent chaining
         */
        public FluentMeasurementContext optionalTag(String key, String value) {
            if (value != null) {
                contextBuilder.optionalTag(key, value);
            }
            return this;
        }
    }
}



