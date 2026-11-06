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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link ClosingBooksPeriodSource#BUSINESS_CALENDAR} path of {@link ClosingBooksTimeBoundaryCalculator} and
 * {@link BuiltInClosingBooksPolicyEvaluator}.
 */
class BusinessCalendarClosingBooksTest {
    // 2030-01-07 is a Monday
    private static final LocalDate MON      = LocalDate.parse("2030-01-07");
    private static final LocalDate WED      = MON.plusDays(2);
    private static final LocalDate THU      = MON.plusDays(3);
    private static final LocalDate FRI      = MON.plusDays(4);
    private static final LocalDate NEXT_MON = MON.plusDays(7);

    private static InMemoryClosingBooksBusinessCalendar weekClosedThrough(LocalDate lastClosed) {
        var calendar = new InMemoryClosingBooksBusinessCalendar();
        for (var day = MON; !day.isAfter(lastClosed); day = day.plusDays(1)) {
            var next = day.getDayOfWeek() == java.time.DayOfWeek.FRIDAY ? day.plusDays(3) : day.plusDays(1);
            calendar.recordClose(day, next);
            if (day.getDayOfWeek() == java.time.DayOfWeek.FRIDAY) {
                break;
            }
        }
        return calendar;
    }

    @Test
    void a_new_aggregate_starts_in_the_open_business_date() {
        assertThat(ClosingBooksTimeBoundaryCalculator.currentPeriodId(weekClosedThrough(FRI))).contains("2030-01-14");
        assertThat(ClosingBooksTimeBoundaryCalculator.currentPeriodId(new InMemoryClosingBooksBusinessCalendar())).isEmpty();
    }

    @Test
    void the_same_business_day_does_not_advance() {
        var evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(weekClosedThrough(WED), THU.toString());

        assertThat(evaluation.advancedPeriods()).isZero();
        assertThat(evaluation.resolvedPeriodId()).isEqualTo("2030-01-10");
    }

    @Test
    void friday_to_monday_is_one_business_day_not_three() {
        var evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(weekClosedThrough(FRI), FRI.toString());

        assertThat(evaluation.advancedPeriods()).isEqualTo(1);
        assertThat(evaluation.gapDetected()).isFalse();
        assertThat(evaluation.resolvedPeriodId()).isEqualTo("2030-01-14");
    }

    @Test
    void wednesday_to_monday_is_a_gap_of_three_business_days() {
        var evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(weekClosedThrough(FRI), WED.toString());

        assertThat(evaluation.advancedPeriods()).isEqualTo(3);
        assertThat(evaluation.gapDetected()).isTrue();
    }

    @Test
    void a_period_that_is_not_a_business_day_counts_every_close_since() {
        var saturday = MON.plusDays(5).toString();

        assertThat(ClosingBooksTimeBoundaryCalculator.evaluate(weekClosedThrough(FRI), saturday).advancedPeriods()).isEqualTo(1);
    }

    @Test
    void a_calendar_behind_the_aggregate_never_moves_its_period_back() {
        var evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(weekClosedThrough(MON), NEXT_MON.toString());

        assertThat(evaluation.advancedPeriods()).isZero();
        assertThat(evaluation.resolvedPeriodId()).isEqualTo("2030-01-14");
    }

    @Test
    void a_later_business_date_advances_even_when_its_closes_are_missing() {
        var calendar = new InMemoryClosingBooksBusinessCalendar();
        calendar.recordClose(FRI, NEXT_MON);

        var evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(calendar, MON.minusDays(7).toString());

        assertThat(evaluation.advancedPeriods()).isEqualTo(1);
        assertThat(evaluation.resolvedPeriodId()).isEqualTo("2030-01-14");
    }

    @Test
    void an_unknown_business_date_never_advances() {
        var evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(new InMemoryClosingBooksBusinessCalendar(), MON.toString());

        assertThat(evaluation.advancedPeriods()).isZero();
        assertThat(evaluation.resolvedPeriodId()).isEqualTo("2030-01-07");
    }

    @Test
    void a_malformed_period_id_is_rejected() {
        assertThatThrownBy(() -> ClosingBooksTimeBoundaryCalculator.evaluate(weekClosedThrough(FRI), "2030-01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_evaluator_rolls_on_the_business_calendar_without_a_clock_or_zone() {
        var calendar  = weekClosedThrough(WED);
        var evaluator = evaluator(calendar, ClosingBooksTimeBoundary.END_OF_DAY, null);

        assertThat(evaluator.periodSource()).isEqualTo(ClosingBooksPeriodSource.BUSINESS_CALENDAR);
        assertThat(evaluator.shouldRolloverOnAccess(new TestAggregate(THU.toString()))).isFalse();
        assertThat(evaluator.shouldRolloverOnAccess(new TestAggregate(WED.toString()))).isTrue();
        assertThat(evaluator.nextPeriodId(new TestAggregate(WED.toString()))).isEqualTo("2030-01-10");
        assertThat(evaluator.description()).isEqualTo("time-boundary end-of-day on business calendar");
    }

    @Test
    void the_evaluator_counts_gaps_and_unknown_dates_with_the_period_source() {
        var registry = new SimpleMeterRegistry();
        var empty    = new InMemoryClosingBooksBusinessCalendar();
        var unknown  = evaluator(empty, ClosingBooksTimeBoundary.END_OF_DAY, registry);

        assertThat(unknown.shouldRolloverOnAccess(new TestAggregate(MON.toString()))).isFalse();
        assertThat(unknown.shouldRolloverOnAccess(new TestAggregate(MON.toString()))).isFalse();
        assertThat(registry.get("essentials.closing_books.business_date_unknown").tag("aggregate_type", "Accounts").counter().count())
                .isEqualTo(2.0);

        var known = evaluator(weekClosedThrough(FRI), ClosingBooksTimeBoundary.END_OF_DAY, registry);
        assertThat(known.shouldRolloverOnAccess(new TestAggregate(WED.toString()))).isTrue();
        assertThat(registry.get("essentials.closing_books.time_boundary_gap_detected")
                           .tag("period_source", "BUSINESS_CALENDAR")
                           .counter()
                           .count()).isEqualTo(1.0);
    }

    @Test
    void the_evaluator_rejects_a_business_calendar_with_any_boundary_but_end_of_day() {
        assertThatThrownBy(() -> evaluator(weekClosedThrough(FRI), ClosingBooksTimeBoundary.END_OF_MONTH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("END_OF_DAY");
    }

    @Test
    void an_event_count_evaluator_with_no_boundary_accepts_a_business_calendar_it_never_reads() {
        var evaluator = eventCountEvaluator(weekClosedThrough(FRI), ClosingBooksTimeBoundary.NONE);

        assertThat(evaluator.periodSource()).isEqualTo(ClosingBooksPeriodSource.BUSINESS_CALENDAR);
        assertThat(evaluator.shouldRolloverOnAccess(new TestAggregate(MON.toString()))).isTrue();
        assertThat(evaluator.nextPeriodId(new TestAggregate(MON.toString()))).isEqualTo(MON.toString());
        assertThat(evaluator.description()).isEqualTo("event-count threshold 2");
    }

    @Test
    void an_event_count_evaluator_with_an_end_of_day_boundary_takes_its_next_period_id_from_the_business_calendar() {
        var evaluator = eventCountEvaluator(weekClosedThrough(WED), ClosingBooksTimeBoundary.END_OF_DAY);

        assertThat(evaluator.periodSource()).isEqualTo(ClosingBooksPeriodSource.BUSINESS_CALENDAR);
        assertThat(evaluator.nextPeriodId(new TestAggregate(WED.toString()))).isEqualTo("2030-01-10");
    }

    @Test
    void an_event_count_evaluator_rejects_a_business_calendar_with_a_boundary_other_than_none_or_end_of_day() {
        assertThatThrownBy(() -> eventCountEvaluator(weekClosedThrough(FRI), ClosingBooksTimeBoundary.END_OF_MONTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("END_OF_DAY");
    }

    private static BuiltInClosingBooksPolicyEvaluator<TestAggregate> eventCountEvaluator(ClosingBooksBusinessCalendar calendar,
                                                                                         ClosingBooksTimeBoundary boundary) {
        return BuiltInClosingBooksPolicyEvaluator.<TestAggregate>builder()
                                                 .setAggregateType(AggregateType.of("Accounts"))
                                                 .setDefaultPolicy(ClosingBooksDefaultPolicyType.EVENT_COUNT)
                                                 .setEventThreshold(2)
                                                 .setTimeBoundary(boundary)
                                                 .setBusinessCalendar(calendar)
                                                 .setEventCountProvider(aggregate -> 2)
                                                 .setCurrentPeriodIdProvider(TestAggregate::periodId)
                                                 .build();
    }

    @Test
    void without_a_business_calendar_the_evaluator_still_needs_a_clock() {
        assertThatThrownBy(() -> evaluator(null, ClosingBooksTimeBoundary.END_OF_DAY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_policy_rolling_on_scans_rolls_an_idle_aggregate_the_plain_one_keeps_it_open() {
        var evaluator = evaluator(weekClosedThrough(WED), ClosingBooksTimeBoundary.END_OF_DAY, null);
        var idle      = new TestAggregate(WED.toString());

        assertThat(evaluator.<String>asDecisionPolicy().decide(context(idle, ClosingBooksTriggerMode.SCHEDULED_SCAN)))
                .as("unchanged: a scan never rolled with the plain policy").isEqualTo(ClosingBooksDecision.KEEP_OPEN);
        var rollingOnScans = evaluator.<String>asDecisionPolicyRollingOn(ClosingBooksTriggerMode.ON_ACCESS, ClosingBooksTriggerMode.SCHEDULED_SCAN);
        assertThat(rollingOnScans.decide(context(idle, ClosingBooksTriggerMode.SCHEDULED_SCAN))).isEqualTo(ClosingBooksDecision.CLOSE_AND_OPEN_NEXT);
        assertThat(rollingOnScans.decide(context(idle, ClosingBooksTriggerMode.ON_ACCESS))).isEqualTo(ClosingBooksDecision.CLOSE_AND_OPEN_NEXT);
        assertThat(rollingOnScans.decide(context(new TestAggregate(THU.toString()), ClosingBooksTriggerMode.SCHEDULED_SCAN)))
                .as("the open business date: nothing to roll").isEqualTo(ClosingBooksDecision.KEEP_OPEN);
    }

    private static ClosingBooksEvaluationContext<String, TestAggregate> context(TestAggregate aggregate, ClosingBooksTriggerMode triggerMode) {
        var logicalId = new LogicalAggregateId<>("A-1");
        return new ClosingBooksEvaluationContext<>(AggregateType.of("Accounts"),
                                                   logicalId,
                                                   new AggregateGeneration<>(AggregateType.of("Accounts"), logicalId, 1, "A-1#1", GenerationState.OPEN, java.time.OffsetDateTime.now(), java.util.Optional.empty()),
                                                   aggregate,
                                                   triggerMode,
                                                   java.time.OffsetDateTime.now());
    }

    private static BuiltInClosingBooksPolicyEvaluator<TestAggregate> evaluator(ClosingBooksBusinessCalendar calendar,
                                                                               ClosingBooksTimeBoundary boundary,
                                                                               SimpleMeterRegistry registry) {
        return BuiltInClosingBooksPolicyEvaluator.<TestAggregate>builder()
                                                 .setAggregateType(AggregateType.of("Accounts"))
                                                 .setDefaultPolicy(ClosingBooksDefaultPolicyType.TIME_BOUNDARY)
                                                 .setTimeBoundary(boundary)
                                                 .setBusinessCalendar(calendar)
                                                 .setMeterRegistry(registry)
                                                 .setEventCountProvider(aggregate -> 0)
                                                 .setCurrentPeriodIdProvider(TestAggregate::periodId)
                                                 .build();
    }

    private record TestAggregate(String periodId) {
    }
}
