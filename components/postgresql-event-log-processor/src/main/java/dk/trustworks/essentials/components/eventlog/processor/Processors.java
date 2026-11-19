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

package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.StoredEvent;

import java.sql.*;

/** What both processors share */
final class Processors {
    private Processors() {
    }

    /**
     * Deserialize inside the handler call, so a failure is a handler failure: retried, then parked (§7). The mutant
     * {@link ProcessorMutant#DESERIALIZE_OUTSIDE_HANDLER_RETRY} reports it as an I/O failure instead
     */
    static DeliveredEvent deserialize(EventDeserializer deserializer, StoredEvent event, ProcessorSeams seams) throws SQLException {
        try {
            return deserializer.deserialize(event);
        } catch (RuntimeException e) {
            if (seams.has(ProcessorMutant.DESERIALIZE_OUTSIDE_HANDLER_RETRY)) {
                throw new SQLTransientConnectionException("MUTANT DESERIALIZE_OUTSIDE_HANDLER_RETRY", "08000", e);
            }
            throw e;
        }
    }
}
