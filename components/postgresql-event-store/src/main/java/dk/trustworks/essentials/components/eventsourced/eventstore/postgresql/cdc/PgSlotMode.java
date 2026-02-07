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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

/**
 * Enumeration defining the different modes for managing PostgreSQL replication slots
 * in relation to Change Data Capture (CDC) operations.
 */
public enum PgSlotMode {

    /**
     * Create slot if it does not exist.
     * Fail if slot exists but is owned by another logical consumer.
     */
    CREATE_IF_MISSING,

    /**
     * Expect slot to already exist.
     * Fail fast if missing.
     */
    REQUIRE_EXISTING,

    /**
     * Always drop + recreate slot at startup.
     * Intended for tests or ephemeral environments.
     */
    RECREATE,

    /**
     * Never create or manage slots.
     * Tailer assumes slot is externally managed.
     */
    EXTERNAL
}
