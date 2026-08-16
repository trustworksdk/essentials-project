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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfigurationBuilder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;

/**
 * Builder for {@link SeparateTablePerAggregateEventStreamConfiguration}, obtained from
 * {@link SeparateTablePerAggregateEventStreamConfiguration#builder()}.
 * <p>
 * Extends {@link AggregateEventStreamConfigurationBuilder} with the two table-layout arguments this configuration
 * adds. The inherited setters are re-declared with the narrower return type so a fluent chain can mix the two sets in
 * any order.
 */
public final class SeparateTablePerAggregateEventStreamConfigurationBuilder extends AggregateEventStreamConfigurationBuilder {
    private String                      eventStreamTableName;
    private EventStreamTableColumnNames eventStreamTableColumnNames;

    /**
     * @param eventStreamTableName the table this aggregate type's events are stored in. Required.
     *                             Goes straight into SQL — see {@code PostgresqlUtil.checkIsValidTableOrColumnName}
     * @return this builder
     */
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setEventStreamTableName(String eventStreamTableName) {
        this.eventStreamTableName = eventStreamTableName;
        return this;
    }

    /**
     * @param eventStreamTableColumnNames the column names in that table. Required
     * @return this builder
     */
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setEventStreamTableColumnNames(EventStreamTableColumnNames eventStreamTableColumnNames) {
        this.eventStreamTableColumnNames = eventStreamTableColumnNames;
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setAggregateType(AggregateType aggregateType) {
        super.setAggregateType(aggregateType);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setQueryFetchSize(int queryFetchSize) {
        super.setQueryFetchSize(queryFetchSize);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setJsonSerializer(JSONEventSerializer jsonSerializer) {
        super.setJsonSerializer(jsonSerializer);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setAggregateIdSerializer(AggregateIdSerializer aggregateIdSerializer) {
        super.setAggregateIdSerializer(aggregateIdSerializer);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setAggregateIdColumnType(IdentifierColumnType aggregateIdColumnType) {
        super.setAggregateIdColumnType(aggregateIdColumnType);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setEventIdColumnType(IdentifierColumnType eventIdColumnType) {
        super.setEventIdColumnType(eventIdColumnType);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setCorrelationIdColumnType(IdentifierColumnType correlationIdColumnType) {
        super.setCorrelationIdColumnType(correlationIdColumnType);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setEventJsonColumnType(JSONColumnType eventJsonColumnType) {
        super.setEventJsonColumnType(eventJsonColumnType);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setEventMetadataJsonColumnType(JSONColumnType eventMetadataJsonColumnType) {
        super.setEventMetadataJsonColumnType(eventMetadataJsonColumnType);
        return this;
    }

    @Override
    public SeparateTablePerAggregateEventStreamConfigurationBuilder setTenantSerializer(TenantSerializer tenantSerializer) {
        super.setTenantSerializer(tenantSerializer);
        return this;
    }

    /** @return the new {@link SeparateTablePerAggregateEventStreamConfiguration} */
    @Override
    @SuppressWarnings("removal")
    public SeparateTablePerAggregateEventStreamConfiguration build() {
        return new SeparateTablePerAggregateEventStreamConfiguration(aggregateType,
                                                                     eventStreamTableName,
                                                                     eventStreamTableColumnNames,
                                                                     queryFetchSize,
                                                                     jsonSerializer,
                                                                     aggregateIdSerializer,
                                                                     aggregateIdColumnType,
                                                                     eventIdColumnType,
                                                                     correlationIdColumnType,
                                                                     eventJsonColumnType,
                                                                     eventMetadataJsonColumnType,
                                                                     tenantSerializer);
    }
}
