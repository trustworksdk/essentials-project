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

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class EventLogAppendLoadIT extends AbstractEventLogIT {

    @Test
    void appended_events_load_back_with_every_column_and_the_exact_json_bytes() throws Exception {
        var eventLog = startedEventLog("Orders");
        // Whitespace, key order, duplicate keys, escapes and non-ASCII: json (not jsonb) keeps them all
        var payload  = "{ \"b\" : 2,\n  \"a\":1, \"a\" : 3, \"text\": \"\\u00e6 æøå \\\"q\\\"\", \"n\": 1.50e+2 }";
        var metadata = "{\"correlation\":   \"x\" }";
        var causedBy    = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        var timestamp   = Instant.parse("2026-11-15T10:11:12.123456Z");
        var first  = new EventToAppend(UUID.randomUUID(), causedBy, correlation, "OrderPlaced", 3, timestamp, payload, metadata);
        var second = new EventToAppend(UUID.randomUUID(), null, null, "OrderShipped", 1, timestamp, "[]", "{}");

        var result = appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "order-1", AppendRequest.NEW_STREAM, List.of(first, second)).withTenant("tenant-a"))
                .getFirst();

        assertThat(result.events()).extracting(AppendedEvent::eventOrder).containsExactly(0L, 1L);
        assertThat(result.events()).extracting(AppendedEvent::globalOrder).containsExactly(1L, 2L);
        assertThat(result.events()).extracting(AppendedEvent::eventId).containsExactly(first.eventId(), second.eventId());

        var loaded = eventLog.loadStream("Orders", "order-1");
        assertThat(loaded).hasSize(2);
        var event = loaded.getFirst();
        assertThat(event.aggregateType()).isEqualTo("Orders");
        assertThat(event.aggregateId()).isEqualTo("order-1");
        assertThat(event.globalOrder()).isEqualTo(1L);
        assertThat(event.eventOrder()).isEqualTo(0L);
        assertThat(event.eventId()).isEqualTo(first.eventId());
        assertThat(event.causedByEventId()).isEqualTo(causedBy);
        assertThat(event.correlationId()).isEqualTo(correlation);
        assertThat(event.eventType()).isEqualTo("OrderPlaced");
        assertThat(event.eventRevision()).isEqualTo(3);
        assertThat(event.timestamp().toInstant()).isEqualTo(timestamp);
        assertThat(event.payload()).isEqualTo(payload);
        assertThat(event.metadata()).isEqualTo(metadata);
        assertThat(event.tenant()).isEqualTo("tenant-a");
        assertThat(loaded.get(1).eventType()).isEqualTo("OrderShipped");
        assertThat(loaded.get(1).causedByEventId()).isNull();

        // Column types: uuid ids, smallint interned type
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_name = 'el_orders_events' AND " +
                                     "((column_name IN ('event_id', 'caused_by_event_id', 'correlation_id') AND data_type = 'uuid') OR " +
                                     " (column_name IN ('event_type', 'event_revision') AND data_type = 'smallint'))")).isEqualTo(5);
        try (var connection = dataSource.getConnection()) {
            assertThat(eventLog.findEvent(connection, "Orders", second.eventId())).map(StoredEvent::eventOrder).contains(1L);
            assertThat(eventLog.findEvent(connection, "Orders", UUID.randomUUID())).isEmpty();
            assertThat(eventLog.loadStream(connection, "Orders", "order-1", 1, 1)).extracting(StoredEvent::eventId).containsExactly(second.eventId());
        }
        assertHeadsConsistent(eventLog.registerAggregateType("Orders"));
    }

    @Test
    void event_types_are_interned_once_and_read_back_by_name_by_a_fresh_engine() throws Exception {
        var eventLog = startedEventLog("Orders");
        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", -1, List.of(event("Placed", "{}"), event("Placed", "{}"), event("Shipped", "{}"))));
        assertThat(queryLong("SELECT count(*) FROM el_event_types")).isEqualTo(2);

        var fresh = startedEventLog("Orders");
        assertThat(fresh.loadStream("Orders", "o1")).extracting(StoredEvent::eventType).containsExactly("Placed", "Placed", "Shipped");
        assertThat(fresh.eventTypes().idOf("Shipped")).isEqualTo(eventLog.eventTypes().idOf("Shipped"));
    }

    @Test
    void appends_after_an_expected_order_and_without_one_continue_the_stream() throws Exception {
        var eventLog = startedEventLog("Orders");
        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", -1, events("E", 2)));
        var after   = appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", 1, events("E", 3))).getFirst();
        var without = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o1", events("E", 1))).getFirst();
        var created = appendCommitted(eventLog, AppendRequest.withoutExpectedOrder("Orders", "o2", events("E", 2))).getFirst();

        assertThat(after.events()).extracting(AppendedEvent::eventOrder).containsExactly(2L, 3L, 4L);
        assertThat(without.events()).extracting(AppendedEvent::eventOrder).containsExactly(5L);
        assertThat(created.events()).extracting(AppendedEvent::eventOrder).containsExactly(0L, 1L);
        assertThat(eventLog.loadStream("Orders", "o1")).extracting(StoredEvent::globalOrder).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertHeadsConsistent(eventLog.registerAggregateType("Orders"));
    }

    @Test
    void several_aggregates_of_several_types_append_in_one_unit_of_work_and_roll_back_together() throws Exception {
        var eventLog = startedEventLog("Orders", "Customers");
        var results = appendCommitted(eventLog,
                                      AppendRequest.afterEventOrder("Orders", "o1", -1, events("E", 2)),
                                      AppendRequest.afterEventOrder("Customers", "c1", -1, events("E", 1)),
                                      AppendRequest.afterEventOrder("Orders", "o2", -1, events("E", 1)),
                                      AppendRequest.withoutExpectedOrder("Orders", "o3", events("E", 1)));
        assertThat(results).extracting(AppendResult::aggregateId).containsExactly("o1", "c1", "o2", "o3");
        assertThat(results.get(0).events()).extracting(AppendedEvent::eventOrder).containsExactly(0L, 1L);
        assertThat(results.get(1).events()).extracting(AppendedEvent::globalOrder).containsExactly(1L);
        assertThat(results.get(2).events()).extracting(AppendedEvent::eventOrder).containsExactly(0L);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            eventLog.append(connection, List.of(AppendRequest.afterEventOrder("Orders", "o1", 1, events("E", 1)),
                                                AppendRequest.afterEventOrder("Customers", "c2", -1, events("E", 1))));
            assertThat(eventLog.loadStream(connection, "Orders", "o1", 0, Long.MAX_VALUE)).as("visible in the caller's transaction").hasSize(3);
            connection.rollback();
        }
        assertThat(eventLog.loadStream("Orders", "o1")).hasSize(2);
        assertThat(eventLog.loadStream("Customers", "c2")).isEmpty();
        assertHeadsConsistent(AggregateTypeTables.of("Orders"));
        assertHeadsConsistent(AggregateTypeTables.of("Customers"));
    }

    @Test
    void an_unregistered_aggregate_type_is_refused() throws Exception {
        var eventLog = startedEventLog();
        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> eventLog.append(connection, AppendRequest.withoutExpectedOrder("Nope", "x", events("E", 1))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
