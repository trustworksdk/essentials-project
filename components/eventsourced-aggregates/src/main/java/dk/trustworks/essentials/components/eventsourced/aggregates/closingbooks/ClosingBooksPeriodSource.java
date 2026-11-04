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

/**
 * Where an {@link ClosingBooksTimeBoundary#END_OF_DAY} boundary takes its days from.
 * <p>
 * {@link #CLOCK} is the calendar day of a {@link java.time.Clock} in a zone: the books roll at midnight.
 * {@link #BUSINESS_CALENDAR} is the business day of an application-supplied {@link ClosingBooksBusinessCalendar}:
 * the books roll when the calendar records that a business day closed, for example at an exchange's clearing cutoff,
 * and never on weekends or holidays. Period ids keep the {@code yyyy-MM-dd} format either way, so an aggregate can move
 * between the two without re-seeding.
 */
public enum ClosingBooksPeriodSource {
    /**
     * Calendar days of the evaluator's {@link java.time.Clock} in its zone. The default.
     */
    CLOCK,

    /**
     * Business days of a {@link ClosingBooksBusinessCalendar}. Valid only with {@link ClosingBooksTimeBoundary#END_OF_DAY}.
     */
    BUSINESS_CALENDAR
}
