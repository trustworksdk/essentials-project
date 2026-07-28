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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * Covers the bug that was silently losing CDC events for runtime-registered aggregates: the
 * resolver used to hold a {@link Map} snapshot captured at Spring-startup time, so
 * {@code addAggregateEventStreamConfiguration(...)} calls made during a running application were
 * invisible to CDC conversion. Events in those aggregates would be dropped at
 * {@code PgOutputToPersistedEventConverter.convertIfRelevant(...)} with no conversion failure or
 * poison row surfaced — {@code delivered=0} in perf-lab runs.
 * <p>
 * The resolver now takes a {@link java.util.function.Supplier} so every lookup reads fresh state.
 */
class DefaultAggregateTypeResolverTest {

    @Test
    void static_map_constructor_resolves_known_tables() {
        Map<String, AggregateType> map = new HashMap<>();
        map.put("orders_events", AggregateType.of("Orders"));
        var resolver = new DefaultAggregateTypeResolver(map);

        // AggregateType extends CharSequenceType which is CharSequence — AssertJ's two assertThat
        // overloads (<T> and CharSequence) both match, so we compare stringified values to bypass
        // the ambiguity without losing the intent.
        AggregateType resolved = resolver.resolveFromEventTable("orders_events");
        assertThat(resolved.toString()).isEqualTo("Orders");
    }

    @Test
    void unknown_table_throws_IllegalArgumentException() {
        Map<String, AggregateType> map = new HashMap<>();
        var resolver = new DefaultAggregateTypeResolver(map);

        assertThatThrownBy(() -> resolver.resolveFromEventTable("nope_events"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope_events");
    }

    @Test
    void tryResolveFromEventTable_returns_empty_for_unknown_table() {
        Map<String, AggregateType> map = new HashMap<>();
        var resolver = new DefaultAggregateTypeResolver(map);

        assertThat(resolver.tryResolveFromEventTable("nope_events")).isEmpty();
    }

    /**
     * Regression: a runtime registration against the backing map must be visible on the next
     * resolver lookup. This is the fix for the "delivered=0 across all backpressure cases" bug —
     * the old resolver built a snapshot at construction and never saw new aggregates.
     */
    @Test
    void supplier_constructor_sees_runtime_registrations() {
        ConcurrentHashMap<String, AggregateType> liveMap = new ConcurrentHashMap<>();
        liveMap.put("orders_events", AggregateType.of("Orders"));

        var resolver = new DefaultAggregateTypeResolver(() -> liveMap);

        // Baseline: known aggregate resolves.
        AggregateType baseline = resolver.resolveFromEventTable("orders_events");
        assertThat(baseline.toString()).isEqualTo("Orders");
        // Unknown aggregate is missing — as expected.
        assertThat(resolver.tryResolveFromEventTable("backpressure_events")).isEmpty();

        // Simulate runtime addAggregateEventStreamConfiguration — mutate the backing map.
        liveMap.put("backpressure_events", AggregateType.of("LabOrdersBackpressure"));

        // The resolver must now see the new aggregate on the next call, without being rebuilt.
        AggregateType afterRuntimeRegistration = resolver.resolveFromEventTable("backpressure_events");
        assertThat(afterRuntimeRegistration.toString()).isEqualTo("LabOrdersBackpressure");
    }

    /**
     * Guards against a regression where the resolver might cache the first supplier result.
     * Every lookup must go through the supplier afresh.
     */
    @Test
    void supplier_is_invoked_on_every_resolve_call() {
        int[] invocations = {0};
        Map<String, AggregateType> map = new HashMap<>();
        map.put("orders_events", AggregateType.of("Orders"));

        var resolver = new DefaultAggregateTypeResolver(() -> {
            invocations[0]++;
            return map;
        });

        resolver.resolveFromEventTable("orders_events");
        resolver.resolveFromEventTable("orders_events");
        resolver.resolveFromEventTable("orders_events");

        assertThat(invocations[0]).isEqualTo(3);
    }
}
