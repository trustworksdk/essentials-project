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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 8 unit 8b-A: the engine reads the {@code EventStore} adapter needs (§5.2) - the last event of a stream, events
 * by ids, by global-order range (streamed, filtered), the highest and lowest global order, and the events caused by an
 * event on its partial index. Their generic plans are checked in {@link EventLogGenericPlanIT}.
 */
class EventLogAdapterReadsIT extends AbstractEventLogIT {
    private static final Instant TIMESTAMP = Instant.parse("2026-11-15T10:00:00.123456Z");

    private EventLog eventLog;

    @BeforeEach
    void start() throws SQLException {
        eventLog = startedEventLog("Orders", "Invoices");
    }

    @AfterEach
    void stop() {
        eventLog.stop();
    }

    @Test
    void the_last_event_of_a_stream_is_the_one_with_the_highest_event_order() throws SQLException {
        appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-1", events("Placed", 3)));
        appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-2", events("Placed", 1)));

        try (var connection = dataSource.getConnection()) {
            var last = eventLog.findLastEvent(connection, "Orders", "o-1").orElseThrow();
            assertThat(last.aggregateId()).isEqualTo("o-1");
            assertThat(last.eventOrder()).isEqualTo(2);
            assertThat(last.payload()).isEqualTo("{\"n\":2}");
            assertThat(eventLog.findLastEvent(connection, "Orders", "missing")).isEmpty();
            assertThat(eventLog.findLastEvent(connection, "Invoices", "o-1")).isEmpty();
        }
    }

    @Test
    void events_by_ids_come_back_once_each_in_global_order_and_missing_ids_are_skipped() throws SQLException {
        var appended = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-1", events("Placed", 4))).getFirst().events();
        var first    = appended.get(0).eventId();
        var third    = appended.get(2).eventId();

        try (var connection = dataSource.getConnection()) {
            var found = eventLog.findEvents(connection, "Orders", List.of(third, UUID.randomUUID(), first, third));
            assertThat(found).extracting(StoredEvent::eventId).containsExactly(first, third);
            assertThat(eventLog.findEvents(connection, "Orders", List.of())).isEmpty();
            assertThat(eventLog.findEvents(connection, "Invoices", List.of(first))).isEmpty();
        }
    }

    @Test
    void a_global_order_range_is_inclusive_skips_holes_and_adds_the_additional_orders_once() throws SQLException {
        var orders = new ArrayList<Long>();
        for (var i = 0; i < 5; i++) {
            orders.add(appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-" + i, events("Placed", 1))).getFirst().events().getFirst().globalOrder());
        }
        var hole = appendRolledBack(AppendRequest.withoutExpectedOrder("Orders", "rolled-back", events("Placed", 1)));
        for (var i = 5; i < 8; i++) {
            orders.add(appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-" + i, events("Placed", 1))).getFirst().events().getFirst().globalOrder());
        }
        assertThat(orders).doesNotContain(hole);

        assertThat(globalOrders(GlobalOrderRead.range("Orders", orders.get(1), orders.get(3)))).containsExactly(orders.get(1), orders.get(2), orders.get(3));
        assertThat(globalOrders(GlobalOrderRead.from("Orders", orders.get(4)))).containsExactly(orders.get(4), orders.get(5), orders.get(6), orders.get(7));
        assertThat(globalOrders(GlobalOrderRead.range("Orders", orders.get(4), orders.get(5)))).as("the rolled-back order is a hole, not a row")
                                                                                             .containsExactly(orders.get(4), orders.get(5));
        assertThat(globalOrders(GlobalOrderRead.range("Orders", orders.get(2), orders.get(3))
                                               .withAdditionalGlobalOrders(List.of(orders.get(7), orders.get(0), orders.get(3), hole))))
                .as("additional orders merged in global order, an order inside the range read once, a hole skipped")
                .containsExactly(orders.get(0), orders.get(2), orders.get(3), orders.get(7));
        assertThat(globalOrders(GlobalOrderRead.range("Orders", 10, 5))).as("empty range").isEmpty();
        assertThat(globalOrders(GlobalOrderRead.range("Orders", 10, 5).withAdditionalGlobalOrders(List.of(orders.get(6)))))
                .as("empty range, additional order").containsExactly(orders.get(6));
    }

    @Test
    void a_global_order_read_filters_by_event_type_and_by_tenant_including_events_without_a_tenant() throws SQLException {
        var placed   = appendCommitted(eventLog, new AppendRequest("Orders", "o-1", null, "acme", events("Placed", 1))).getFirst().events().getFirst().globalOrder();
        var shipped  = appendCommitted(eventLog, new AppendRequest("Orders", "o-2", null, "acme", events("Shipped", 1))).getFirst().events().getFirst().globalOrder();
        var other    = appendCommitted(eventLog, new AppendRequest("Orders", "o-3", null, "globex", events("Placed", 1))).getFirst().events().getFirst().globalOrder();
        var untenant = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-4", events("Cancelled", 1))).getFirst().events().getFirst().globalOrder();
        var all      = GlobalOrderRead.from("Orders", 0);

        assertThat(globalOrders(all)).containsExactly(placed, shipped, other, untenant);
        assertThat(globalOrders(all.withEventTypes(Set.of("Placed")))).containsExactly(placed, other);
        assertThat(globalOrders(all.withEventTypes(Set.of("Placed", "NeverRegistered")))).containsExactly(placed, other);
        assertThat(globalOrders(all.withEventTypes(Set.of("NeverRegistered")))).as("no known type: nothing, without a statement").isEmpty();
        assertThat(globalOrders(all.withTenant("acme"))).as("the tenant's events and the untenanted ones").containsExactly(placed, shipped, untenant);
        assertThat(globalOrders(all.withTenant("acme").withEventTypes(Set.of("Placed", "Cancelled")))).containsExactly(placed, untenant);
        assertThat(globalOrders(all.withTenant("globex").withAdditionalGlobalOrders(List.of(placed)))).as("the filters apply to additional orders too")
                                                                                                    .containsExactly(other, untenant);
        try (var stream = eventLog.loadByGlobalOrder(all.withEventTypes(Set.of("Shipped")))) {
            var event = stream.findFirst().orElseThrow();
            assertThat(event.eventType()).isEqualTo("Shipped");
            assertThat(event.tenant()).isEqualTo("acme");
            assertThat(event.aggregateType()).isEqualTo("Orders");
        }
    }

    @Test
    void a_global_order_read_streams_in_fetch_size_pages_and_closing_the_stream_releases_the_borrowed_connection() throws SQLException {
        appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-1", events("Placed", 25)));
        var before = openConnections();

        try (var stream = eventLog.loadByGlobalOrder(GlobalOrderRead.from("Orders", 0).withFetchSize(4))) {
            var iterator = stream.iterator();
            iterator.next();
            assertThat(openConnections()).as("the borrowed connection is held while the stream is open").isEqualTo(before + 1);
            assertThat(cursorPortals()).as("a server-side portal, so rows come in fetch-size pages").isPositive();
            var count = 1;
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
            assertThat(count).isEqualTo(25);
        }
        assertThat(openConnections()).as("closing the stream returned the connection").isEqualTo(before);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var stream = eventLog.loadByGlobalOrder(connection, GlobalOrderRead.range("Orders", 0, Long.MAX_VALUE).withFetchSize(3))) {
                assertThat(stream.map(StoredEvent::eventOrder).toList()).hasSize(25).isSorted();
            }
            assertThat(connection.isClosed()).as("the caller's connection stays open").isFalse();
            connection.commit();
        }
    }

    @Test
    void the_highest_and_lowest_global_order_are_those_committed_and_empty_for_an_empty_table() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertThat(eventLog.findHighestGlobalOrder(connection, "Orders")).isEmpty();
            assertThat(eventLog.findLowestGlobalOrder(connection, "Orders")).isEmpty();
        }
        var appended = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-1", events("Placed", 3))).getFirst().events();
        appendRolledBack(AppendRequest.withoutExpectedOrder("Orders", "o-2", events("Placed", 2)));

        try (var connection = dataSource.getConnection()) {
            assertThat(eventLog.findHighestGlobalOrder(connection, "Orders")).hasValue(appended.getLast().globalOrder());
            assertThat(eventLog.findLowestGlobalOrder(connection, "Orders")).hasValue(appended.getFirst().globalOrder());
            assertThat(eventLog.findHighestGlobalOrder(connection, "Invoices")).isEmpty();
        }
    }

    @Test
    void the_events_caused_by_an_event_come_from_every_registered_type_on_the_partial_caused_by_index() throws SQLException {
        var cause   = UUID.randomUUID();
        var other   = UUID.randomUUID();
        var order1  = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-1", List.of(caused("Placed", cause), caused("Placed", other)))).getFirst().events();
        var invoice = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Invoices", "i-1", List.of(caused("Issued", cause)))).getFirst().events();
        var order2  = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o-2", List.of(caused("Placed", cause), event("Placed", "{}")))).getFirst().events();

        try (var connection = dataSource.getConnection()) {
            assertThat(eventLog.findCausedBy(connection, cause))
                    .as("Invoices before Orders (event table name order), each in global order")
                    .extracting(StoredEvent::eventId)
                    .containsExactly(invoice.getFirst().eventId(), order1.getFirst().eventId(), order2.getFirst().eventId());
            assertThat(eventLog.findCausedBy(connection, other)).extracting(StoredEvent::eventId).containsExactly(order1.get(1).eventId());
            assertThat(eventLog.findCausedBy(connection, UUID.randomUUID())).isEmpty();
        }
        var tables = AggregateTypeTables.of("Orders");
        assertThat(queryLong("SELECT count(*) FROM pg_indexes WHERE indexname = '" + tables.causedByIndex() + "' AND tablename = '" + tables.eventTable() +
                                     "' AND indexdef LIKE '%(caused_by_event_id) WHERE (caused_by_event_id IS NOT NULL)'"))
                .as("the partial caused-by index exists").isOne();
        assertThat(queryLong("SELECT count(*) FROM " + tables.eventTable() + " WHERE caused_by_event_id IS NULL")).as("an event without a cause").isOne();
    }

    @Test
    void reads_refuse_an_unregistered_aggregate_type() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> eventLog.findLastEvent(connection, "Unknown", "x")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> eventLog.loadByGlobalOrder(connection, GlobalOrderRead.from("Unknown", 0))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> eventLog.findHighestGlobalOrder(connection, "Unknown")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static EventToAppend caused(String type, UUID cause) {
        return new EventToAppend(UUID.randomUUID(), cause, null, type, 1, TIMESTAMP, "{}", "{}");
    }

    private List<Long> globalOrders(GlobalOrderRead read) throws SQLException {
        try (var stream = eventLog.loadByGlobalOrder(read)) {
            return stream.map(StoredEvent::globalOrder).toList();
        }
    }

    /** @return the global order the rolled-back append drew */
    private long appendRolledBack(AppendRequest request) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var order = eventLog.append(connection, request).events().getFirst().globalOrder();
            connection.rollback();
            return order;
        }
    }

    private long openConnections() throws SQLException {
        return queryLong("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid()");
    }

    private long cursorPortals() throws SQLException {
        return queryLong("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction' AND query LIKE 'SELECT global_order%'");
    }
}
