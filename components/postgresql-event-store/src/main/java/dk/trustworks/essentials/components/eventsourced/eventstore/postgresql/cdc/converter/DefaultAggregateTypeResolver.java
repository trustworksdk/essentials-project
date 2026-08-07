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

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Default implementation of {@link AggregateTypeResolver} that resolves aggregate types based on a
 * <em>live</em> mapping between event table names and {@link AggregateType} instances.
 * <p>
 * The resolver takes a {@link Supplier} rather than a fixed {@link Map} so that aggregates
 * registered at runtime (e.g. via {@code addAggregateEventStreamConfiguration}) become visible to
 * CDC conversion without needing to rebuild the Spring context. Earlier versions of this class
 * held a map snapshot captured at construction time — any runtime registration was silently
 * invisible to CDC, causing events to be dropped at conversion (returned as {@code Optional.empty()}
 * without surfacing a conversion failure, poison row, or dispatcher error).
 * <p>
 * Each {@link #resolveFromEventTable(String)} call invokes the supplier exactly once. The cost is
 * one map lookup per resolve plus whatever the supplier costs (typically either a direct reference
 * or a cheap {@code Collectors.toMap} over the configured aggregates). For CDC dispatch rates this
 * is negligible.
 */
public class DefaultAggregateTypeResolver implements AggregateTypeResolver {

    private final Supplier<Map<String, AggregateType>> aggregateEventStreamTableNamesSupplier;

    /**
     * Construct a resolver backed by a live supplier of the table-name → aggregate-type map.
     * The supplier is invoked on every {@link #resolveFromEventTable(String)} call, so runtime
     * registrations propagate immediately.
     */
    public DefaultAggregateTypeResolver(Supplier<Map<String, AggregateType>> aggregateEventStreamTableNamesSupplier) {
        this.aggregateEventStreamTableNamesSupplier = requireNonNull(aggregateEventStreamTableNamesSupplier,
                                                                     "aggregateEventStreamTableNamesSupplier cannot be null.");
    }

    /**
     * Back-compat convenience for callers (typically tests) that have a static table-name map and
     * don't need live updates. Wraps the provided map in a {@code () -> map} supplier.
     */
    public DefaultAggregateTypeResolver(Map<String, AggregateType> aggregateEventStreamTableNames) {
        this(() -> requireNonNull(aggregateEventStreamTableNames, "aggregateEventStreamTableNames cannot be null."));
    }

    /**
     * Resolves and returns the {@link AggregateType} associated with the given event table name.
     * Throws {@link IllegalArgumentException} if no mapping is present. Callers who want soft
     * resolution should use {@link #tryResolveFromEventTable(String)}.
     */
    @Override
    public AggregateType resolveFromEventTable(String tableName) {
        return Optional.ofNullable(aggregateEventStreamTableNamesSupplier.get().get(tableName))
                       .orElseThrow(() -> new IllegalArgumentException("No aggregate type found for event table name: " + tableName));
    }
}
