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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The recorder itself: what {@link AppendedEvents#recordDuring} hands back for the events
 * {@link PostgresqlEventStore#appendToStream} reports. That every append reaches it is {@code AppendedEventsIT}'s job
 */
class AppendedEventsTest {
    private static final AggregateType ORDERS   = AggregateType.of("Orders");
    private static final AggregateType PRODUCTS = AggregateType.of("Products");

    @Test
    void the_events_appended_during_the_action_are_handed_to_the_caller_with_the_actions_result() {
        var recorded = AppendedEvents.recordDuring(() -> {
            AppendedEvents.appended(List.of(event(ORDERS, "o-1", 42), event(ORDERS, "o-1", 43)));
            AppendedEvents.appended(List.of(event(PRODUCTS, "p-1", 7)));
            return "result";
        });

        assertThat(recorded.result()).isEqualTo("result");
        assertThat(recorded.appendedEvents()).extracting(event -> event.globalEventOrder().longValue()).containsExactly(42L, 43L, 7L);
        assertThat(recorded.lastAppendedEvent()).hasValueSatisfying(event -> assertThat((Object) event.aggregateType()).isEqualTo(PRODUCTS));
        assertThat(recorded.highestGlobalEventOrder(ORDERS)).contains(GlobalEventOrder.of(43));
        assertThat(recorded.highestGlobalEventOrder(PRODUCTS)).contains(GlobalEventOrder.of(7));
        assertThat(recorded.highestGlobalEventOrder(AggregateType.of("Other"))).isEmpty();
    }

    @Test
    void an_action_that_appends_nothing_records_nothing() {
        var recorded = AppendedEvents.recordDuring(() -> null);

        assertThat(recorded.appendedEvents()).isEmpty();
        assertThat(recorded.lastAppendedEvent()).isEmpty();
        assertThat(recorded.highestGlobalEventOrder(ORDERS)).isEmpty();
    }

    @Test
    void recordings_nest_and_nothing_is_recorded_outside_one() {
        AppendedEvents.appended(List.of(event(ORDERS, "o-0", 1)));

        var outer = AppendedEvents.recordDuring(() -> {
            AppendedEvents.appended(List.of(event(ORDERS, "o-1", 2)));
            var inner = AppendedEvents.recordDuring(() -> {
                AppendedEvents.appended(List.of(event(ORDERS, "o-2", 3)));
                return null;
            });
            assertThat(inner.appendedEvents()).extracting(PersistedEvent::aggregateId).containsExactly("o-2");
            return inner;
        });

        assertThat(outer.appendedEvents()).extracting(PersistedEvent::aggregateId).containsExactly("o-1", "o-2");
    }

    private static PersistedEvent event(AggregateType aggregateType, String aggregateId, long globalEventOrder) {
        var event = mock(PersistedEvent.class);
        when(event.aggregateType()).thenReturn(aggregateType);
        when(event.aggregateId()).thenReturn(aggregateId);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalEventOrder));
        return event;
    }
}
