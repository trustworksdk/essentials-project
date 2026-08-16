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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.foundation.types.*;

import java.util.function.Function;

/**
 * Builder for {@link SeparateTablePerAggregateTypeEventStreamConfigurationFactory}, obtained from
 * {@link SeparateTablePerAggregateTypeEventStreamConfigurationFactory#builder()}.
 * <p>
 * Note this builds the <em>factory</em> — the thing that produces one
 * {@link SeparateTablePerAggregateEventStreamConfiguration} per {@link AggregateType}. It carries no
 * {@code aggregateType} and no {@code aggregateIdSerializer}, because those are exactly what the factory resolves per
 * aggregate type; that is why it is a sibling of
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfigurationBuilder}
 * rather than a subclass of it.
 */
public final class SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder {
    private Function<AggregateType, String> resolveEventStreamTableName;
    private EventStreamTableColumnNames     eventStreamTableColumnNames;
    private int                             queryFetchSize = 100;
    private JSONEventSerializer             jsonSerializer;
    private IdentifierColumnType            aggregateIdColumnType;
    private IdentifierColumnType            eventIdColumnType;
    private IdentifierColumnType            correlationIdColumnType;
    private JSONColumnType                  eventJsonColumnType;
    private JSONColumnType                  eventMetadataJsonColumnType;
    private TenantSerializer<?>             tenantSerializer;

    /** @param resolveEventStreamTableName maps an aggregate type to its event-stream table name. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setResolveEventStreamTableName(Function<AggregateType, String> resolveEventStreamTableName) {
        this.resolveEventStreamTableName = resolveEventStreamTableName;
        return this;
    }

    /** @param eventStreamTableColumnNames the column names shared by every event-stream table. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setEventStreamTableColumnNames(EventStreamTableColumnNames eventStreamTableColumnNames) {
        this.eventStreamTableColumnNames = eventStreamTableColumnNames;
        return this;
    }

    /** @param queryFetchSize the JDBC fetch size used when streaming events. Defaults to 100 @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setQueryFetchSize(int queryFetchSize) {
        this.queryFetchSize = queryFetchSize;
        return this;
    }

    /** @param jsonSerializer the event JSON serializer. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setJsonSerializer(JSONEventSerializer jsonSerializer) {
        this.jsonSerializer = jsonSerializer;
        return this;
    }

    /** @param aggregateIdColumnType the SQL column type for the aggregate id. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setAggregateIdColumnType(IdentifierColumnType aggregateIdColumnType) {
        this.aggregateIdColumnType = aggregateIdColumnType;
        return this;
    }

    /** @param eventIdColumnType the SQL column type for the event id. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setEventIdColumnType(IdentifierColumnType eventIdColumnType) {
        this.eventIdColumnType = eventIdColumnType;
        return this;
    }

    /** @param correlationIdColumnType the SQL column type for the correlation id. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setCorrelationIdColumnType(IdentifierColumnType correlationIdColumnType) {
        this.correlationIdColumnType = correlationIdColumnType;
        return this;
    }

    /** @param eventJsonColumnType JSON or JSONB for the event payload column. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setEventJsonColumnType(JSONColumnType eventJsonColumnType) {
        this.eventJsonColumnType = eventJsonColumnType;
        return this;
    }

    /** @param eventMetadataJsonColumnType JSON or JSONB for the event metadata column. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setEventMetadataJsonColumnType(JSONColumnType eventMetadataJsonColumnType) {
        this.eventMetadataJsonColumnType = eventMetadataJsonColumnType;
        return this;
    }

    /** @param tenantSerializer how tenants are converted to/from their column value. Required @return this builder */
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactoryBuilder setTenantSerializer(TenantSerializer<?> tenantSerializer) {
        this.tenantSerializer = tenantSerializer;
        return this;
    }

    /** @return the new factory */
    @SuppressWarnings("removal")
    public SeparateTablePerAggregateTypeEventStreamConfigurationFactory build() {
        return new SeparateTablePerAggregateTypeEventStreamConfigurationFactory(resolveEventStreamTableName,
                                                                                eventStreamTableColumnNames,
                                                                                queryFetchSize,
                                                                                jsonSerializer,
                                                                                aggregateIdColumnType,
                                                                                eventIdColumnType,
                                                                                correlationIdColumnType,
                                                                                eventJsonColumnType,
                                                                                eventMetadataJsonColumnType,
                                                                                tenantSerializer);
    }
}
