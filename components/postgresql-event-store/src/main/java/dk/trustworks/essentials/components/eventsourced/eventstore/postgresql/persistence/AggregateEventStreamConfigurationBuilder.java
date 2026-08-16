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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;

/**
 * Builder for {@link AggregateEventStreamConfiguration}, obtained from
 * {@link AggregateEventStreamConfiguration#builder()}.
 * <p>
 * Modelled on the existing {@code EventStreamTableColumnNamesBuilder}. The five column-type arguments in the middle of
 * the constructor are the reason this exists: three consecutive {@link IdentifierColumnType}s followed by two
 * consecutive {@link JSONColumnType}s cannot be told apart by the compiler, so a transposition is silent and shows up
 * as a schema that is subtly wrong.
 */
public class AggregateEventStreamConfigurationBuilder {
    protected AggregateType         aggregateType;
    protected int                   queryFetchSize = 100;
    protected JSONEventSerializer   jsonSerializer;
    protected AggregateIdSerializer aggregateIdSerializer;
    protected IdentifierColumnType  aggregateIdColumnType;
    protected IdentifierColumnType  eventIdColumnType;
    protected IdentifierColumnType  correlationIdColumnType;
    protected JSONColumnType        eventJsonColumnType;
    protected JSONColumnType        eventMetadataJsonColumnType;
    protected TenantSerializer      tenantSerializer;

    /** @param aggregateType the aggregate type this configuration applies to. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setAggregateType(AggregateType aggregateType) {
        this.aggregateType = aggregateType;
        return this;
    }

    /** @param queryFetchSize the JDBC fetch size used when streaming events. Defaults to 100 @return this builder */
    public AggregateEventStreamConfigurationBuilder setQueryFetchSize(int queryFetchSize) {
        this.queryFetchSize = queryFetchSize;
        return this;
    }

    /** @param jsonSerializer the event JSON serializer. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setJsonSerializer(JSONEventSerializer jsonSerializer) {
        this.jsonSerializer = jsonSerializer;
        return this;
    }

    /** @param aggregateIdSerializer how aggregate ids are converted to/from their column value. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setAggregateIdSerializer(AggregateIdSerializer aggregateIdSerializer) {
        this.aggregateIdSerializer = aggregateIdSerializer;
        return this;
    }

    /** @param aggregateIdColumnType the SQL column type for the aggregate id. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setAggregateIdColumnType(IdentifierColumnType aggregateIdColumnType) {
        this.aggregateIdColumnType = aggregateIdColumnType;
        return this;
    }

    /** @param eventIdColumnType the SQL column type for the event id. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setEventIdColumnType(IdentifierColumnType eventIdColumnType) {
        this.eventIdColumnType = eventIdColumnType;
        return this;
    }

    /** @param correlationIdColumnType the SQL column type for the correlation id. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setCorrelationIdColumnType(IdentifierColumnType correlationIdColumnType) {
        this.correlationIdColumnType = correlationIdColumnType;
        return this;
    }

    /** @param eventJsonColumnType JSON or JSONB for the event payload column. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setEventJsonColumnType(JSONColumnType eventJsonColumnType) {
        this.eventJsonColumnType = eventJsonColumnType;
        return this;
    }

    /** @param eventMetadataJsonColumnType JSON or JSONB for the event metadata column. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setEventMetadataJsonColumnType(JSONColumnType eventMetadataJsonColumnType) {
        this.eventMetadataJsonColumnType = eventMetadataJsonColumnType;
        return this;
    }

    /** @param tenantSerializer how tenants are converted to/from their column value. Required @return this builder */
    public AggregateEventStreamConfigurationBuilder setTenantSerializer(TenantSerializer tenantSerializer) {
        this.tenantSerializer = tenantSerializer;
        return this;
    }

    /** @return the new {@link AggregateEventStreamConfiguration} */
    @SuppressWarnings("removal")
    public AggregateEventStreamConfiguration build() {
        return new AggregateEventStreamConfiguration(aggregateType,
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
