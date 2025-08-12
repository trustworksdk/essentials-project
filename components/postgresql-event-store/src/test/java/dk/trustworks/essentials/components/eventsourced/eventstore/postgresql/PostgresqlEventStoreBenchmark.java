/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JacksonJSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test.TestPersistableEventMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.openjdk.jmh.annotations.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test.TestObjectMapperFactory.createObjectMapper;

@BenchmarkMode(Mode.Throughput)
@Fork(1)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(10)
public class PostgresqlEventStoreBenchmark {

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }

    @State(Scope.Benchmark)
    public static class PerformanceTestState {

        @Param({"1", "5", "10", "20"})
        public  int                                                                     appendedEvents;
        private PostgreSQLContainer<?>                                                  postgreSQLContainer;
        private HikariDataSource                                                        ds;
        public  AggregateType                                                           aggregateType;
        public  EventStoreManagedUnitOfWorkFactory                                      unitOfWorkFactory;
        public  PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
        public  AtomicLong                                                              orderNumber = new AtomicLong(0);

        @Setup(Level.Trial)
        public void trialSetUp() {
            System.out.println("Trial setup");
            postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
                    .withDatabaseName("event-store")
                    .withUsername("test-user")
                    .withPassword("secret-password");
            postgreSQLContainer.start();
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
            hikariConfig.setUsername(postgreSQLContainer.getUsername());
            hikariConfig.setPassword(postgreSQLContainer.getPassword());
            ds = new HikariDataSource(hikariConfig);
            var jdbi = Jdbi.create(ds);
            jdbi.installPlugin(new PostgresPlugin());
            jdbi.setSqlLogger(new SqlExecutionTimeLogger());

            aggregateType = AggregateType.of("Orders");
            unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
            var jsonSerializer = new JacksonJSONEventSerializer(createObjectMapper());
            var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                           unitOfWorkFactory,
                                                                                           new TestPersistableEventMapper(),
                                                                                           SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(jsonSerializer,
                                                                                                                                                                                          IdentifierColumnType.UUID,
                                                                                                                                                                                          JSONColumnType.JSONB));
            eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                    persistenceStrategy,
                                                    jsonSerializer);
            eventStore.addAggregateEventStreamConfiguration(aggregateType,
                                                            OrderId.class);
        }

        @TearDown(Level.Trial)
        public void trialTeardown() {
            System.out.println("Trial teardown");
            ds.close();
            postgreSQLContainer.stop();
        }
//
//        @Setup(Level.Invocation)
//        public void InvocationSetUp() {
//        }
//
//        @Setup(Level.Iteration)
//        public void IterationSetUp() {
//            System.out.println("Iteration setup: " + appendedEvents);
//        }
    }

    @Benchmark
    public void appendEvents(PerformanceTestState state) {
        state.unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var orderId      = OrderId.random();
            var appendEvents = new ArrayList<OrderEvent>();
            appendEvents.add(new OrderEvent.OrderAdded(orderId,
                                                       CustomerId.random(),
                                                       state.orderNumber.incrementAndGet()));
            for (var i = 0; i < state.appendedEvents; i++) {
                appendEvents.add(new OrderEvent.ProductAddedToOrder(orderId,
                                                                    ProductId.random(),
                                                                    2));
            }
            state.eventStore.appendToStream(state.aggregateType,
                                            orderId,
                                            appendEvents);
        });
    }

    @Benchmark
    public void appendEventsAndLoadEvents(PerformanceTestState state) {
        var orderId = OrderId.random();
        state.unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var appendEvents = new ArrayList<OrderEvent>();
            appendEvents.add(new OrderEvent.OrderAdded(orderId,
                                                       CustomerId.random(),
                                                       state.orderNumber.incrementAndGet()));
            for (var i = 0; i < state.appendedEvents; i++) {
                appendEvents.add(new OrderEvent.ProductAddedToOrder(orderId,
                                                                    ProductId.random(),
                                                                    2));
            }
            state.eventStore.appendToStream(state.aggregateType,
                                            orderId,
                                            appendEvents);
        });
        state.unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var eventStream = state.eventStore.fetchStream(state.aggregateType,
                                                           orderId).get();
            var events = eventStream.events().map(persistedEvent -> persistedEvent.event().getJsonDeserialized().get()).collect(Collectors.toList());
            if (events.size() != 1 + state.appendedEvents) {
                throw new RuntimeException("Loaded " + events.size() + " but expected " + (1 + state.appendedEvents));
            }
        });
    }

}
