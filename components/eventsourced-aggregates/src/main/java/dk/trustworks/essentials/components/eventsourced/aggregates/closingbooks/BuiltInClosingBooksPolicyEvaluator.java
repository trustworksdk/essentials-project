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
    private static final String UNKNOWN_BUSINESS_DATE_COUNTER_NAME = "essentials.closing_books.business_date_unknown";
    private static final ZoneId UNUSED_ZONE_ID = ZoneId.of("UTC");

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
    private final ClosingBooksBusinessCalendar  businessCalendar;
    private volatile boolean                    businessDateUnknown;

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
     */
    BuiltInClosingBooksPolicyEvaluator(AggregateType aggregateType,
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
     */
    <T extends HasClosingBooksPeriodId> BuiltInClosingBooksPolicyEvaluator(AggregateType aggregateType,
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
     */
    BuiltInClosingBooksPolicyEvaluator(AggregateType aggregateType,
                                              ClosingBooksDefaultPolicyType defaultPolicy,
                                              long eventThreshold,
                                              ClosingBooksTimeBoundary timeBoundary,
                                              ZoneId zoneId,
                                              Integer intervalDays,
                                              Clock clock,
                                              Optional<MeterRegistry> meterRegistry,
                                              ToLongFunction<AGGREGATE> eventCountProvider,
                                              Function<AGGREGATE, String> currentPeriodIdProvider) {
        this(aggregateType,
             defaultPolicy,
             eventThreshold,
             timeBoundary,
             zoneId,
             intervalDays,
             clock,
             meterRegistry,
             eventCountProvider,
             currentPeriodIdProvider,
             null);
    }

    /**
     * Constructs an evaluator whose {@link ClosingBooksTimeBoundary#END_OF_DAY} boundary may take its days from a
     * business calendar ({@link ClosingBooksPeriodSource#BUSINESS_CALENDAR}).
     *
     * @param businessCalendar the business calendar, or {@code null} to evaluate on {@code clock} in {@code zoneId}.
     *                         When set, {@code zoneId} and {@code clock} are not used and may be null, and
     *                         {@code timeBoundary} must be {@code END_OF_DAY} - except that a policy other than
     *                         {@code TIME_BOUNDARY} or {@code EVENT_COUNT_OR_TIME_BOUNDARY} may keep boundary
     *                         {@code NONE}, where the calendar is never read
     */
    BuiltInClosingBooksPolicyEvaluator(AggregateType aggregateType,
                                       ClosingBooksDefaultPolicyType defaultPolicy,
                                       long eventThreshold,
                                       ClosingBooksTimeBoundary timeBoundary,
                                       ZoneId zoneId,
                                       Integer intervalDays,
                                       Clock clock,
                                       Optional<MeterRegistry> meterRegistry,
                                       ToLongFunction<AGGREGATE> eventCountProvider,
                                       Function<AGGREGATE, String> currentPeriodIdProvider,
                                       ClosingBooksBusinessCalendar businessCalendar) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.defaultPolicy = requireNonNull(defaultPolicy, "No defaultPolicy provided");
        this.timeBoundary = requireNonNull(timeBoundary, "No timeBoundary provided");
        this.businessCalendar = businessCalendar;
        // The calendar is read wherever the boundary is evaluated (shouldRolloverOnAccess for a time-boundary policy,
        // nextPeriodId for every policy) unless the boundary is NONE. Only a policy that is not a time-boundary one
        // may keep a NONE boundary, so only there is a calendar never read and its boundary left unchecked
        if (businessCalendar != null && !businessCalendarIsNeverRead(defaultPolicy, timeBoundary) &&
                timeBoundary != ClosingBooksTimeBoundary.END_OF_DAY) {
            throw new IllegalArgumentException("A business calendar can only be used with time boundary END_OF_DAY, not " + timeBoundary);
        }
        this.zoneId = businessCalendar != null && zoneId == null ? UNUSED_ZONE_ID : requireNonNull(zoneId, "No zoneId provided");
        this.clock = businessCalendar != null && clock == null ? Clock.systemUTC() : requireNonNull(clock, "No clock provided");
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
            case TIME_BOUNDARY -> timeBoundaryEvaluation(aggregate, true).boundaryAdvanced();
            // The boundary is always evaluated, so a gap is reported even when the event count alone rolls the books
            case EVENT_COUNT_OR_TIME_BOUNDARY -> timeBoundaryEvaluation(aggregate, true).boundaryAdvanced() | eventThresholdReached(aggregate);
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
        // Not reported again: the rollover decision that precedes this call already logged and counted any gap
        return timeBoundaryEvaluation(aggregate, false).resolvedPeriodId();
    }

    public String description() {
        return switch (defaultPolicy) {
            case MANUAL_ONLY -> "manual-only";
            case EVENT_COUNT -> "event-count threshold " + eventThreshold;
            case TIME_BOUNDARY -> timeBoundaryDescription();
            case EVENT_COUNT_OR_TIME_BOUNDARY -> "event-count threshold " + eventThreshold + " or " + timeBoundaryDescription();
            case EXPLICIT_ONLY -> "explicit-only";
            case UNSPECIFIED -> "unspecified";
        };
    }

    /**
     * @return {@link ClosingBooksPeriodSource#BUSINESS_CALENDAR} when a business calendar is set, else
     * {@link ClosingBooksPeriodSource#CLOCK}
     */
    public ClosingBooksPeriodSource periodSource() {
        return businessCalendar != null ? ClosingBooksPeriodSource.BUSINESS_CALENDAR : ClosingBooksPeriodSource.CLOCK;
    }

    private String timeBoundaryDescription() {
        var boundary = "time-boundary " + timeBoundary.name().toLowerCase().replace('_', '-');
        return businessCalendar != null ? boundary + " on business calendar" : boundary + " in zone " + zoneId;
    }

    /**
     * Like {@link #asDecisionPolicy()}, rolling on the given trigger modes - typically
     * {@code ON_ACCESS, SCHEDULED_SCAN}, so a scan rolls an idle aggregate whose boundary has passed (EF-1b).
     * <p>
     * Roll on a scan only with a {@link ClosingBooksScanRollover} on the scan processor: without one a scan rollover
     * opens the next generation with an empty stream - no closing event, no carried-forward state, no period id.
     *
     * @param triggerMode  a trigger mode to roll on
     * @param triggerModes further trigger modes to roll on
     * @return the decision policy
     */
    public <ID> ClosingBooksDecisionPolicy<ID, AGGREGATE> asDecisionPolicyRollingOn(ClosingBooksTriggerMode triggerMode,
                                                                                   ClosingBooksTriggerMode... triggerModes) {
        requireNonNull(triggerMode, "No triggerMode provided");
        var modes = java.util.EnumSet.of(triggerMode, triggerModes);
        return context -> modes.contains(context.triggerMode()) && shouldRolloverOnAccess(context.aggregate())
                          ? ClosingBooksDecision.CLOSE_AND_OPEN_NEXT
                          : ClosingBooksDecision.KEEP_OPEN;
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
        return usesTimeBoundary(defaultPolicy);
    }

    private static boolean businessCalendarIsNeverRead(ClosingBooksDefaultPolicyType defaultPolicy, ClosingBooksTimeBoundary timeBoundary) {
        return !usesTimeBoundary(defaultPolicy) && timeBoundary == ClosingBooksTimeBoundary.NONE;
    }

    private static boolean usesTimeBoundary(ClosingBooksDefaultPolicyType defaultPolicy) {
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

    /**
     * @param report whether to log and count a gap or an unknown business date - once per decision, from
     *               {@link #shouldRolloverOnAccess}, not again from {@link #nextPeriodId}
     */
    private ClosingBooksTimeBoundaryEvaluation timeBoundaryEvaluation(AGGREGATE aggregate, boolean report) {
        var currentPeriodIdProvider = currentPeriodIdProvider();
        if (timeBoundary == ClosingBooksTimeBoundary.NONE) {
            return new ClosingBooksTimeBoundaryEvaluation(currentPeriodIdProvider.apply(aggregate), 0);
        }

        var currentPeriodId = currentPeriodIdProvider.apply(aggregate);
        ClosingBooksTimeBoundaryEvaluation evaluation;
        if (businessCalendar != null) {
            if (report) {
                noteWhetherBusinessDateIsKnown();
            }
            evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(businessCalendar, currentPeriodId);
        } else {
            evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(timeBoundary,
                                                                     zoneId,
                                                                     clock,
                                                                     currentPeriodId,
                                                                     intervalDays);
        }
        if (report && evaluation.gapDetected()) {
            log.info("Detected closing-books time-boundary gap for aggregate type '{}': currentPeriod='{}', resolvedPeriod='{}', advancedPeriods={}, boundary={}, {}",
                     aggregateType,
                     currentPeriodId,
                     evaluation.resolvedPeriodId(),
                     evaluation.advancedPeriods(),
                     timeBoundary,
                     businessCalendar != null ? "on business calendar" : "zoneId=" + zoneId);
            meterRegistry.ifPresent(registry -> registry.counter(GAP_COUNTER_NAME,
                                                                 "aggregate_type", aggregateType.toString(),
                                                                 "time_boundary", timeBoundary.name(),
                                                                 "policy_type", defaultPolicy.name(),
                                                                 "period_source", periodSource().name())
                                                        .increment());
        }
        return evaluation;
    }

    /**
     * An unknown business date never rolls the books. It is counted every time and logged at WARN once each time the
     * calendar stops knowing it (normally only before the calendar is loaded on start-up), at DEBUG otherwise.
     */
    private void noteWhetherBusinessDateIsKnown() {
        if (businessCalendar.currentBusinessDate().isPresent()) {
            if (businessDateUnknown) {
                businessDateUnknown = false;
                log.info("Closing-books business calendar for aggregate type '{}' knows the open business date again", aggregateType);
            }
            return;
        }
        if (!businessDateUnknown) {
            businessDateUnknown = true;
            log.warn("Closing-books business calendar for aggregate type '{}' does not know the open business date: no books roll until it does", aggregateType);
        } else {
            log.debug("Closing-books business calendar for aggregate type '{}' still does not know the open business date", aggregateType);
        }
        meterRegistry.ifPresent(registry -> registry.counter(UNKNOWN_BUSINESS_DATE_COUNTER_NAME,
                                                             "aggregate_type", aggregateType.toString())
                                                    .increment());
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
        private ClosingBooksBusinessCalendar businessCalendar;

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
         * @param zoneId required, unless a business calendar is set ({@link #setBusinessCalendar})
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
         * @param clock required, unless a business calendar is set ({@link #setBusinessCalendar})
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
         * Takes the days of an {@link ClosingBooksTimeBoundary#END_OF_DAY} boundary from a business calendar instead of
         * the clock ({@link ClosingBooksPeriodSource#BUSINESS_CALENDAR}): the books roll when the calendar records that
         * a business day closed. With a calendar, {@link #setClock(Clock)} and {@link #setZoneId(ZoneId)} are not used
         * and may be left unset. Any boundary other than {@code END_OF_DAY} is rejected by {@link #build()}, except
         * boundary {@code NONE} for a policy other than {@code TIME_BOUNDARY} or {@code EVENT_COUNT_OR_TIME_BOUNDARY},
         * which never reads the calendar.
         *
         * @param businessCalendar optional - {@code null} evaluates on the clock
         * @return this builder
         */
        public Builder<AGGREGATE> setBusinessCalendar(ClosingBooksBusinessCalendar businessCalendar) {
            this.businessCalendar = businessCalendar;
            return this;
        }

        /**
         * @return the new {@link BuiltInClosingBooksPolicyEvaluator}
         * @throws IllegalArgumentException if a business calendar is set with a boundary other than {@code END_OF_DAY},
         *                                  unless the boundary is {@code NONE} and the policy is neither
         *                                  {@code TIME_BOUNDARY} nor {@code EVENT_COUNT_OR_TIME_BOUNDARY}
         */
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
                                                                     currentPeriodIdProvider,
                                                                     businessCalendar);
        }
    }

}
