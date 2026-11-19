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

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.*;
import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.shared.security.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.BeanDefinitionOverrideException;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.components.boot.autoconfigure.eventlog.TestOrder.ORDERS;
import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * The starter in a running Spring context, beside the old event-store starter (on this module's test class path through
 * the admin API starter): every auto-configuration on the class path is imported, as in an application, so
 * {@link EventLogImportFilter} is consulted exactly as it is in production.
 */
@Testcontainers(disabledWithoutDocker = true)
class EventLogAutoConfigurationIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

    static final List<TestOrder.OrderPlaced> PROJECTED = new CopyOnWriteArrayList<>();
    static final List<TestOrder.OrderPlaced> ANNOTATED = new CopyOnWriteArrayList<>();

    @BeforeEach
    void emptyDatabase() throws SQLException {
        PROJECTED.clear();
        ANNOTATED.clear();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Application.class)
                .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "spring.datasource.username=" + postgres.getUsername(),
                                    "spring.datasource.password=" + postgres.getPassword());
    }

    private ApplicationContextRunner enabled() {
        return runner().withPropertyValues("essentials.event-log.enabled=true")
                       .withUserConfiguration(OrdersConfiguration.class);
    }

    /**
     * What "running on the new engine beside the old starter" means; the must-fail arm below shows it does not hold
     * without the import filter.
     */
    private static void assertRunsOnTheNewEngineOnly(AssertableApplicationContext ctx) {
        assertThat(ctx).hasNotFailed();
        assertThat(ctx).hasSingleBean(EventLog.class);
        assertThat(ctx.getBean("essentialsEventStore")).isInstanceOf(EventLogEventStore.class);
        assertThat(ctx).hasSingleBean(EventStore.class);
        assertThat(ctx).doesNotHaveBean(EventStoreSubscriptionManager.class);
        assertThat(ctx).doesNotHaveBean(EventStoreConfiguration.class);
    }

    @Test
    void enabled_beside_the_old_starter_runs_on_the_adapter_and_drops_the_old_configuration() {
        enabled().run(EventLogAutoConfigurationIT::assertRunsOnTheNewEngineOnly);
    }

    /**
     * Must-fail arm: the same assertion over the same auto-configurations, imported directly so that no
     * {@code AutoConfigurationImportFilter} is consulted, fails. Without the filter the old {@code EventStoreConfiguration}
     * is imported beside this starter: both declare {@code essentialsEventStore}, and the context does not even start.
     * So the assertion above holds because of the filter, not by accident of ordering or conditions.
     */
    @Test
    void without_the_import_filter_the_old_configuration_is_imported_and_the_assertion_fails() {
        runner().withPropertyValues("essentials.event-log.enabled=true")
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                         DataSourceTransactionManagerAutoConfiguration.class,
                                                         EssentialsComponentsConfiguration.class,
                                                         EventLogAutoConfiguration.class,
                                                         EventStoreConfiguration.class))
                .run(ctx -> {
                    assertThatThrownBy(() -> assertRunsOnTheNewEngineOnly(ctx)).isInstanceOf(AssertionError.class);
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining(BeanDefinitionOverrideException.class.getName())
                                                       .hasStackTraceContaining("'essentialsEventStore'")
                                                       .hasStackTraceContaining(EventStoreConfiguration.class.getName());
                });
    }

    @Test
    void an_append_through_a_repository_reaches_a_view_projection_bean() {
        enabled().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var unitOfWorkFactory = ctx.getBean(EventStoreUnitOfWorkFactory.class);
            @SuppressWarnings("unchecked")
            StatefulAggregateRepository<TestOrder.OrderId, Object, TestOrder> repository = ctx.getBean(StatefulAggregateRepository.class);
            var orderId = TestOrder.OrderId.random();

            unitOfWorkFactory.usingUnitOfWork(() -> repository.save(new TestOrder(orderId, 7)));

            assertThat(ctx.getBean("ordersView", ViewProjection.class).isRunning()).isTrue();
            await().atMost(Duration.ofSeconds(20))
                   .untilAsserted(() -> assertThat(PROJECTED).containsExactly(new TestOrder.OrderPlaced(orderId, 7)));
            // An EventLogViewProcessor bean is started by the components starter's lifecycle manager, on the starter's dependencies bean
            assertThat(ctx.getBean(OrdersAnnotatedView.class).isStarted()).isTrue();
            await().atMost(Duration.ofSeconds(20))
                   .untilAsserted(() -> assertThat(ANNOTATED).containsExactly(new TestOrder.OrderPlaced(orderId, 7)));
            var loaded = (TestOrder) unitOfWorkFactory.withUnitOfWork(() -> repository.load(orderId));
            assertThat(loaded.total).isEqualTo(7);
        });
    }

    @Test
    void snapshots_are_written_and_loaded_on_the_adapter() {
        enabled().withPropertyValues("essentials.eventstore.snapshots.enabled=true",
                                     "essentials.eventstore.snapshots.default-mode=sync",
                                     "essentials.eventstore.snapshots.default-every-n-events=2",
                                     "essentials.eventstore.snapshots.durable.enabled=false")
                 .run(ctx -> {
                     assertThat(ctx).hasNotFailed();
                     assertThat(ctx).hasSingleBean(AggregateSnapshotStore.class);
                     var snapshotRepository = ctx.getBean(AggregateSnapshotRepositoryProvider.class)
                                                 .resolve(ORDERS, TestOrder.class)
                                                 .orElseThrow();
                     @SuppressWarnings("unchecked")
                     ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> eventStore = ctx.getBean(ConfigurableEventStore.class);
                     StatefulAggregateRepository<TestOrder.OrderId, Object, TestOrder> repository =
                             StatefulAggregateRepository.from(eventStore, ORDERS, reflectionBasedAggregateRootFactory(),
                                                              TestOrder.OrderId.class, TestOrder.class, snapshotRepository);
                     var unitOfWorkFactory = ctx.getBean(EventStoreUnitOfWorkFactory.class);
                     var orderId = TestOrder.OrderId.random();

                     unitOfWorkFactory.usingUnitOfWork(() -> repository.save(new TestOrder(orderId, 1)));
                     unitOfWorkFactory.usingUnitOfWork(() -> {
                         var order = repository.load(orderId);
                         order.add(2);
                         order.add(3);
                     });

                     var snapshot = snapshotRepository.loadSnapshot(ORDERS, orderId, TestOrder.class);
                     assertThat(snapshot).isPresent();
                     assertThat(snapshot.get().aggregateSnapshot.total).isEqualTo(6);
                     assertThat(snapshot.get().eventOrderOfLastIncludedEvent).isEqualTo(EventOrder.of(2));
                     var loaded = unitOfWorkFactory.withUnitOfWork(() -> repository.load(orderId));
                     assertThat(((TestOrder) loaded).total).isEqualTo(6);
                 });
    }

    @Test
    void an_unported_old_processor_bean_fails_start_up_naming_it_before_its_missing_dependencies() {
        // ESS-138: the guard runs before any bean is created, so the missing ViewEventProcessorDependencies is never reached
        enabled().withUserConfiguration(OldProcessorConfiguration.class)
                 .run(ctx -> assertThat(ctx.getStartupFailure())
                         .isInstanceOf(IllegalStateException.class)
                         .hasMessage("Bean 'orderProjection' (" + UnportedEventProcessorGuardTest.OrderProjection.class.getName() + ") is an old-engine ViewEventProcessor. " +
                                             "With essentials.event-log.enabled=true it must be ported to EventLogViewProcessor - " +
                                             "see LLM-postgresql-event-log.md § Porting from the EventProcessor family."));
    }

    @Configuration(proxyBeanMethods = false)
    static class OldProcessorConfiguration {
        @Bean
        UnportedEventProcessorGuardTest.OrderProjection orderProjection(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies dependencies) {
            throw new AssertionError("an old processor must never be created");
        }
    }

    @Test
    void closing_books_configuration_starts_on_the_adapter() {
        enabled().withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                 .run(ctx -> {
                     assertRunsOnTheNewEngineOnly(ctx);
                     assertThat(ctx).hasSingleBean(ClosingBooksConfiguration.class);
                 });
    }

    @Test
    void disabled_leaves_the_old_event_store_in_place() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EventLog.class);
            assertThat(ctx).doesNotHaveBean(EventLogAutoConfiguration.class);
            assertThat(ctx.getBean(EventStore.class)).isNotInstanceOf(EventLogEventStore.class);
            assertThat(ctx).hasSingleBean(EventStoreSubscriptionManager.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
    }

    @Configuration(proxyBeanMethods = false)
    static class OrdersConfiguration {
        @Bean
        StatefulAggregateRepository<TestOrder.OrderId, Object, TestOrder> ordersRepository(ConfigurableEventStore<EventLogAggregateEventStreamConfiguration> eventStore) {
            return StatefulAggregateRepository.from(eventStore, ORDERS, reflectionBasedAggregateRootFactory(), TestOrder.OrderId.class, TestOrder.class);
        }

        @Bean
        OrdersAnnotatedView ordersAnnotatedView(EventLogProcessorDependencies dependencies) {
            return new OrdersAnnotatedView(dependencies);
        }

        @Bean
        ViewProjection ordersView(EventLog eventLog, JSONEventSerializer serializer, PageTransactionSource transactions) {
            return ViewProjection.builder("orders-view", ORDERS.toString())
                                 .on(TestOrder.OrderPlaced.class, (event, context) -> PROJECTED.add(event.payloadAs(TestOrder.OrderPlaced.class)))
                                 .transactions(transactions)
                                 .build(eventLog, serializer);
        }
    }

    /** An annotated view processor bean (phase 10 §3.5) */
    static class OrdersAnnotatedView extends EventLogViewProcessor {
        OrdersAnnotatedView(EventLogProcessorDependencies dependencies) {
            super("orders-annotated-view", List.of(ORDERS.toString()), dependencies);
        }

        @MessageHandler
        void on(TestOrder.OrderPlaced placed, DeliveredEvent event) {
            ANNOTATED.add(placed);
        }
    }
}
