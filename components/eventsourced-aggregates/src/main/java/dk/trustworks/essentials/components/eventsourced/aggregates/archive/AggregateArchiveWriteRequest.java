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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateGeneration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Metadata describing where and how to write an archive artifact. Carries no payload bytes —
 * the destination opens its own {@link java.io.OutputStream} and invokes the
 * {@link ArchiveContentWriter} that is passed alongside this request.
 */
public record AggregateArchiveWriteRequest(
        AggregateType aggregateType,
        String logicalAggregateId,
        AggregateGeneration<String> generation,
        AggregateArchiveFormat format,
        String fileExtension
) {
    public AggregateArchiveWriteRequest {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonBlank(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(generation, "No generation provided");
        requireNonNull(format, "No format provided");
        requireNonBlank(fileExtension, "No fileExtension provided");
    }
}
