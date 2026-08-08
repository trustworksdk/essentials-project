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

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClosingBooksTimeBoundaryCalculatorTest {
    @Test
    void resolves_month_period_from_clock_and_zone() {
        var clock = Clock.fixed(Instant.parse("2026-04-05T10:15:30Z"), ZoneOffset.UTC);

        assertThat(ClosingBooksTimeBoundaryCalculator.resolveCurrentPeriodId(ClosingBooksTimeBoundary.END_OF_MONTH,
                                                                             ZoneId.of("Europe/Copenhagen"),
                                                                             clock,
                                                                             "2026-03",
                                                                             null)).isEqualTo("2026-04");
    }

    @Test
    void fixed_interval_skips_gaps_using_anchor_period() {
        var clock = Clock.fixed(Instant.parse("2026-04-20T10:15:30Z"), ZoneOffset.UTC);

        assertThat(ClosingBooksTimeBoundaryCalculator.resolveCurrentPeriodId(ClosingBooksTimeBoundary.EVERY_N_DAYS,
                                                                             ZoneId.of("UTC"),
                                                                             clock,
                                                                             "2026-04-01",
                                                                             7)).isEqualTo("2026-04-15");
    }

    @Test
    void rejects_invalid_month_period_id() {
        var clock = Clock.fixed(Instant.parse("2026-04-05T10:15:30Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> ClosingBooksTimeBoundaryCalculator.resolveCurrentPeriodId(ClosingBooksTimeBoundary.END_OF_MONTH,
                                                                                           ZoneId.of("UTC"),
                                                                                           clock,
                                                                                           "2026-4",
                                                                                           null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid currentPeriodId '2026-4'")
                .hasMessageContaining("Expected format: 'yyyy-MM'");
    }

    @Test
    void rejects_invalid_week_period_id() {
        var clock = Clock.fixed(Instant.parse("2026-04-05T10:15:30Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> ClosingBooksTimeBoundaryCalculator.resolveCurrentPeriodId(ClosingBooksTimeBoundary.END_OF_WEEK,
                                                                                           ZoneId.of("UTC"),
                                                                                           clock,
                                                                                           "2026-W60",
                                                                                           null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid currentPeriodId '2026-W60'")
                .hasMessageContaining("Expected format: 'yyyy-Www'");
    }

    @Test
    void rejects_blank_fixed_interval_anchor_period_id() {
        var clock = Clock.fixed(Instant.parse("2026-04-20T10:15:30Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> ClosingBooksTimeBoundaryCalculator.resolveCurrentPeriodId(ClosingBooksTimeBoundary.EVERY_N_DAYS,
                                                                                           ZoneId.of("UTC"),
                                                                                           clock,
                                                                                           " ",
                                                                                           7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected format: 'yyyy-MM-dd'");
    }

    @Test
    void current_period_id_is_produced_in_the_format_each_boundary_expects() {
        // A new aggregate has no period id yet, so it cannot go through resolveCurrentPeriodId. Its seed value
        // still has to match the boundary's format, which is what this exists to guarantee.
        var clock = Clock.fixed(Instant.parse("2026-08-08T10:15:30Z"), ZoneOffset.UTC);
        var zone  = ZoneOffset.UTC;

        assertThat(ClosingBooksTimeBoundaryCalculator.currentPeriodId(ClosingBooksTimeBoundary.END_OF_DAY, zone, clock, null))
                .isEqualTo("2026-08-08");
        assertThat(ClosingBooksTimeBoundaryCalculator.currentPeriodId(ClosingBooksTimeBoundary.END_OF_MONTH, zone, clock, null))
                .isEqualTo("2026-08");
        assertThat(ClosingBooksTimeBoundaryCalculator.currentPeriodId(ClosingBooksTimeBoundary.END_OF_YEAR, zone, clock, null))
                .isEqualTo("2026");
        assertThat(ClosingBooksTimeBoundaryCalculator.currentPeriodId(ClosingBooksTimeBoundary.END_OF_WEEK, zone, clock, null))
                .isEqualTo("2026-W32");
        assertThat(ClosingBooksTimeBoundaryCalculator.currentPeriodId(ClosingBooksTimeBoundary.EVERY_N_DAYS, zone, clock, 7))
                .isEqualTo("2026-08-08");
        assertThat(ClosingBooksTimeBoundaryCalculator.currentPeriodId(ClosingBooksTimeBoundary.NONE, zone, clock, null))
                .isNull();
    }

    @Test
    void a_current_period_id_evaluates_as_not_advanced() {
        // The round trip that matters: seeding an aggregate with currentPeriodId must not immediately look like a
        // skipped period to the evaluator. The old demo seeded a hardcoded literal and tripped gap detection.
        var clock = Clock.fixed(Instant.parse("2026-08-08T10:15:30Z"), ZoneOffset.UTC);
        var zone  = ZoneOffset.UTC;

        for (var boundary : new ClosingBooksTimeBoundary[]{ClosingBooksTimeBoundary.END_OF_DAY,
                                                            ClosingBooksTimeBoundary.END_OF_WEEK,
                                                            ClosingBooksTimeBoundary.END_OF_MONTH,
                                                            ClosingBooksTimeBoundary.END_OF_YEAR}) {
            var seed       = ClosingBooksTimeBoundaryCalculator.currentPeriodId(boundary, zone, clock, null);
            var evaluation = ClosingBooksTimeBoundaryCalculator.evaluate(boundary, zone, clock, seed, null);

            assertThat(evaluation.boundaryAdvanced()).as("boundary %s should not have advanced", boundary).isFalse();
            assertThat(evaluation.gapDetected()).as("boundary %s should not report a gap", boundary).isFalse();
            assertThat(evaluation.resolvedPeriodId()).isEqualTo(seed);
        }
    }
}
