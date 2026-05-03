/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

/**
 * Provides an interface for archiving a specific generation of an aggregate. This interface
 * defines the method necessary to initiate the archiving process and retrieve an archive entry
 * containing metadata and results of the operation.
 */
public interface AggregateGenerationArchiver {

    /**
     * Archives a specific generation of an aggregate and returns the associated archive entry.
     * This method performs the necessary steps to process and store the archival data for the aggregate.
     *
     * @param aggregateType       The type of the aggregate to be archived. Must not be null.
     * @param logicalAggregateId  The logical identifier of the aggregate. Must not be blank.
     * @param generation          The generation number of the aggregate to be archived. Must be greater
     *                             than or equal to 1.
     * @return An {@code AggregateArchiveEntry} that encapsulates the metadata and results of the
     *         archiving process.
     * @throws IllegalArgumentException     If {@code aggregateType} or {@code logicalAggregateId} is null.
     * @throws IllegalArgumentException If {@code generation} is less than 1 or {@code logicalAggregateId} is blank.
     */
    AggregateArchiveEntry archiveGeneration(AggregateType aggregateType,
                                           String logicalAggregateId,
                                           long generation);
}
