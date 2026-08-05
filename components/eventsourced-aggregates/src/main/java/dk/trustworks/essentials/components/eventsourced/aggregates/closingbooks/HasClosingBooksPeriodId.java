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
 * Optional aggregate contract for built-in closing-books time-boundary policies.
 * <p>
 * Aggregates that use {@link ClosingBooksDefaultPolicyType#TIME_BOUNDARY} or
 * {@link ClosingBooksDefaultPolicyType#EVENT_COUNT_OR_TIME_BOUNDARY} need to expose
 * the currently persisted business period id so the framework can detect whether
 * the configured boundary has advanced and whether one or more periods were skipped.
 * The required period-id format depends on {@link ClosingBooksTimeBoundary}:
 * {@code END_OF_DAY} and {@code EVERY_N_DAYS} use {@code yyyy-MM-dd},
 * {@code END_OF_WEEK} uses ISO week format {@code yyyy-Www},
 * {@code END_OF_MONTH} uses {@code yyyy-MM},
 * and {@code END_OF_YEAR} uses {@code yyyy}.
 */
public interface HasClosingBooksPeriodId {
    /**
     * @return the aggregate's currently persisted business period identifier
     */
    String closingBooksPeriodId();
}
