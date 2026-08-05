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
 * Common built-in closing-books policy styles that can be selected directly from
 * {@link AggregateClosingBooksPolicy} or external configuration.
 */
public enum ClosingBooksDefaultPolicyType {
    /**
     * No explicit default policy was declared. Fall back to external configuration or framework defaults.
     */
    UNSPECIFIED,

    /**
     * No automatic rollover. New generations are only opened explicitly by application code.
     */
    MANUAL_ONLY,

    /**
     * Roll over once a configured event threshold is reached.
     */
    EVENT_COUNT,

    /**
     * Roll over when the configured time boundary advances.
     */
    TIME_BOUNDARY,

    /**
     * Roll over when either the event threshold or time-boundary condition is met.
     */
    EVENT_COUNT_OR_TIME_BOUNDARY,

    /**
     * Roll over only when explicitly requested by application code.
     */
    EXPLICIT_ONLY
}
