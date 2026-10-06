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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.foundation.types.EventId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Operation matching the {@link EventStore#loadEventsCausedBy(EventId)} call: load every {@link PersistedEvent} whose
 * {@link PersistedEvent#causedByEventId()} is {@link #causedByEventId}
 */
public final class LoadEventsCausedBy {
    /**
     * The id of the causing event
     */
    public final EventId causedByEventId;

    /**
     * @param causedByEventId the id of the causing event
     */
    public LoadEventsCausedBy(EventId causedByEventId) {
        this.causedByEventId = requireNonNull(causedByEventId, "No causedByEventId provided");
    }

    @Override
    public String toString() {
        return "LoadEventsCausedBy{causedByEventId=" + causedByEventId + '}';
    }
}
