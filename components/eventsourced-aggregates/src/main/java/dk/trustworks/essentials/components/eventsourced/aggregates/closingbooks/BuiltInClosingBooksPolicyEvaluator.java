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
    private static final Logger log = LoggerFactory.getLogger(BuiltInClosingBooksPolicyEvaluator.class);
    private static final String GAP_COUNTER_NAME = "essentials.closing_books.time_boundary_gap_detected";

    private final AggregateType aggregateType;
    private final ClosingBooksDefaultPolicyType defaultPolicy;
    private final long eventThreshold;
    private final ClosingBooksTimeBoundary timeBoundary;
    private final ZoneId zoneId;
    private final Integer intervalDays;
    private final Clock clock;
    private final Optional<MeterRegistry> meterRegistry;
    private final ToLongFunction<AGGREGATE> eventCountProvider;
    private final Function<AGGREGATE, String> currentPeriodIdProvider;

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
        this.currentPeriodIdProvider = requireNonNull(currentPeriodIdProvider, "No currentPeriodIdProvider provided");
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

    private ClosingBooksTimeBoundaryEvaluation timeBoundaryEvaluation(AGGREGATE aggregate) {
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
}
