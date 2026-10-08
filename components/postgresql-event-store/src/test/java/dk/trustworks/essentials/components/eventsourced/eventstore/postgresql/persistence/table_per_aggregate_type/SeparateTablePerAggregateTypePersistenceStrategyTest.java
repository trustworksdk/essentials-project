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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.schema.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeparateTablePerAggregateTypePersistenceStrategyTest {
    @Test
    void test_SeparateTablePerAggregateTypePersistenceStrategy_validates_eventStreamTableName_and_EventStreamTableColumnNames_validate_is_called() {
        try (var postgresqlUtilMock = mockStatic(PostgresqlUtil.class)) {
            var eventStreamTableColumnNames = spy(EventStreamTableColumnNames.defaultColumnNames());
            new SeparateTablePerAggregateTypePersistenceStrategy(mock(Jdbi.class),
                                                                 mock(EventStoreUnitOfWorkFactory.class),
                                                                 mock(PersistableEventMapper.class),
                                                                 mock(AggregateEventStreamConfigurationFactory.class),
                                                                 new SeparateTablePerAggregateEventStreamConfiguration(
                                                                         mock(AggregateType.class),
                                                                         "valid_name",
                                                                         eventStreamTableColumnNames,
                                                                         1,
                                                                         mock(JSONEventSerializer.class),
                                                                         mock(AggregateIdSerializer.class),
                                                                         IdentifierColumnType.UUID,
                                                                         IdentifierColumnType.UUID,
                                                                         IdentifierColumnType.UUID,
                                                                         JSONColumnType.JSON,
                                                                         JSONColumnType.JSON,
                                                                         mock(TenantSerializer.TenantIdSerializer.class)
                                                                 ));

            // The name is validated 5 times, the column names 3 times:
            // SeparateTablePerAggregateEventStreamConfiguration.<init>
            // SeparateTablePerAggregateTypePersistenceStrategy.addAggregateEventStreamConfiguration
            // SeparateTablePerAggregateTypePersistenceStrategy.schemaChangesFor
            // PostgresqlCreateSchemaApplier.apply - the name only, once per change (table and tenant index)
            postgresqlUtilMock.verify(() -> PostgresqlUtil.checkIsValidTableOrColumnName("valid_name"), times(5));
            verify(eventStreamTableColumnNames, times(3)).validate();
        }
    }

    @Test
    void test_addAggregateEventStreamConfiguration_validates_eventStreamTableName_and_EventStreamTableColumnNames_validate_is_called() {
        try (var postgresqlUtilMock = mockStatic(PostgresqlUtil.class)) {
            var eventStreamTableColumnNames = spy(EventStreamTableColumnNames.defaultColumnNames());
            var strategy = new SeparateTablePerAggregateTypePersistenceStrategy(mock(Jdbi.class),
                                                                                mock(EventStoreUnitOfWorkFactory.class),
                                                                                mock(PersistableEventMapper.class),
                                                                                mock(AggregateEventStreamConfigurationFactory.class)
            );
            strategy.addAggregateEventStreamConfiguration(
                    new SeparateTablePerAggregateEventStreamConfiguration(
                            mock(AggregateType.class),
                            "valid_name",
                            eventStreamTableColumnNames,
                            1,
                            mock(JSONEventSerializer.class),
                            mock(AggregateIdSerializer.class),
                            IdentifierColumnType.UUID,
                            IdentifierColumnType.UUID,
                            IdentifierColumnType.UUID,
                            JSONColumnType.JSON,
                            JSONColumnType.JSON,
                            mock(TenantSerializer.TenantIdSerializer.class)
                    ));

            // The name is validated 5 times, the column names 3 times:
            // SeparateTablePerAggregateEventStreamConfiguration.<init>
            // SeparateTablePerAggregateTypePersistenceStrategy.addAggregateEventStreamConfiguration
            // SeparateTablePerAggregateTypePersistenceStrategy.schemaChangesFor
            // PostgresqlCreateSchemaApplier.apply - the name only, once per change (table and tenant index)
            postgresqlUtilMock.verify(() -> PostgresqlUtil.checkIsValidTableOrColumnName("valid_name"), times(5));
            verify(eventStreamTableColumnNames, times(3)).validate();
        }
    }

    @Test
    void test_resetEventStorageFor_validates_eventStreamTableName_and_EventStreamTableColumnNames_validate_is_called() {
        try (var postgresqlUtilMock = mockStatic(PostgresqlUtil.class)) {
            var eventStreamTableColumnNames = spy(EventStreamTableColumnNames.defaultColumnNames());
            var strategy = new SeparateTablePerAggregateTypePersistenceStrategy(mock(Jdbi.class),
                                                                                mock(EventStoreUnitOfWorkFactory.class),
                                                                                mock(PersistableEventMapper.class),
                                                                                mock(AggregateEventStreamConfigurationFactory.class)
            );
            strategy.resetEventStorageFor(
                    new SeparateTablePerAggregateEventStreamConfiguration(
                            mock(AggregateType.class),
                            "valid_name",
                            eventStreamTableColumnNames,
                            1,
                            mock(JSONEventSerializer.class),
                            mock(AggregateIdSerializer.class),
                            IdentifierColumnType.UUID,
                            IdentifierColumnType.UUID,
                            IdentifierColumnType.UUID,
                            JSONColumnType.JSON,
                            JSONColumnType.JSON,
                            mock(TenantSerializer.TenantIdSerializer.class)
                    ));

            // The name is validated 5 times, the column names 3 times:
            // SeparateTablePerAggregateEventStreamConfiguration.<init>
            // SeparateTablePerAggregateTypePersistenceStrategy.resetEventStorageFor
            // SeparateTablePerAggregateTypePersistenceStrategy.schemaChangesFor
            // PostgresqlCreateSchemaApplier.apply - the name only, once per change (table and tenant index)
            postgresqlUtilMock.verify(() -> PostgresqlUtil.checkIsValidTableOrColumnName("valid_name"), times(5));
            verify(eventStreamTableColumnNames, times(3)).validate();
        }
    }

    @Test
    void an_aggregate_type_whose_storage_initialisation_fails_is_not_registered_and_a_retry_initialises_it_again() {
        var strategy = harnessOwnedStrategy();
        var applied  = new AtomicInteger();
        strategy.attach(changes -> {
            if (applied.incrementAndGet() == 1) {
                throw new SchemaValidationException("Schema mismatch", List.of("orders_events differs"));
            }
        });
        var configuration = configurationFor(AggregateType.of("Orders"), "orders_events");

        assertThatThrownBy(() -> strategy.addAggregateEventStreamConfiguration(configuration))
                .isInstanceOf(SchemaValidationException.class);
        assertThat(strategy.findAggregateEventStreamConfiguration(configuration.aggregateType)).isEmpty();
        assertThat(strategy.contribute(mock(SchemaContext.class))).isEmpty();

        strategy.addAggregateEventStreamConfiguration(configuration);

        assertThat(applied).hasValue(2);
        assertThat(strategy.findAggregateEventStreamConfiguration(configuration.aggregateType)).containsSame(configuration);
    }

    @Test
    void concurrent_registrations_of_one_aggregate_type_initialise_its_storage_once() throws Exception {
        var strategy = harnessOwnedStrategy();
        var applied  = new AtomicInteger();
        strategy.attach(changes -> applied.incrementAndGet());
        var aggregateType = AggregateType.of("Orders");
        var threads       = 8;
        var start         = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var registrations = new ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                var configuration = configurationFor(aggregateType, "orders_events");
                registrations.add(executor.submit(() -> {
                    start.await();
                    return strategy.addAggregateEventStreamConfiguration(configuration);
                }));
            }
            start.countDown();
            for (var registration : registrations) {
                registration.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(applied).hasValue(1);
        assertThat(strategy.findAggregateEventStreamConfiguration(aggregateType)).isPresent();
    }

    @Test
    void a_validation_failure_names_the_aggregate_type_and_its_table_and_says_how_to_fix_it() {
        var strategy = harnessOwnedStrategy();
        var problems = List.of("'postgresql-event-store' change 'event-stream-table' on 'orders_events' (AggregateType 'Orders'): not applied");
        strategy.attach(changes -> {
            throw new SchemaValidationException("The database schema does not match", problems);
        });

        assertThatThrownBy(() -> strategy.addAggregateEventStreamConfiguration(configurationFor(AggregateType.of("Orders"), "orders_events")))
                .isInstanceOfSatisfying(SchemaValidationException.class, e -> {
                    assertThat(e.problems()).isEqualTo(problems);
                    assertThat(e.getCause()).isInstanceOf(SchemaValidationException.class);
                })
                .hasMessageContaining("The event-stream table 'orders_events' of AggregateType 'Orders' is not in the database as described")
                .hasMessageContaining("register 'Orders' at start-up")
                .hasMessageContaining("re-run the emit mode")
                .hasMessageContaining("The database schema does not match");
    }

    @Test
    void every_schema_change_names_its_aggregate_type() {
        var strategy = harnessOwnedStrategy();
        strategy.enableCausationIndex();
        strategy.addAggregateEventStreamConfiguration(configurationFor(AggregateType.of("Orders"), "orders_events"));
        strategy.addAggregateEventStreamConfiguration(configurationFor(AggregateType.of("Products"), "products_events"));

        var changes = strategy.contribute(mock(SchemaContext.class));

        assertThat(changes).hasSize(6)
                           .allSatisfy(change -> assertThat(change.description()).isEqualTo(change.objectName().equals("orders_events")
                                                                                            ? "AggregateType 'Orders'"
                                                                                            : "AggregateType 'Products'"));
    }

    private static SeparateTablePerAggregateTypePersistenceStrategy harnessOwnedStrategy() {
        return SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                               .setJdbi(mock(Jdbi.class))
                                                               .setUnitOfWorkFactory(mock(EventStoreUnitOfWorkFactory.class))
                                                               .setEventMapper(mock(PersistableEventMapper.class))
                                                               .setAggregateEventStreamConfigurationFactory(mock(AggregateEventStreamConfigurationFactory.class))
                                                               .setSchemaOwnership(SchemaOwnership.HARNESS)
                                                               .build();
    }

    private static SeparateTablePerAggregateEventStreamConfiguration configurationFor(AggregateType aggregateType, String tableName) {
        return new SeparateTablePerAggregateEventStreamConfiguration(aggregateType,
                                                                     tableName,
                                                                     EventStreamTableColumnNames.defaultColumnNames(),
                                                                     1,
                                                                     mock(JSONEventSerializer.class),
                                                                     mock(AggregateIdSerializer.class),
                                                                     IdentifierColumnType.UUID,
                                                                     IdentifierColumnType.UUID,
                                                                     IdentifierColumnType.UUID,
                                                                     JSONColumnType.JSON,
                                                                     JSONColumnType.JSON,
                                                                     mock(TenantSerializer.TenantIdSerializer.class));
    }
}
