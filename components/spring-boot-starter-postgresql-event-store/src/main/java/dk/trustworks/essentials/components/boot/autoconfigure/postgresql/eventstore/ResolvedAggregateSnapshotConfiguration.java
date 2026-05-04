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

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.SnapshotDeletionMode;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.SnapshotExecutionMode;

/**
 * Represents the resolved configuration for aggregate snapshotting.
 * This configuration is typically used to determine how snapshots of aggregates
 * are managed, including persistence, frequency, and deletion strategies.
 *
 * @param enabled Indicates whether snapshotting is enabled for the aggregate. If set to {@code true},
 *                snapshots will be created and managed according to the resolved configuration. If
 *                set to {@code false}, snapshotting will be disabled.
 * @param mode Defines the execution mode for persisting aggregate snapshots. This can be one of the
 *             modes defined in {@link SnapshotExecutionMode}, such as synchronous or asynchronous
 *             persistence.
 * @param everyNEvents Specifies the frequency at which snapshots are created, based on the number
 *                     of events applied to the aggregate. A snapshot will be taken after every
 *                     {@code everyNEvents} events.
 * @param deletionMode Configures the strategy for deleting historical snapshots. The value is based
 *                     on {@link SnapshotDeletionMode}, which determines whether all historical
 *                     snapshots are deleted or whether only a specific number of snapshots are retained.
 * @param keepLastSnapshots Defines the number of most recent snapshots to retain when the deletion
 *                          mode is set to {@code KEEP_LAST_N}. This value is ignored if the deletion
 *                          mode is {@code DELETE_ALL_HISTORIC}.
 */
public record ResolvedAggregateSnapshotConfiguration(
        boolean enabled,
        SnapshotExecutionMode mode,
        int everyNEvents,
        SnapshotDeletionMode deletionMode,
        int keepLastSnapshots
) {
}
