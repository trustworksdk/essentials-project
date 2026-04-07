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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

/**
 * Controls how aggregate snapshots are persisted.
 */
public enum SnapshotExecutionMode {
    /**
     * Represents a mode where aggregate snapshots are persisted synchronously.
     * In this mode, the persistence operation will wait until the snapshot
     * is fully written to its storage before proceeding further.
     */
    SYNC,
    /**
     * Represents a mode where aggregate snapshots are persisted asynchronously in memory.
     * In this mode, snapshot persistence does not block further operations, and the snapshot
     * is retained temporarily in-memory without being immediately written to durable storage.
     * This approach optimizes for performance at the cost of durability, which can be useful
     * for scenarios where temporary persistence is sufficient or durability is managed through
     * other mechanisms.
     */
    ASYNC_IN_MEMORY,
    /**
     * Represents a mode where aggregate snapshots are persisted asynchronously
     * but ensure durability by writing them to a permanent storage medium.
     * Unlike {@code ASYNC_IN_MEMORY}, this mode balances the benefits of
     * non-blocking operations with the reliability of durable persistence.
     */
    ASYNC_DURABLE
}
