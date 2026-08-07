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
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Metadata describing one stream generation for a logical aggregate.
 * Each generation maps the logical aggregate id to one concrete stream aggregate id.
 */
public record AggregateGeneration<ID>(AggregateType aggregateType,
                                      LogicalAggregateId<ID> logicalAggregateId,
                                      long generation,
                                      String streamAggregateId,
                                      GenerationState state,
                                      OffsetDateTime openedAt,
                                      Optional<OffsetDateTime> closedAt) {
    public AggregateGeneration {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(streamAggregateId, "No streamAggregateId provided");
        requireNonNull(state, "No state provided");
        requireNonNull(openedAt, "No openedAt provided");
        requireNonNull(closedAt, "No closedAt provided");
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be >= 1");
        }
    }

    public boolean isOpen() {
        return state == GenerationState.OPEN;
    }

    /**
     * @return {@code true} when the generation has been closed and should no longer receive writes
     */
    public boolean isClosed() {
        return state == GenerationState.CLOSED;
    }

    /**
     * Create a closed copy of this generation.
     */
    public AggregateGeneration<ID> close(OffsetDateTime closedAt) {
        requireNonNull(closedAt, "No closedAt provided");
        return new AggregateGeneration<>(aggregateType,
                                         logicalAggregateId,
                                         generation,
                                         streamAggregateId,
                                         GenerationState.CLOSED,
                                         openedAt,
                                         Optional.of(closedAt));
    }
}
