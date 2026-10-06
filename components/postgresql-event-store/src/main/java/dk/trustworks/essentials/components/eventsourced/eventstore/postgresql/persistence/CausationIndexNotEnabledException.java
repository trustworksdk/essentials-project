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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence;

/**
 * Thrown when the events caused by an event are requested but the caused-by-event-id index is not enabled. The lookup
 * refuses rather than scanning every event-stream table; enable the index with
 * {@code essentials.eventstore.causation.index-enabled=true} (or {@code enableCausationIndex()} on the persistence
 * strategy) - see {@code docs/event-causation.md}.
 */
public final class CausationIndexNotEnabledException extends IllegalStateException {
    public CausationIndexNotEnabledException(String message) {
        super(message);
    }
}
