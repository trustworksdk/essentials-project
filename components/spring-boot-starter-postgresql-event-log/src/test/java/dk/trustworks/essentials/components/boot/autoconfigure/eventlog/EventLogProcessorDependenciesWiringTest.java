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

package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.EventMetaData;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The starter's {@link EventLogProcessorDependencies} takes each aggregate type's tenant serializer from the event
 * store's configuration, so a handler gets the tenant type the adapter wrote with.
 */
class EventLogProcessorDependenciesWiringTest {
    private static final JSONEventSerializer SERIALIZER = EssentialsJSONEventSerializers.create();

    record CountryTenant(String country) implements Tenant {
    }

    static class CountryTenantSerializer implements TenantSerializer<CountryTenant> {
        @Override
        public Class<?> tenantType() {
            return CountryTenant.class;
        }

        @Override
        public String serialize(CountryTenant tenant) {
            return tenant == null ? null : tenant.country();
        }

        @Override
        public Optional<CountryTenant> deserialize(String tenant) {
            return Optional.ofNullable(tenant).map(CountryTenant::new);
        }
    }

    private static StoredEvent storedWithTenant(String tenant) {
        return new StoredEvent("Orders", 7, 3, UUID.randomUUID(), null, null, "OrderPlaced", 1, OffsetDateTime.now(), "o1", "{}",
                               SERIALIZER.serialize(EventMetaData.of()), tenant);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EventLogProcessorDependencies dependenciesWith(TenantSerializer<?> tenantSerializer) {
        var eventStore = mock(ConfigurableEventStore.class);
        when(eventStore.findAggregateEventStreamConfiguration(AggregateType.of("Orders")))
                .thenReturn(Optional.of(new EventLogAggregateEventStreamConfiguration(AggregateType.of("Orders"), SERIALIZER,
                                                                                      new AggregateIdSerializer.StringIdSerializer(), tenantSerializer)));
        when(eventStore.findAggregateEventStreamConfiguration(AggregateType.of("Invoices"))).thenReturn(Optional.empty());
        ObjectProvider eventStores = mock(ObjectProvider.class);
        when(eventStores.getIfAvailable()).thenReturn(eventStore);
        return new EventLogAutoConfiguration().eventLogProcessorDependencies(EventLog.builder().setDataSource(new org.postgresql.ds.PGSimpleDataSource()).build(),
                                                                             SERIALIZER, mock(PageTransactionSource.class), eventStores);
    }

    @Test
    void a_handler_gets_the_tenant_type_of_the_applications_tenant_serializer() {
        var dependencies = dependenciesWith(new CountryTenantSerializer());

        assertThat(dependencies.deserializerFor("Orders").deserialize(storedWithTenant("DK")).tenant()).hasValue(new CountryTenant("DK"));
        // A type the event store has no configuration for reads TenantId tenants
        assertThat(dependencies.deserializerFor("Invoices").deserialize(storedWithTenant("DK")).tenant()).hasValue(TenantId.of("DK"));
    }

    @Test
    void the_single_tenant_default_reads_a_tenant_as_a_tenant_id_instead_of_throwing() {
        // Without a TenantSerializer bean the adapter is configured with NoSupportForMultiTenancySerializer, which throws on
        // any tenant; an event appended through the EventLog directly can carry one
        var dependencies = dependenciesWith(new TenantSerializer.NoSupportForMultiTenancySerializer());

        assertThat(dependencies.deserializerFor("Orders").deserialize(storedWithTenant("DK")).tenant()).hasValue(TenantId.of("DK"));
        assertThat(dependencies.deserializerFor("Orders").deserialize(storedWithTenant(null)).tenant()).isEmpty();
    }
}
