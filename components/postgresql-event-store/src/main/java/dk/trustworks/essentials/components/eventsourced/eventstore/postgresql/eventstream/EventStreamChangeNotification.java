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

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Represents a notification about changes to an event stream table.
 * This notification contains specific information about events being inserted into
 * aggregate event stream tables in the table-per-aggregate-type persistence strategy.
 *
 * <p>The {@link AggregateType} is derived from the table name since each aggregate type
 * has its own separate event stream table (e.g., "order_events", "customer_events").
 */
public class EventStreamChangeNotification extends TableChangeNotification {

    @JsonProperty("aggregate_id")
    private String aggregateId;

    @JsonProperty("global_order")
    private Long globalOrder;

    @JsonProperty("event_order")
    private Long eventOrder;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("tenant")
    private String tenant;

    @JsonProperty("timestamp")
    private OffsetDateTime timestamp;

    /**
     * Default constructor for JSON deserialization
     */
    protected EventStreamChangeNotification() {
    }

    /**
     * Constructor for creating a new event stream change notification
     *
     * @param tableName   the name of the event stream table
     * @param operation   the SQL operation that caused this notification
     * @param aggregateId the ID of the aggregate instance
     * @param globalOrder the global order of the event within the aggregate type
     * @param eventOrder  the order of the event within the specific aggregate instance
     * @param eventType   the type/name of the event
     * @param tenant      the tenant ID (can be null for single-tenant applications)
     * @param timestamp   the timestamp when the event was persisted
     */
    public EventStreamChangeNotification(String tableName,
                                         SqlOperation operation,
                                         String aggregateId,
                                         Long globalOrder,
                                         Long eventOrder,
                                         String eventType,
                                         String tenant,
                                         OffsetDateTime timestamp) {
        super(tableName, operation);
        this.aggregateId = aggregateId;
        this.globalOrder = globalOrder;
        this.eventOrder = eventOrder;
        this.eventType = eventType;
        this.tenant = tenant;
        this.timestamp = timestamp;
    }

    /**
     * Derives the AggregateType from the table name by removing the "_events" suffix.
     * For example: "order_events" -> AggregateType.of("order")
     *
     * @return the AggregateType derived from the table name
     */
    public AggregateType getAggregateType() {
        String tableName = getTableName();
        if (tableName != null && tableName.endsWith("_events")) {
            String aggregateTypeName = tableName.substring(0, tableName.length() - "_events".length());
            return AggregateType.of(aggregateTypeName);
        }
        throw new IllegalStateException("Cannot derive AggregateType from table name: " + tableName);
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public Long getGlobalOrder() {
        return globalOrder;
    }

    public Long getEventOrder() {
        return eventOrder;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTenant() {
        return tenant;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventStreamChangeNotification)) return false;
        if (!super.equals(o)) return false;
        EventStreamChangeNotification that = (EventStreamChangeNotification) o;
        return Objects.equals(aggregateId, that.aggregateId) &&
                Objects.equals(globalOrder, that.globalOrder) &&
                Objects.equals(eventOrder, that.eventOrder) &&
                Objects.equals(eventType, that.eventType) &&
                Objects.equals(tenant, that.tenant) &&
                Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), aggregateId, globalOrder, eventOrder,
                            eventType, tenant, timestamp);
    }

    @Override
    public String toString() {
        return "EventStreamChangeNotification{" +
                "tableName='" + getTableName() + '\'' +
                ", operation=" + getOperation() +
                ", aggregateId='" + aggregateId + '\'' +
                ", globalOrder=" + globalOrder +
                ", eventOrder=" + eventOrder +
                ", eventType='" + eventType + '\'' +
                ", tenant='" + tenant + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}