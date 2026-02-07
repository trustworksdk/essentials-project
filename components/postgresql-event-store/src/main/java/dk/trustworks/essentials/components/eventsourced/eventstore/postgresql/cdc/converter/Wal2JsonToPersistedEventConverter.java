/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;

import java.util.List;

/**
 * Interface defining a contract for converting wal2json messages into persisted events.
 * Implementations of this interface take a single wal2json-formatted message, typically
 * containing one or more changes from a relational database, and transform it into a list
 * of {@link PersistedEvent} objects. These persisted events are the representation of the
 * changes in a format suitable for further processing or storage.
 */
public interface Wal2JsonToPersistedEventConverter {

    /**
     * Convert a single wal2json message (which may include many changes)
     * into zero or more PersistedEvent.
     * <p>
     * Return empty list if the message is not relevant.
     */
    List<PersistedEvent> convert(String wal2jsonMessage);
}
