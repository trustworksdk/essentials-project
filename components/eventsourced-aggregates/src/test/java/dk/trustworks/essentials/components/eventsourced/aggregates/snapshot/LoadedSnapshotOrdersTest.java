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


package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoadedSnapshotOrdersTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    private final LoadedSnapshotOrders orders  = new LoadedSnapshotOrders();
    private final AtomicInteger        queries = new AtomicInteger();

    @Test
    void what_a_load_found_answers_the_next_update_once_then_the_database_is_asked_again() {
        loaded("order-1", EventOrder.MAX_EVENT_ORDER, Optional.of(snapshotAt(7)));

        assertThat(newest("order-1")).contains(EventOrder.of(7));
        assertThat(queries).hasValue(0);
        assertThat(newest("order-1")).contains(EventOrder.of(99));
        assertThat(queries).hasValue(1);
    }

    @Test
    void a_load_that_found_no_snapshot_answers_that_there_is_none() {
        loaded("order-1", EventOrder.MAX_EVENT_ORDER, Optional.empty());

        assertThat(newest("order-1")).isEmpty();
        assertThat(queries).hasValue(0);
    }

    /**
     * A load bounded below the newest snapshot - an as-of projection - says nothing about the newest one
     */
    @Test
    void a_load_bounded_to_an_older_event_order_is_not_recorded() {
        loaded("order-1", EventOrder.of(3), Optional.of(snapshotAt(2)));

        assertThat(newest("order-1")).contains(EventOrder.of(99));
        assertThat(queries).hasValue(1);
    }

    @Test
    void aggregates_are_told_apart_by_type_id_and_implementation_type() {
        loaded("order-1", EventOrder.MAX_EVENT_ORDER, Optional.of(snapshotAt(7)));

        assertThat(orders.newestSnapshotEventOrder(ORDERS, "order-2", String.class, this::query)).contains(EventOrder.of(99));
        assertThat(orders.newestSnapshotEventOrder(AggregateType.of("Other"), "order-1", String.class, this::query)).contains(EventOrder.of(99));
        assertThat(orders.newestSnapshotEventOrder(ORDERS, "order-1", Integer.class, this::query)).contains(EventOrder.of(99));
        assertThat(newest("order-1")).contains(EventOrder.of(7));
    }

    @Test
    void forgetting_an_aggregate_or_an_implementation_type_drops_what_was_recorded() {
        loaded("order-1", EventOrder.MAX_EVENT_ORDER, Optional.of(snapshotAt(7)));
        loaded("order-2", EventOrder.MAX_EVENT_ORDER, Optional.of(snapshotAt(8)));
        loaded("order-3", EventOrder.MAX_EVENT_ORDER, Optional.of(snapshotAt(9)));

        orders.forget(ORDERS, "order-1", String.class);
        orders.forgetAll(String.class);

        assertThat(newest("order-1")).contains(EventOrder.of(99));
        assertThat(newest("order-2")).contains(EventOrder.of(99));
        assertThat(newest("order-3")).contains(EventOrder.of(99));
        assertThat(queries).hasValue(3);
    }

    @Test
    void only_a_bounded_number_of_loads_is_kept_and_the_oldest_goes_first() {
        for (var i = 0; i <= 10_000; i++) {
            loaded("order-" + i, EventOrder.MAX_EVENT_ORDER, Optional.of(snapshotAt(i)));
        }

        assertThat(newest("order-0")).contains(EventOrder.of(99));
        assertThat(newest("order-10000")).contains(EventOrder.of(10_000));
    }

    private void loaded(String aggregateId, EventOrder bound, Optional<AggregateSnapshot<String, String>> found) {
        orders.recordLoaded(ORDERS, aggregateId, bound, String.class, found);
    }

    private Optional<EventOrder> newest(String aggregateId) {
        return orders.newestSnapshotEventOrder(ORDERS, aggregateId, String.class, this::query);
    }

    private Optional<EventOrder> query() {
        queries.incrementAndGet();
        return Optional.of(EventOrder.of(99));
    }

    private static AggregateSnapshot<String, String> snapshotAt(long eventOrder) {
        return new AggregateSnapshot<>(ORDERS, "order", String.class, "state", EventOrder.of(eventOrder));
    }
}
