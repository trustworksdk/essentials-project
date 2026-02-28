/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

/**
 * Represents a functional interface for resolving aggregate IDs based on a given
 * aggregate type and aggregate ID string.
 * <p>
 * Designed to work with {@code AggregateType} to provide a context for the resolution
 * and utilizes the provided {@code aggregateId} string to determine the resolved ID value.
 */
@FunctionalInterface
public interface AggregateIdResolver {
    Object resolve(AggregateType aggregateType, String aggregateId);
}
