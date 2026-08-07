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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTriggerMode;

/**
 * Represents the resolved configuration used for managing the automatic or
 * manual closing of books within an event-sourced aggregate system.
 * This configuration aggregates multiple parameters that govern the triggering,
 * policies, thresholds, and time-based conditions for transitioning aggregate
 * states to a closed state.
 *
 * @param enabled Indicates whether the automatic closing-books functionality is enabled.
 * @param triggerMode Specifies the trigger mechanism for closing books as defined by {@link ClosingBooksTriggerMode}.
 * @param defaultPolicy Determines the default policy for closing books, based on the options available in {@link ClosingBooksDefaultPolicyType}.
 * @param eventThreshold Defines the threshold for the number of events that must be reached to trigger a rollover, when applicable.
 * @param timeBoundary Specifies the time-based boundary that governs closing-books rollover, as defined by {@link ClosingBooksTimeBoundary}.
 * @param zoneId Identifies the configured time zone for time-based evaluations, which is used alongside time-boundary rules.
 * @param intervalDays Defines the interval in days for fixed-interval rollovers, applicable when {@link ClosingBooksTimeBoundary#EVERY_N_DAYS}
 *                     is selected as the time-boundary mode.
 */
public record ResolvedAggregateClosingBooksConfiguration(
        boolean enabled,
        ClosingBooksTriggerMode triggerMode,
        ClosingBooksDefaultPolicyType defaultPolicy,
        Long eventThreshold,
        ClosingBooksTimeBoundary timeBoundary,
        String zoneId,
        Integer intervalDays
) {
}
