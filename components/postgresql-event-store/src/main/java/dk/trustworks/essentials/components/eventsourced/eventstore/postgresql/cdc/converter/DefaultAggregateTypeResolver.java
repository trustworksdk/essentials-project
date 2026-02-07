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

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Default implementation of the {@link AggregateTypeResolver} interface. This implementation resolves
 * aggregate types based on a predefined mapping between event table names and {@link AggregateType} instances.
 * <p>
 * The resolution process uses a {@code Map} where the keys are event table names and the values
 * are the corresponding aggregate types. If a table name cannot be resolved, an exception is thrown.
 */
public class DefaultAggregateTypeResolver implements AggregateTypeResolver {

    private final Map<String, AggregateType> aggregateEventStreamTableNames;

    public DefaultAggregateTypeResolver(Map<String, AggregateType> aggregateEventStreamTableNames) {
        this.aggregateEventStreamTableNames = requireNonNull(aggregateEventStreamTableNames, "aggregateEventStreamTableNames cannot be null.");
    }

    /**
     * Resolves and returns the {@link AggregateType} associated with the given event table name.
     * The mapping between event table names and aggregate types is predefined.
     * If the table name does not exist in the mapping, an {@code IllegalArgumentException} is thrown.
     *
     * @param tableName the name of the event table for which the aggregate type needs to be resolved
     * @return the {@link AggregateType} associated with the specified table name
     * @throws IllegalArgumentException if no matching aggregate type is found for the provided table name
     */
    @Override
    public AggregateType resolveFromEventTable(String tableName) {
        return Optional.ofNullable(aggregateEventStreamTableNames.get(tableName)).orElseThrow(() -> new IllegalArgumentException("No aggregate type found for event table name: " + tableName));
    }
}
