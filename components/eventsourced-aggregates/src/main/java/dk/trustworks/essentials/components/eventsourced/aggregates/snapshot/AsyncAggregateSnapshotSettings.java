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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Settings for async aggregate snapshot persistence.
 */
public record AsyncAggregateSnapshotSettings(SnapshotExecutionMode mode) {
    public AsyncAggregateSnapshotSettings(SnapshotExecutionMode mode) {
        this.mode = requireNonNull(mode, "No mode provided");
    }

    public static AsyncAggregateSnapshotSettings synchronous() {
        return new AsyncAggregateSnapshotSettings(SnapshotExecutionMode.SYNC);
    }

    public static AsyncAggregateSnapshotSettings asynchronous() {
        return new AsyncAggregateSnapshotSettings(SnapshotExecutionMode.ASYNC_IN_MEMORY);
    }
}
