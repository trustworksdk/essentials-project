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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryClosingBooksBusinessCalendarTest {
    // 2030-01-07 is a Monday
    private static final LocalDate MON = LocalDate.parse("2030-01-07");
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate THU = MON.plusDays(3);
    private static final LocalDate FRI = MON.plusDays(4);
    private static final LocalDate SAT = MON.plusDays(5);
    private static final LocalDate NEXT_MON = MON.plusDays(7);

    @Test
    void an_empty_calendar_does_not_know_the_business_date() {
        var calendar = new InMemoryClosingBooksBusinessCalendar();

        assertThat(calendar.currentBusinessDate()).isEmpty();
        assertThat(calendar.latestClosedBusinessDate()).isEmpty();
        assertThat(calendar.businessDaysClosedBetween(MON, FRI)).isZero();
    }

    @Test
    void the_current_business_date_is_the_one_the_latest_close_opened() {
        var calendar = new InMemoryClosingBooksBusinessCalendar();

        assertThat(calendar.recordClose(THU, FRI)).isTrue();
        assertThat(calendar.recordClose(FRI, NEXT_MON)).isTrue();

        assertThat(calendar.currentBusinessDate()).contains(NEXT_MON);
        assertThat(calendar.latestClosedBusinessDate()).contains(FRI);
    }

    @Test
    void recording_the_same_close_again_changes_nothing() {
        var calendar = new InMemoryClosingBooksBusinessCalendar();
        calendar.recordClose(MON, TUE);

        assertThat(calendar.recordClose(MON, TUE)).isFalse();

        assertThat(calendar.recordedCloses()).isEqualTo(1);
        assertThat(calendar.conflictingCloses()).isZero();
    }

    @Test
    void a_late_close_for_an_older_day_never_moves_the_date_back_but_fills_the_count() {
        var calendar = new InMemoryClosingBooksBusinessCalendar();
        calendar.recordClose(MON, TUE);
        calendar.recordClose(WED, THU);
        assertThat(calendar.businessDaysClosedBetween(MON, THU)).isEqualTo(2);
        assertThat(calendar.missingCloses()).containsExactly(TUE);

        calendar.recordClose(TUE, WED);

        assertThat(calendar.currentBusinessDate()).contains(THU);
        assertThat(calendar.businessDaysClosedBetween(MON, THU)).isEqualTo(3);
        assertThat(calendar.missingCloses()).isEmpty();
    }

    @Test
    void a_conflicting_close_keeps_the_first_and_is_counted() {
        var calendar = new InMemoryClosingBooksBusinessCalendar();
        calendar.recordClose(FRI, NEXT_MON);

        assertThat(calendar.recordClose(FRI, SAT)).isFalse();

        assertThat(calendar.currentBusinessDate()).contains(NEXT_MON);
        assertThat(calendar.conflictingCloses()).isEqualTo(1);
    }

    @Test
    void counts_closed_business_days_from_inclusive_to_exclusive_skipping_days_that_are_not_business_days() {
        var calendar = new InMemoryClosingBooksBusinessCalendar();
        calendar.recordClose(MON, TUE);
        calendar.recordClose(TUE, WED);
        calendar.recordClose(WED, THU);
        calendar.recordClose(THU, FRI);
        calendar.recordClose(FRI, NEXT_MON);

        assertThat(calendar.businessDaysClosedBetween(FRI, NEXT_MON)).isEqualTo(1);
        assertThat(calendar.businessDaysClosedBetween(WED, NEXT_MON)).isEqualTo(3);
        assertThat(calendar.businessDaysClosedBetween(SAT, NEXT_MON)).isZero();
        assertThat(calendar.businessDaysClosedBetween(MON.minusDays(10), NEXT_MON)).isEqualTo(5);
        assertThat(calendar.businessDaysClosedBetween(NEXT_MON, MON)).isZero();
        assertThat(calendar.businessDaysClosedBetween(MON, MON)).isZero();
    }

    @Test
    void rejects_a_next_business_date_that_is_not_after_the_closed_one() {
        var calendar = new InMemoryClosingBooksBusinessCalendar();

        assertThatThrownBy(() -> calendar.recordClose(TUE, TUE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.recordClose(TUE, MON)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrent_closes_and_readers_see_a_consistent_calendar() throws Exception {
        var calendar = new InMemoryClosingBooksBusinessCalendar();
        var start    = LocalDate.parse("2030-01-01");
        var days     = 400;
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var i = 0; i < days; i++) {
                var day = start.plusDays(i);
                futures.add(executor.submit(() -> calendar.recordClose(day, day.plusDays(1))));
                futures.add(executor.submit(() -> {
                    var current = calendar.currentBusinessDate();
                    current.ifPresent(date -> assertThat(calendar.businessDaysClosedBetween(start, date)).isLessThanOrEqualTo(days));
                }));
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(calendar.recordedCloses()).isEqualTo(days);
        assertThat(calendar.currentBusinessDate()).contains(start.plusDays(days));
        assertThat(calendar.businessDaysClosedBetween(start, start.plusDays(days))).isEqualTo(days);
    }
}
