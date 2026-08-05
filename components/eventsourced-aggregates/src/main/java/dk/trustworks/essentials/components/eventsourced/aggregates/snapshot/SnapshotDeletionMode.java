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

import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * Enumeration representing the modes of snapshot deletion for an aggregate.
 * The modes define different strategies for managing historical snapshots.
 */
public enum SnapshotDeletionMode {
    /**
     * Represents a mode of snapshot deletion where all historical snapshots
     * associated with an aggregate are deleted. This mode is designed to remove
     * all prior snapshots, ensuring no historical state of the aggregate is retained.
     */
    DELETE_ALL_HISTORIC,
    /**
     * Represents a snapshot deletion mode where only a specified number of the most recent
     * snapshots are retained. Older snapshots exceeding the defined limit are deleted.
     * This mode is used when the goal is to maintain a limited history of snapshots
     * for an aggregate while removing excessive historical data.
     */
    KEEP_LAST_N;

    public AggregateSnapshotDeletionStrategy toDeletionStrategy() {
        return switch (this) {
            case DELETE_ALL_HISTORIC -> AggregateSnapshotDeletionStrategy.deleteAllHistoricSnapshots();
            case KEEP_LAST_N -> throw new IllegalArgumentException("KEEP_LAST_N requires keepLastSnapshots to be provided");
        };
    }

    public AggregateSnapshotDeletionStrategy toDeletionStrategy(int keepLastSnapshots) {
        requireTrue(keepLastSnapshots >= 0, "keepLastSnapshots must be >= 0");
        return switch (this) {
            case DELETE_ALL_HISTORIC -> AggregateSnapshotDeletionStrategy.deleteAllHistoricSnapshots();
            case KEEP_LAST_N -> AggregateSnapshotDeletionStrategy.keepALimitedNumberOfHistoricSnapshots(keepLastSnapshots);
        };
    }
}
