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

package dk.trustworks.essentials.components.eventsourced.aggregates.stateful;

import dk.trustworks.essentials.components.eventsourced.aggregates.OrderId;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.Order;
import dk.trustworks.essentials.components.eventsourced.aggregates.modern.OrderEvent;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotRepositoryProvider;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for the {@code Optional}-aware {@code from(…)} overload and the builder. No Docker: the
 * {@link ConfigurableEventStore} is a mock, since construction only registers configuration and an in-memory projector
 * on it.
 */
class StatefulAggregateRepositoryBuilderTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    private ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        eventStore = mock(ConfigurableEventStore.class);
        when(eventStore.findAggregateEventStreamConfiguration(any(AggregateType.class))).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------------------------------------------
    // 4a - the Optional-aware overload
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_an_empty_snapshot_provider_optional_yields_a_repository_with_no_snapshots() {
        Optional<AggregateSnapshotRepositoryProvider> noProvider = Optional.empty();

        StatefulAggregateRepository<OrderId, OrderEvent, Order> repository =
                StatefulAggregateRepository.from(eventStore,
                                                 ORDERS,
                                                 StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                                                 Order.class,
                                                 noProvider);

        assertThat((Object) repository.aggregateType()).isEqualTo(ORDERS);
        assertThat(repository.aggregateRootImplementationType()).isEqualTo(Order.class);
        assertThat(repository.aggregateIdType()).isEqualTo(OrderId.class);
    }

    @Test
    void test_a_present_snapshot_provider_optional_resolves_the_snapshot_repository() {
        var provider           = mock(AggregateSnapshotRepositoryProvider.class);
        var snapshotRepository = mock(AggregateSnapshotRepository.class);
        when(provider.resolve(ORDERS, Order.class)).thenReturn(Optional.of(snapshotRepository));

        StatefulAggregateRepository<OrderId, OrderEvent, Order> repository =
                StatefulAggregateRepository.from(eventStore,
                                                 ORDERS,
                                                 StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                                                 Order.class,
                                                 Optional.of(provider));

        assertThat((Object) repository.aggregateType()).isEqualTo(ORDERS);
        verify(provider).resolve(ORDERS, Order.class);
    }

    @Test
    void test_the_optional_aware_overload_rejects_a_null_optional() {
        Optional<AggregateSnapshotRepositoryProvider> nullOptional = null;

        assertThatThrownBy(() -> StatefulAggregateRepository.from(eventStore,
                                                                  ORDERS,
                                                                  StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                                                                  Order.class,
                                                                  nullOptional))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------------
    // 4b - the builder
    // ------------------------------------------------------------------------------------------------------

    @Test
    void test_the_builder_produces_the_same_repository_as_the_aggregate_type_from_overload() {
        StatefulAggregateRepository<OrderId, OrderEvent, Order> expected =
                StatefulAggregateRepository.from(eventStore,
                                                 ORDERS,
                                                 StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                                                 Order.class);

        StatefulAggregateRepository<OrderId, OrderEvent, Order> built =
                StatefulAggregateRepository.builder(eventStore)
                                           .setAggregateType(ORDERS)
                                           .setAggregateImplementationType(Order.class)
                                           .build();

        assertThat((Object) built.aggregateType()).isEqualTo(expected.aggregateType());
        assertThat(built.aggregateIdType()).isEqualTo(expected.aggregateIdType());
        assertThat(built.aggregateRootImplementationType()).isEqualTo(expected.aggregateRootImplementationType());
    }

    @Test
    void test_the_builder_produces_the_same_repository_as_the_event_stream_configuration_from_overload() {
        var configuration = configurationFor(ORDERS);

        StatefulAggregateRepository<OrderId, OrderEvent, Order> expected =
                StatefulAggregateRepository.from(eventStore,
                                                 configuration,
                                                 StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                                                 Order.class);

        StatefulAggregateRepository<OrderId, OrderEvent, Order> built =
                StatefulAggregateRepository.builder(eventStore)
                                           .setEventStreamConfiguration(configuration)
                                           .setAggregateImplementationType(Order.class)
                                           .build();

        assertThat((Object) built.aggregateType()).isEqualTo(expected.aggregateType());
        assertThat(built.aggregateIdType()).isEqualTo(expected.aggregateIdType());
        assertThat(built.aggregateRootImplementationType()).isEqualTo(expected.aggregateRootImplementationType());
        verify(eventStore, times(2)).addAggregateEventStreamConfiguration(configuration);
    }

    @Test
    void test_the_builder_resolves_the_aggregate_id_type_from_the_implementation_type() {
        StatefulAggregateRepository<OrderId, OrderEvent, Order> built =
                StatefulAggregateRepository.builder(eventStore)
                                           .setAggregateType(ORDERS)
                                           .setAggregateImplementationType(Order.class)
                                           .build();

        assertThat(built.aggregateIdType()).isEqualTo(OrderId.class);
    }

    @Test
    void test_an_explicit_aggregate_id_type_is_used_as_given() {
        StatefulAggregateRepository<OrderId, OrderEvent, Order> built =
                StatefulAggregateRepository.builder(eventStore)
                                           .setAggregateType(ORDERS)
                                           .setAggregateImplementationType(Order.class)
                                           .setAggregateIdType(OrderId.class)
                                           .build();

        assertThat(built.aggregateIdType()).isEqualTo(OrderId.class);
    }

    @Test
    void test_the_builder_resolves_a_present_snapshot_repository_provider() {
        var provider = mock(AggregateSnapshotRepositoryProvider.class);
        when(provider.resolve(ORDERS, Order.class)).thenReturn(Optional.of(mock(AggregateSnapshotRepository.class)));

        StatefulAggregateRepository<OrderId, OrderEvent, Order> built =
                StatefulAggregateRepository.builder(eventStore)
                                           .setAggregateType(ORDERS)
                                           .setAggregateImplementationType(Order.class)
                                           .setAggregateSnapshotRepositoryProvider(provider)
                                           .build();

        assertThat((Object) built.aggregateType()).isEqualTo(ORDERS);
        verify(provider).resolve(ORDERS, Order.class);
    }

    @Test
    void test_the_builder_leaves_snapshots_off_for_an_empty_provider_optional() {
        var provider = mock(AggregateSnapshotRepositoryProvider.class);

        StatefulAggregateRepository<OrderId, OrderEvent, Order> built =
                StatefulAggregateRepository.builder(eventStore)
                                           .setAggregateType(ORDERS)
                                           .setAggregateImplementationType(Order.class)
                                           .setAggregateSnapshotRepositoryProvider(Optional.empty())
                                           .build();

        assertThat((Object) built.aggregateType()).isEqualTo(ORDERS);
        verifyNoInteractions(provider);
    }

    @Test
    void test_the_builder_requires_an_aggregate_implementation_type() {
        assertThatThrownBy(() -> StatefulAggregateRepository.builder(eventStore)
                                                            .setAggregateType(ORDERS)
                                                            .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateImplementationType");
    }

    @Test
    void test_the_builder_requires_either_an_aggregate_type_or_an_event_stream_configuration() {
        assertThatThrownBy(() -> StatefulAggregateRepository.builder(eventStore)
                                                            .setAggregateImplementationType(Order.class)
                                                            .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Either an aggregateType or an eventStreamConfiguration");
    }

    @Test
    void test_the_builder_rejects_both_an_aggregate_type_and_an_event_stream_configuration() {
        assertThatThrownBy(() -> StatefulAggregateRepository.builder(eventStore)
                                                            .setAggregateType(ORDERS)
                                                            .setEventStreamConfiguration(configurationFor(ORDERS))
                                                            .setAggregateImplementationType(Order.class)
                                                            .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void test_the_builder_rejects_both_a_snapshot_repository_and_a_provider() {
        assertThatThrownBy(() -> StatefulAggregateRepository.builder(eventStore)
                                                            .setAggregateType(ORDERS)
                                                            .setAggregateImplementationType(Order.class)
                                                            .setAggregateSnapshotRepository(mock(AggregateSnapshotRepository.class))
                                                            .setAggregateSnapshotRepositoryProvider(mock(AggregateSnapshotRepositoryProvider.class))
                                                            .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    void test_the_builder_rejects_a_null_event_store() {
        assertThatThrownBy(() -> StatefulAggregateRepository.builder(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static SeparateTablePerAggregateEventStreamConfiguration configurationFor(AggregateType aggregateType) {
        return SeparateTablePerAggregateEventStreamConfiguration.standardSingleTenantConfiguration(
                aggregateType,
                mock(JSONEventSerializer.class),
                new AggregateIdSerializer.StringIdSerializer(),
                IdentifierColumnType.TEXT,
                JSONColumnType.JSONB);
    }
}
