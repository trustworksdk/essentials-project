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

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Extension of {@link ClosingBooksGenerationRepository} that can scan open generations across many logical aggregates.
 */
public interface ClosingBooksOpenGenerationRepository<ID> extends ClosingBooksGenerationRepository<ID> {
    /**
     * Load up to {@code limit} currently open generations for the aggregate type.
     * Intended for scheduled closing-books scans.
     */
    List<AggregateGeneration<ID>> loadOpenGenerations(AggregateType aggregateType,
                                                      int limit);

    /**
     * Load up to {@code limit} currently open generations for the aggregate type, excluding any that
     * {@link #deferScan(AggregateType, LogicalAggregateId, OffsetDateTime)} has deferred past {@code eligibleAt}.
     * <p>
     * A scheduled scan orders by age and takes the oldest {@code limit} rows, so without this a generation the scan
     * cannot make progress on — an aggregate that fails to load, a policy that throws — stays at the head of every
     * batch and starves every other aggregate of the same type.
     * <p>
     * The default implementation ignores {@code eligibleAt} and so retains that behaviour; implementations backing a
     * scheduled scan should override both this and {@link #deferScan}.
     *
     * @param eligibleAt only return generations eligible for scanning at this point in time
     */
    default List<AggregateGeneration<ID>> loadOpenGenerations(AggregateType aggregateType,
                                                              int limit,
                                                              OffsetDateTime eligibleAt) {
        return loadOpenGenerations(aggregateType, limit);
    }

    /**
     * Exclude the open generation of {@code logicalAggregateId} from
     * {@link #loadOpenGenerations(AggregateType, int, OffsetDateTime)} until {@code nextScanTs}.
     * <p>
     * Called by a scheduled scan when it could not process the generation, so the failure costs one attempt per
     * deferral window rather than the whole batch on every poll. Deferral is advisory: it never blocks an explicit
     * rollover, and a generation whose scan later succeeds simply keeps a timestamp in the past.
     * <p>
     * The default implementation does nothing.
     *
     * @param nextScanTs the earliest point in time the generation should be scanned again
     */
    default void deferScan(AggregateType aggregateType,
                           LogicalAggregateId<ID> logicalAggregateId,
                           OffsetDateTime nextScanTs) {
    }
}
