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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stopping an application whose database has gone away used to take minutes, and a second Ctrl-C could not cut it short:
 * the lifecycle beans stop one after the other, each event processor released its fenced lock through a lock manager
 * that did not know the application was shutting down, and every database step waited out the connection pool's 30 s
 * timeout - queued behind the lock manager's own background ticks doing the same.
 * <p>
 * Each test starts its own PostgreSQL rather than sharing a static one, because each kills it part-way through.
 */
class ShutdownWithUnreachableDatabaseIT {
    static final AggregateType ORDERS   = AggregateType.of("Orders");
    static final AggregateType PAYMENTS = AggregateType.of("Payments");

    @Test
    void the_application_stops_within_the_shutdown_timeout_when_the_database_is_gone() {
        assertStopsWithin(Duration.ofSeconds(15), "essentials.life-cycles.shutdown-timeout=10s");
    }

    /**
     * The timeout is the backstop. With it out of reach, what remains is the shutdown-aware cleanup itself: the first
     * step to find the database unreachable gives up after its bounded attempt, and every later one is skipped.
     */
    @Test
    void the_cleanup_gives_up_on_an_unreachable_database_without_relying_on_the_shutdown_timeout() {
        assertStopsWithin(Duration.ofSeconds(15), "essentials.life-cycles.shutdown-timeout=5m");
    }

    private void assertStopsWithin(Duration limit, String shutdownTimeoutProperty) {
        try (var postgres = new PostgreSQLContainer("postgres:18.4")
                .withDatabaseName("shutdown-unreachable")
                .withUsername("test-user")
                .withPassword("secret-password")) {
            postgres.start();
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                             DataSourceTransactionManagerAutoConfiguration.class,
                                                             EssentialsComponentsConfiguration.class,
                                                             EventStoreConfiguration.class))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withUserConfiguration(ProcessorConfiguration.class)
                    .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                                        "spring.datasource.username=" + postgres.getUsername(),
                                        "spring.datasource.password=" + postgres.getPassword(),
                                        "essentials.eventstore.cdc.enabled=false",
                                        "essentials.life-cycles.start-life-cycles=true",
                                        shutdownTimeoutProperty)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        // Both of the processor's exclusive subscriptions hold their fenced lock, so stopping has
                        // locks to release - the case that hung
                        Awaitility.await()
                                  .atMost(Duration.ofSeconds(30))
                                  .until(() -> heldFencedLocks(postgres) >= 2);

                        postgres.stop();

                        var started = System.nanoTime();
                        context.close();
                        var took = Duration.ofNanos(System.nanoTime() - started);

                        assertThat(took).as("time to stop with the database gone").isLessThan(limit);
                    });
        }
    }

    private static long heldFencedLocks(PostgreSQLContainer postgres) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from fenced_locks where locked_by_lockmanager_instance_id is not null")) {
            result.next();
            return result.getLong(1);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProcessorConfiguration {
        @Bean
        OrderProcessor orderProcessor(EventProcessorDependencies dependencies,
                                      ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
            return new OrderProcessor(dependencies, eventStore);
        }
    }

    static class OrderProcessor extends EventProcessor {
        OrderProcessor(EventProcessorDependencies dependencies,
                       ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
            super(dependencies);
            eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
            eventStore.addAggregateEventStreamConfiguration(PAYMENTS, AggregateIdSerializer.serializerFor(String.class));
        }

        @Override
        public String getProcessorName() {
            return "OrderProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS, PAYMENTS);
        }

        @MessageHandler
        void on(OrderPlaced e) {
        }
    }

    record OrderPlaced(String orderId) {
    }
}
