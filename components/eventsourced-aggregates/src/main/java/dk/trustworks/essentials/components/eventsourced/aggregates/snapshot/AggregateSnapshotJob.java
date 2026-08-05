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

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Represents a job tasked with managing aggregate snapshots, including creation, deletion,
 * and updates. Each job is uniquely identified by its {@code jobId}.
 * <p>
 * This record stores metadata and configuration about the job, its current state,
 * and relevant timestamps needed to manage its execution lifecycle.
 * <p>
 * This record helps manage the lifecycle of aggregate snapshot operations
 * while providing clear metadata for debugging and monitoring purposes.
 */
public record AggregateSnapshotJob(UUID jobId,
                                   String aggregateType,
                                   String serializedAggregateId,
                                   String aggregateImplementationType,
                                   long lastIncludedEventOrder,
                                   String serializedSnapshot,
                                   boolean deleteAllExistingSnapshots,
                                   List<Long> snapshotEventOrdersToDelete,
                                   OffsetDateTime createdTs,
                                   OffsetDateTime nextAttemptTs,
                                   int attempts,
                                   AggregateSnapshotJobStatus status,
                                   String lastError) {
    public AggregateSnapshotJob {
        snapshotEventOrdersToDelete = List.copyOf(snapshotEventOrdersToDelete == null ? List.of() : snapshotEventOrdersToDelete);
    }
}
