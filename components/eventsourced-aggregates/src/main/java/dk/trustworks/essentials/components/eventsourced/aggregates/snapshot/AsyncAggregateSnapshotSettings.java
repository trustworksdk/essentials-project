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
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * Settings for async aggregate snapshot persistence.
 *
 * @param mode          how snapshot writes are scheduled (see {@link SnapshotExecutionMode}).
 * @param workerThreads number of worker threads in the {@link SnapshotExecutionMode#ASYNC_IN_MEMORY}
 *                      executor pool. Must be {@code >= 1}. Ignored for {@link SnapshotExecutionMode#SYNC}.
 */
public record AsyncAggregateSnapshotSettings(SnapshotExecutionMode mode, int workerThreads) {
    public AsyncAggregateSnapshotSettings {
        requireNonNull(mode, "No mode provided");
        requireTrue(workerThreads >= 1, "workerThreads must be >= 1");
    }

    public AsyncAggregateSnapshotSettings(SnapshotExecutionMode mode) {
        this(mode, 1);
    }

    public static AsyncAggregateSnapshotSettings synchronous() {
        return new AsyncAggregateSnapshotSettings(SnapshotExecutionMode.SYNC, 1);
    }

    public static AsyncAggregateSnapshotSettings asynchronous() {
        return new AsyncAggregateSnapshotSettings(SnapshotExecutionMode.ASYNC_IN_MEMORY, 1);
    }
}
