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
}
