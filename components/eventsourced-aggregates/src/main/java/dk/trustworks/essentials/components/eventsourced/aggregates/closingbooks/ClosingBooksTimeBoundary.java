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
 * Built-in time cadences that can drive automatic closing-books rollover.
 */
public enum ClosingBooksTimeBoundary {
    /**
     * No time-based rollover rule is configured.
     */
    NONE,

    /**
     * Roll over when a new day starts in the configured zone.
     */
    END_OF_DAY,

    /**
     * Roll over when a new ISO week starts in the configured zone.
     */
    END_OF_WEEK,

    /**
     * Roll over when a new calendar month starts in the configured zone.
     */
    END_OF_MONTH,

    /**
     * Roll over when a new calendar year starts in the configured zone.
     */
    END_OF_YEAR,

    /**
     * Roll over when the current fixed interval window changes.
     */
    EVERY_N_DAYS
}
