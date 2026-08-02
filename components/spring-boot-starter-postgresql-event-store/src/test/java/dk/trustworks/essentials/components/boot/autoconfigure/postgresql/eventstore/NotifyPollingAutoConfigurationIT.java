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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamPersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify.NotifyEpochSource;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring autoconfig wiring tests for S1 (NOTIFY-driven polling wake-up).
 * <p>
 * Asserts that:
 * <ul>
 *   <li>With notify-polling DISABLED (the default), no S1 beans are created.</li>
 *   <li>With notify-polling ENABLED, {@link NotifyEpochSource} and
 *       {@link EventStoreNotifyPollingBootstrap} are wired with the correct collaborators.</li>
 *   <li>Property binding picks up custom backoff durations on
 *       {@code essentials.eventstore.subscription-manager.notify-polling.*}.</li>
 *   <li>Spring-managed end-to-end: registering an aggregate after enable installs a
 *       trigger and persisting an event advances the {@link NotifyEpochSource} epoch
 *       through the framework's {@link MultiTableChangeListener}.</li>
 *   <li>CDC + notify-polling coexistence: both bean graphs build cleanly (operator-only
 *       configuration, validated by a WARN at startup which is logged, not asserted here).</li>
 * </ul>
 */
@Testcontainers
public class NotifyPollingAutoConfigurationIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("notify-polling-autoconfig-it")
            .withUsername("test-user")
            .withPassword("secret-password");

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            DataSourceAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class,
                            EssentialsComponentsConfiguration.class,
                            EventStoreConfiguration.class
                    ))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                            "essentials.life-cycles.start-life-cycles=false"
                    ).applyTo(ctx.getEnvironment()))
                    .withPropertyValues("essentials.eventstore.cdc.enabled=false");

    @Test
    void notifyPolling_disabledByDefault_noS1BeansArePresent() {
        contextRunner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(NotifyEpochSource.class);
            assertThat(ctx).doesNotHaveBean(EventStoreNotifyPollingBootstrap.class);
            // Defaults still bind so operators can flip enabled at runtime via config.
            var props = ctx.getBean(EssentialsEventStoreProperties.class)
                           .getSubscriptionManager()
                           .getNotifyPolling();
            assertThat(props.isEnabled()).isFalse();
            assertThat(props.getInitialDelay()).isEqualTo(Duration.ofMillis(50));
            assertThat(props.getMaxDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(props.getBackoffMultiplier()).isEqualTo(2.0d);
        });
    }

    @Test
    void notifyPolling_enabled_wiresEpochSourceAndBootstrap() {
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.subscription-manager.notify-polling.enabled=true"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(NotifyEpochSource.class);
                    assertThat(ctx).hasSingleBean(EventStoreNotifyPollingBootstrap.class);
                    var listener = (MultiTableChangeListener<?>) ctx.getBean(MultiTableChangeListener.class);
                    assertThat(listener).isNotNull();
                    assertThat(listener.getEventBus()).isNotNull();
                });
    }

    @Test
    void notifyPolling_propertiesBindFromConfiguration() {
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.subscription-manager.notify-polling.enabled=true",
                        "essentials.eventstore.subscription-manager.notify-polling.initial-delay=PT0.020S",
                        "essentials.eventstore.subscription-manager.notify-polling.max-delay=PT0.500S",
                        "essentials.eventstore.subscription-manager.notify-polling.backoff-multiplier=1.5"
                )
                .run(ctx -> {
                    var props = ctx.getBean(EssentialsEventStoreProperties.class)
                                   .getSubscriptionManager()
                                   .getNotifyPolling();
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getInitialDelay()).isEqualTo(Duration.ofMillis(20));
                    assertThat(props.getMaxDelay()).isEqualTo(Duration.ofMillis(500));
                    assertThat(props.getBackoffMultiplier()).isEqualTo(1.5d);
                });
    }

    @Test
    void notifyPolling_enabled_persistedEventAdvancesEpochThroughSpringContext() throws Exception {
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.subscription-manager.notify-polling.enabled=true",
                        // Tight MultiTableChangeListener poll so the test stays under the 5s budget.
                        "essentials.components.multi-table-change-listener.polling-interval=PT0.050S",
                        "essentials.life-cycles.start-life-cycles=true"
                )
                .run(ctx -> {
                    var epochSource         = ctx.getBean(NotifyEpochSource.class);
                    @SuppressWarnings("unchecked")
                    var persistenceStrategy = (AggregateEventStreamPersistenceStrategy<SeparateTablePerAggregateEventStreamConfiguration>)
                            ctx.getBean(AggregateEventStreamPersistenceStrategy.class);
                    @SuppressWarnings("unchecked")
                    var unitOfWorkFactory   = (EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>)
                            ctx.getBean(EventStoreUnitOfWorkFactory.class);

                    // Registering the aggregate must go through the installer wired by the
                    // EventStoreNotifyPollingBootstrap bean. Verify: tableName = aggregateType
                    // lowercased + "_events" (the standard factory's naming convention).
                    // UUID hyphens are invalid in SQL identifiers — strip them so the
                    // table name matches Postgres' identifier rules.
                    var aggregateType = AggregateType.of("ContextAggregate"
                                                                 + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                    var tableName     = aggregateType.toString().toLowerCase() + "_events";
                    persistenceStrategy.addAggregateEventStreamConfiguration(
                            aggregateType,
                            AggregateIdSerializer.serializerFor(String.class));

                    // Baseline: no notifications yet for this table.
                    assertThat(epochSource.currentEpoch(tableName)).isZero();

                    // Persist an event — pg_notify trigger fires, MultiTableChangeListener
                    // forwards onto the EventBus, NotifyEpochSource bumps the counter.
                    var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
                    persistenceStrategy.persist(unitOfWork,
                                                aggregateType,
                                                "test-id-1",
                                                java.util.Optional.empty(),
                                                java.util.List.of(new TestEvent("first")));
                    unitOfWork.commit();

                    // Wait for the epoch to advance — listener poll + bus dispatch latency.
                    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (epochSource.currentEpoch(tableName) < 1L && System.nanoTime() < deadline) {
                        Thread.sleep(25);
                    }
                    assertThat(epochSource.currentEpoch(tableName))
                            .as("Persisting an event should advance the notify epoch for table='%s'", tableName)
                            .isGreaterThanOrEqualTo(1L);

                    // Tidy: roll back any lingering UoW so the context shuts down cleanly.
                    unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
                });
    }

    @Test
    void notifyPolling_andCdc_canCoexist() {
        // The coexistence WARN is logged (verified by reading the test output); we assert
        // here that both bean graphs build cleanly without overriding each other — operators
        // intentionally running both during migration windows must not see startup failures.
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.subscription-manager.notify-polling.enabled=true",
                        "essentials.eventstore.cdc.enabled=true"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(NotifyEpochSource.class);
                    assertThat(ctx).hasSingleBean(EventStoreNotifyPollingBootstrap.class);
                    // The CDC bean graph should also have constructed (smoke check via
                    // CdcEnabled property still reading true on the bound config).
                    var cdc = ctx.getBean(EssentialsEventStoreProperties.class).getCdc();
                    assertThat(cdc.isEnabled()).isTrue();
                });
    }

    /**
     * Simple POJO event payload — kept inside the test so the autoconfig module doesn't
     * pick up an unrelated test-data dependency from the event-store module.
     */
    public static final class TestEvent {
        public final String name;

        public TestEvent() {
            this.name = null;
        }

        public TestEvent(String name) {
            this.name = name;
        }
    }
}
