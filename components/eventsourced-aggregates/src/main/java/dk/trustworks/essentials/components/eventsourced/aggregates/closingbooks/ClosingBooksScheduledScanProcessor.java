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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

/**
 * Processor interface for executing scheduled scans of closing books in an event-sourced system.
 * The primary purpose of this interface is to define the contract for managing types of aggregates
 * and processing batches of aggregated data.
 */
public interface ClosingBooksScheduledScanProcessor {

    /**
     * Retrieves the type of aggregate that this processor is responsible for handling.
     *
     * @return the aggregate type associated with this processor
     */
    AggregateType aggregateType();

    /**
     * Processes the next batch of aggregated data for the associated aggregate type.
     *
     * @param batchSize the size of the batch to be processed; must be a positive integer
     * @return the number of items successfully processed in the batch
     */
    int processNextBatch(int batchSize);
}
