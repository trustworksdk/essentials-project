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

import java.time.LocalDate;
import java.util.Optional;

/**
 * The business days a closing-books {@link ClosingBooksTimeBoundary#END_OF_DAY} boundary rolls on, supplied by the
 * application ({@link ClosingBooksPeriodSource#BUSINESS_CALENDAR}).
 * <p>
 * A business day ends when the application learns it closed - typically from an external signal such as an exchange's
 * end-of-day event - not at midnight. The calendar answers two questions on the hot path of every mutating command, so
 * an implementation must answer from memory: no I/O per call. {@link InMemoryClosingBooksBusinessCalendar} is a
 * thread-safe implementation an application feeds from wherever it records closes (a table, an event stream).
 * <p>
 * The period id a business date maps to is its {@code yyyy-MM-dd} form, the same format as the clock-based
 * {@code END_OF_DAY} boundary.
 */
public interface ClosingBooksBusinessCalendar {
    /**
     * The business date currently open: the date work done now books into. After a close of business date {@code d},
     * this is the next business date the close named.
     *
     * @return the open business date, or empty when it is not known yet (for example before the calendar has been
     * loaded on a cold start). An unknown date never rolls the books.
     */
    Optional<LocalDate> currentBusinessDate();

    /**
     * How many business days closed going from {@code from} to {@code to}: the number of closed business dates
     * {@code d} with {@code from <= d < to}.
     * <p>
     * {@code from} need not be a business date itself (an aggregate seeded on a Saturday counts every business day
     * closed since). Days that are not business days are never counted.
     *
     * @param from the start, inclusive
     * @param to   the end, exclusive
     * @return the count; {@code 0} when {@code to} is not after {@code from}
     */
    long businessDaysClosedBetween(LocalDate from, LocalDate to);
}
