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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

/**
 * Enum representing the possible statuses of an aggregate archive during its lifecycle.
 * <p>
 * - ARCHIVED: Indicates that the archive process has successfully completed, and the
 *   aggregate's data has been written to storage.
 * <p>
 * - FAILED: Indicates that the archive process encountered an error and did not complete successfully.
 * <p>
 * - IN_PROGRESS: Indicates that the archive process is currently underway. This status is reserved
 *   by a worker actively writing the archive. It ensures that duplicate concurrent export operations
 *   for the same aggregate generation are prevented across different nodes.
 */
public enum AggregateArchiveStatus {

    ARCHIVED,
    FAILED,
    /** Reserved by a worker that is currently writing the archive. Used to prevent duplicate
     *  concurrent exports of the same generation across nodes. */
    IN_PROGRESS
}
