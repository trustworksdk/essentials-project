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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Reusable evaluator for the framework's built-in closing-books policy types.
 * <p>
 * This class keeps the mechanics of event-count and time-boundary rollover in one place,
 * including gap detection logging and metrics, while allowing application-specific aggregates
 * to supply the functions that expose their current business period and effective event count.
 */
public final class BuiltInClosingBooksPolicyEvaluator<AGGREGATE> {
    private static final Logger log              = LoggerFactory.getLogger(BuiltInClosingBooksPolicyEvaluator.class);
    private static final String GAP_COUNTER_NAME = "essentials.closing_books.time_boundary_gap_detected";

    private final AggregateType                 aggregateType;
    private final ClosingBooksDefaultPolicyType defaultPolicy;
    private final long                          eventThreshold;
    private final ClosingBooksTimeBoundary      timeBoundary;
    private final ZoneId                        zoneId;
    private final Integer                       intervalDays;
    private final Clock                         clock;
    private final Optional<MeterRegistry>       meterRegistry;
    private final ToLongFunction<AGGREGATE>     eventCountProvider;
    private final Function<AGGREGATE, String>   currentPeriodIdProvider;

    /**
     * Constructs a new instance of the {@code BuiltInClosingBooksPolicyEvaluator}.
     *
     * @param aggregateType      the type of the aggregate being processed; must not be null.
     * @param defaultPolicy      the default closing books policy to apply; must not be null.
     * @param eventThreshold     the threshold for the number of events after which a closing book is triggered.
     * @param timeBoundary       the evaluation boundary for time-based closing books; must not be null.
     * @param zoneId             the time zone used for date and time calculations; must not be null.
     * @param intervalDays       the interval in days for evaluating time-based periods; can be null.
     * @param clock              the clock instance used for obtaining the current time; must not be null.
     * @param meterRegistry      an optional meter registry for instrumentation and metrics; must not be null.
     * @param eventCountProvider a function that provides the event count for the given aggregate; must not be null.
     * @throws IllegalArgumentException if any required parameter is null.
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public BuiltInClosingBooksPolicyEvaluator(AggregateType aggregateType,
                                              ClosingBooksDefaultPolicyType defaultPolicy,
                                              long eventThreshold,
                                              ClosingBooksTimeBoundary timeBoundary,
                                              ZoneId zoneId,
                                              Integer intervalDays,
                                              Clock clock,
                                              Optional<MeterRegistry> meterRegistry,
                                              ToLongFunction<AGGREGATE> eventCountProvider) {
        this(aggregateType,
             defaultPolicy,
             eventThreshold,
             timeBoundary,
             zoneId,
             intervalDays,
             clock,
             meterRegistry,
             eventCountProvider,
             (Function<AGGREGATE, String>) null);
    }

    /**
     * Constructs a new instance of the {@code BuiltInClosingBooksPolicyEvaluator}.
     *
     * @param aggregateType             the type of the aggregate being processed; must not be null.
     * @param defaultPolicy             the default closing books policy to apply; must not be null.
     * @param eventThreshold            the threshold for the number of events after which a closing book is triggered.
     * @param timeBoundary              the evaluation boundary for time-based closing books; must not be null.
     * @param zoneId                    the time zone used for date and time calculations; must not be null.
     * @param intervalDays              the interval in days for evaluating time-based periods; can be null.
     * @param clock                     the clock instance used for obtaining the current time; must not be null.
     * @param meterRegistry             an optional meter registry for instrumentation and metrics; must not be null.
     * @param eventCountProvider        a function that provides the event count for the given aggregate; must not be null.
     * @param aggregateTypeWithPeriodId the class type representing aggregates with a closing books period identifier; must not be null.
     * @throws IllegalArgumentException if any required parameter is null.
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public <T extends HasClosingBooksPeriodId> BuiltInClosingBooksPolicyEvaluator(AggregateType aggregateType,
                                                                                  ClosingBooksDefaultPolicyType defaultPolicy,
                                                                                  long eventThreshold,
                                                                                  ClosingBooksTimeBoundary timeBoundary,
                                                                                  ZoneId zoneId,
                                                                                  Integer intervalDays,
                                                                                  Clock clock,
                                                                                  Optional<MeterRegistry> meterRegistry,
                                                                                  ToLongFunction<AGGREGATE> eventCountProvider,
                                                                                  Class<T> aggregateTypeWithPeriodId) {
        this(aggregateType,
             defaultPolicy,
             eventThreshold,
             timeBoundary,
             zoneId,
             intervalDays,
             clock,
             meterRegistry,
             eventCountProvider,
             aggregate -> ((HasClosingBooksPeriodId) aggregate).closingBooksPeriodId());
        requireNonNull(aggregateTypeWithPeriodId, "No aggregateTypeWithPeriodId provided");
    }

    /**
     * Constructs a new instance of the BuiltInClosingBooksPolicyEvaluator.
     *
     * @param aggregateType           the type of the aggregate being processed; must not be null.
     * @param defaultPolicy           the default closing books policy to apply; must not be null.
     * @param eventThreshold          the threshold for the number of events after which a closing book is triggered.
     * @param timeBoundary            the evaluation boundary for time-based closing books; must not be null.
     * @param zoneId                  the time zone used for date and time calculations; must not be null.
     * @param intervalDays            the interval in days for evaluating time-based periods; can be null.
     * @param clock                   the clock instance used for obtaining the current time; must not be null.
     * @param meterRegistry           an optional meter registry for instrumentation and metrics; must not be null.
     * @param eventCountProvider      a function that provides the event count for the given aggregate; must not be null.
     * @param currentPeriodIdProvider a function that provides the current period identifier for the given aggregate;
     *                                required when using a time-boundary closing books policy. May be null otherwise.
     * @throws IllegalArgumentException if any required parameter is null.
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public BuiltInClosingBooksPolicyEvaluator(AggregateType aggregateType,
                                              ClosingBooksDefaultPolicyType defaultPolicy,
                                              long eventThreshold,
                                              ClosingBooksTimeBoundary timeBoundary,
                                              ZoneId zoneId,
                                              Integer intervalDays,
                                              Clock clock,
                                              Optional<MeterRegistry> meterRegistry,
                                              ToLongFunction<AGGREGATE> eventCountProvider,
                                              Function<AGGREGATE, String> currentPeriodIdProvider) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.defaultPolicy = requireNonNull(defaultPolicy, "No defaultPolicy provided");
        this.timeBoundary = requireNonNull(timeBoundary, "No timeBoundary provided");
        this.zoneId = requireNonNull(zoneId, "No zoneId provided");
        this.clock = requireNonNull(clock, "No clock provided");
        this.meterRegistry = requireNonNull(meterRegistry, "No meterRegistry provided");
        this.eventCountProvider = requireNonNull(eventCountProvider, "No eventCountProvider provided");
        this.currentPeriodIdProvider = requiresCurrentPeriodIdProvider(defaultPolicy)
                                       ? requireNonNull(currentPeriodIdProvider, "No currentPeriodIdProvider provided for time-boundary closing-books policy")
                                       : currentPeriodIdProvider;
        this.eventThreshold = eventThreshold;
        this.intervalDays = intervalDays;
    }

    /**
     * Determines whether a rollover should occur based on the provided aggregate and
     * the current default policy.
     * <p>
     * The method evaluates the default policy and uses the provided aggregate to
     * decide if certain thresholds or conditions are met, indicating that a rollover
     * is appropriate.
     *
     * @param aggregate the aggregate instance used to evaluate rollover conditions; must not be null.
     * @return {@code true} if the rollover should occur based on the evaluated criteria, {@code false} otherwise.
     * @throws IllegalArgumentException if the provided aggregate is null.
     */
    public boolean shouldRolloverOnAccess(AGGREGATE aggregate) {
        requireNonNull(aggregate, "No aggregate provided");
        return switch (defaultPolicy) {
            case MANUAL_ONLY, EXPLICIT_ONLY, UNSPECIFIED -> false;
            case EVENT_COUNT -> eventThresholdReached(aggregate);
            case TIME_BOUNDARY -> timeBoundaryEvaluation(aggregate).boundaryAdvanced();
            case EVENT_COUNT_OR_TIME_BOUNDARY -> eventThresholdReached(aggregate) || timeBoundaryEvaluation(aggregate).boundaryAdvanced();
        };
    }

    /**
     * Generates the next period identifier based on the given aggregate's context and the current time boundary evaluation.
     *
     * @param aggregate the aggregate instance used to determine the next period identifier; must not be null.
     * @return the resolved identifier for the next period.
     * @throws IllegalArgumentException if the provided aggregate is null.
     */
    public String nextPeriodId(AGGREGATE aggregate) {
        requireNonNull(aggregate, "No aggregate provided");
        return timeBoundaryEvaluation(aggregate).resolvedPeriodId();
    }

    public String description() {
        return switch (defaultPolicy) {
            case MANUAL_ONLY -> "manual-only";
            case EVENT_COUNT -> "event-count threshold " + eventThreshold;
            case TIME_BOUNDARY -> "time-boundary " + timeBoundary.name().toLowerCase().replace('_', '-') + " in zone " + zoneId;
            case EVENT_COUNT_OR_TIME_BOUNDARY -> "event-count threshold " + eventThreshold
                    + " or time-boundary " + timeBoundary.name().toLowerCase().replace('_', '-') + " in zone " + zoneId;
            case EXPLICIT_ONLY -> "explicit-only";
            case UNSPECIFIED -> "unspecified";
        };
    }

    public <ID> ClosingBooksDecisionPolicy<ID, AGGREGATE> asDecisionPolicy() {
        return context -> context.triggerMode() == ClosingBooksTriggerMode.ON_ACCESS && shouldRolloverOnAccess(context.aggregate())
                          ? ClosingBooksDecision.CLOSE_AND_OPEN_NEXT
                          : ClosingBooksDecision.KEEP_OPEN;
    }

    private boolean eventThresholdReached(AGGREGATE aggregate) {
        return eventThreshold > 0 && eventCountProvider.applyAsLong(aggregate) >= eventThreshold;
    }

    private static boolean requiresCurrentPeriodIdProvider(ClosingBooksDefaultPolicyType defaultPolicy) {
        return switch (defaultPolicy) {
            case TIME_BOUNDARY, EVENT_COUNT_OR_TIME_BOUNDARY -> true;
            case MANUAL_ONLY, EVENT_COUNT, EXPLICIT_ONLY, UNSPECIFIED -> false;
        };
    }

    private Function<AGGREGATE, String> currentPeriodIdProvider() {
        if (currentPeriodIdProvider == null) {
            throw new IllegalStateException("Current period id is only available when a time-boundary closing-books policy is configured with a currentPeriodIdProvider or aggregates implement HasClosingBooksPeriodId");
        }
        return currentPeriodIdProvider;
    }

    private ClosingBooksTimeBoundaryEvaluation timeBoundaryEvaluation(AGGREGATE aggregate) {
        var currentPeriodIdProvider = currentPeriodIdProvider();
        if (timeBoundary == ClosingBooksTimeBoundary.NONE) {
            return new ClosingBooksTimeBoundaryEvaluation(currentPeriodIdProvider.apply(aggregate), 0);
        }

        var currentPeriodId = currentPeriodIdProvider.apply(aggregate);
        var evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(timeBoundary,
                                                                     zoneId,
                                                                     clock,
                                                                     currentPeriodId,
                                                                     intervalDays);
        if (evaluation.gapDetected()) {
            log.info("Detected closing-books time-boundary gap for aggregate type '{}': currentPeriod='{}', resolvedPeriod='{}', advancedPeriods={}, boundary={}, zoneId={}",
                     aggregateType,
                     currentPeriodId,
                     evaluation.resolvedPeriodId(),
                     evaluation.advancedPeriods(),
                     timeBoundary,
                     zoneId);
            meterRegistry.ifPresent(registry -> registry.counter(GAP_COUNTER_NAME,
                                                                 "aggregate_type", aggregateType.toString(),
                                                                 "time_boundary", timeBoundary.name(),
                                                                 "policy_type", defaultPolicy.name())
                                                        .increment());
        }
        return evaluation;
    }

    /**
     * Creates a builder for a {@link BuiltInClosingBooksPolicyEvaluator}.
     *
     * @param <AGGREGATE> the aggregate id type
     * @return a new builder
     */
    public static <AGGREGATE> Builder<AGGREGATE> builder() {
        return new Builder<>();
    }

    /**
     * Builder for {@link BuiltInClosingBooksPolicyEvaluator}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload.
     */
    public static final class Builder<AGGREGATE> {
        private AggregateType aggregateType;
        private ClosingBooksDefaultPolicyType defaultPolicy;
        private long eventThreshold;
        private ClosingBooksTimeBoundary timeBoundary;
        private ZoneId zoneId;
        private Integer intervalDays;
        private Clock clock;
        private MeterRegistry meterRegistry;
        private ToLongFunction<AGGREGATE> eventCountProvider;
        private Function<AGGREGATE, String> currentPeriodIdProvider;

        /**
         * @param aggregateType required
         * @return this builder
         */
        public Builder<AGGREGATE> setAggregateType(AggregateType aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        /**
         * @param defaultPolicy required
         * @return this builder
         */
        public Builder<AGGREGATE> setDefaultPolicy(ClosingBooksDefaultPolicyType defaultPolicy) {
            this.defaultPolicy = defaultPolicy;
            return this;
        }

        /**
         * @param eventThreshold required
         * @return this builder
         */
        public Builder<AGGREGATE> setEventThreshold(long eventThreshold) {
            this.eventThreshold = eventThreshold;
            return this;
        }

        /**
         * @param timeBoundary required
         * @return this builder
         */
        public Builder<AGGREGATE> setTimeBoundary(ClosingBooksTimeBoundary timeBoundary) {
            this.timeBoundary = timeBoundary;
            return this;
        }

        /**
         * @param zoneId required
         * @return this builder
         */
        public Builder<AGGREGATE> setZoneId(ZoneId zoneId) {
            this.zoneId = zoneId;
            return this;
        }

        /**
         * @param intervalDays required
         * @return this builder
         */
        public Builder<AGGREGATE> setIntervalDays(Integer intervalDays) {
            this.intervalDays = intervalDays;
            return this;
        }

        /**
         * @param clock required
         * @return this builder
         */
        public Builder<AGGREGATE> setClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * @param meterRegistry optional — {@code null} selects the default
         * @return this builder
         */
        public Builder<AGGREGATE> setMeterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry}.
         *
         * @param meterRegistry the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder<AGGREGATE> setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
            requireNonNull(meterRegistry, "No meterRegistry provided");
            return setMeterRegistry(meterRegistry.orElse(null));
        }

        /**
         * @param eventCountProvider required
         * @return this builder
         */
        public Builder<AGGREGATE> setEventCountProvider(ToLongFunction<AGGREGATE> eventCountProvider) {
            this.eventCountProvider = eventCountProvider;
            return this;
        }

        /**
         * @param currentPeriodIdProvider required
         * @return this builder
         */
        public Builder<AGGREGATE> setCurrentPeriodIdProvider(Function<AGGREGATE, String> currentPeriodIdProvider) {
            this.currentPeriodIdProvider = currentPeriodIdProvider;
            return this;
        }

        /**
         * @return the new {@link BuiltInClosingBooksPolicyEvaluator}
         */
        @SuppressWarnings("removal")
        public BuiltInClosingBooksPolicyEvaluator<AGGREGATE> build() {
            return new BuiltInClosingBooksPolicyEvaluator<>(aggregateType,
                                                                     defaultPolicy,
                                                                     eventThreshold,
                                                                     timeBoundary,
                                                                     zoneId,
                                                                     intervalDays,
                                                                     clock,
                                                                     Optional.ofNullable(meterRegistry),
                                                                     eventCountProvider,
                                                                     currentPeriodIdProvider);
        }
    }

}
