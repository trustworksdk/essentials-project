/*
 *  Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream;

import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

class EventStreamChangeNotificationTest {

    @Test
    void can_create_event_stream_change_notification() {
        // Given
        var tableName   = "order_events";
        var operation   = SqlOperation.INSERT;
        var aggregateId = "order-123";
        var globalOrder = 42L;
        var eventOrder  = 5L;
        var eventType   = "OrderPlaced";
        var tenant      = "tenant-a";
        var timestamp   = OffsetDateTime.now();

        // When
        var notification = new EventStreamChangeNotification(
                tableName, operation, aggregateId, globalOrder, eventOrder,
                eventType, tenant, timestamp
        );

        // Then
        assertThat(notification.getTableName()).isEqualTo(tableName);
        assertThat(notification.getOperation()).isEqualTo(operation);
        assertThat(notification.getAggregateId()).isEqualTo(aggregateId);
        assertThat(notification.getGlobalOrder()).isEqualTo(globalOrder);
        assertThat(notification.getEventOrder()).isEqualTo(eventOrder);
        assertThat(notification.getEventType()).isEqualTo(eventType);
        assertThat(notification.getTenant()).isEqualTo(tenant);
        assertThat(notification.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void can_derive_aggregate_type_from_table_name() {
        // Given
        var tableName = "order_events";
        var notification = new EventStreamChangeNotification(
                tableName, SqlOperation.INSERT, "order-123", 42L, 5L,
                "OrderPlaced", "tenant-a", OffsetDateTime.now()
        );

        // When
        var aggregateType = notification.getAggregateType();

        // Then
        assertThat((CharSequence) aggregateType).isEqualTo(AggregateType.of("order"));
    }

    @Test
    void can_derive_aggregate_type_from_complex_table_name() {
        // Given
        var tableName = "customer_order_events";
        var notification = new EventStreamChangeNotification(
                tableName, SqlOperation.INSERT, "order-123", 42L, 5L,
                "OrderPlaced", "tenant-a", OffsetDateTime.now()
        );

        // When
        var aggregateType = notification.getAggregateType();

        // Then
        assertThat((CharSequence) aggregateType).isEqualTo(AggregateType.of("customer_order"));
    }

    @Test
    void fails_to_derive_aggregate_type_from_invalid_table_name() {
        // Given
        var tableName = "invalid_table";
        var notification = new EventStreamChangeNotification(
                tableName, SqlOperation.INSERT, "order-123", 42L, 5L,
                "OrderPlaced", "tenant-a", OffsetDateTime.now()
        );

        // When & Then
        assertThatThrownBy(notification::getAggregateType)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot derive AggregateType from table name: invalid_table");
    }

    @Test
    void handles_null_table_name() {
        // Given
        var notification = new EventStreamChangeNotification(
                null, SqlOperation.INSERT, "order-123", 42L, 5L,
                "OrderPlaced", "tenant-a", OffsetDateTime.now()
        );

        // When & Then
        assertThatThrownBy(notification::getAggregateType)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot derive AggregateType from table name: null");
    }

    @Test
    void equality_works_correctly() {
        // Given
        var timestamp = OffsetDateTime.now();
        var notification1 = new EventStreamChangeNotification(
                "order_events", SqlOperation.INSERT, "order-123", 42L, 5L,
                "OrderPlaced", "tenant-a", timestamp
        );
        var notification2 = new EventStreamChangeNotification(
                "order_events", SqlOperation.INSERT, "order-123", 42L, 5L,
                "OrderPlaced", "tenant-a", timestamp
        );
        var notification3 = new EventStreamChangeNotification(
                "order_events", SqlOperation.INSERT, "order-456", 42L, 5L,
                "OrderPlaced", "tenant-a", timestamp
        );

        // Then
        assertThat(notification1).isEqualTo(notification2);
        assertThat(notification1).isNotEqualTo(notification3);
        assertThat(notification1.hashCode()).isEqualTo(notification2.hashCode());
        assertThat(notification1.hashCode()).isNotEqualTo(notification3.hashCode());
    }

    @Test
    void toString_contains_relevant_information() {
        // Given
        var notification = new EventStreamChangeNotification(
                "order_events", SqlOperation.INSERT, "order-123", 42L, 5L,
                "OrderPlaced", "tenant-a", OffsetDateTime.now()
        );

        // When
        var string = notification.toString();

        // Then
        assertThat(string).contains("order_events");
        assertThat(string).contains("INSERT");
        assertThat(string).contains("order-123");
        assertThat(string).contains("42");
        assertThat(string).contains("5");
        assertThat(string).contains("OrderPlaced");
        assertThat(string).contains("tenant-a");
    }
}