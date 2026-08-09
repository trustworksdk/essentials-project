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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.health.contributor.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class StarterAutoConfigurationIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("starter-test-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
    }

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            DataSourceAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class,
                            EssentialsComponentsConfiguration.class,
                            EventStoreConfiguration.class,
                            SnapshotConfiguration.class,
                            ClosingBooksConfiguration.class,
                            AggregateLifecycleApiConfiguration.class,
                            AggregateArchiveApiConfiguration.class
                    ))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                            "essentials.eventstore.archives.enabled=true",
                            "essentials.life-cycles.start-life-cycles=false"
                    ).applyTo(ctx.getEnvironment())); // needed

    /**
     * CDC is opt-in: every CDC bean is gated on {@code essentials.eventstore.cdc.enabled=true} with no
     * {@code matchIfMissing}. Tests that assert on CDC beans must therefore opt in exactly as a real
     * application does — see {@link #cdc_beans_are_absent_unless_explicitly_enabled()} for the other half
     * of that contract.
     */
    private final ApplicationContextRunner cdcEnabledContextRunner =
            contextRunner.withPropertyValues("essentials.eventstore.cdc.enabled=true");

    /**
     * CDC is opt-in. An application that says nothing about CDC must get no CDC pipeline at all — no
     * tailer, no dispatcher, no availability state, and therefore no replication slot created and no
     * publication touched on its database. This is the half of the contract that is easy to break by
     * adding a new CDC bean without the gate, so it is asserted rather than assumed.
     */
    @Test
    void cdc_beans_are_absent_unless_explicitly_enabled() {
        contextRunner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(CdcAvailability.class);
            assertThat(ctx).doesNotHaveBean(CdcApi.class);
            assertThat(ctx).doesNotHaveBean(WalReplicationTailer.class);
            assertThat(ctx).doesNotHaveBean(CdcDispatcher.class);
            assertThat(ctx).doesNotHaveBean(CdcEventStore.class);
            assertThat(ctx).doesNotHaveBean("configuredLogicalDecodingPlugin");

            // the event store itself must still be fully wired — CDC is an accelerator, not a dependency
            assertThat(ctx).hasSingleBean(EventStoreApi.class);
        });
    }

    @Test
    void verify_api_beans() {
        cdcEnabledContextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EventStoreApi.class);
            EventStoreApi eventStoreApi = ctx.getBean(EventStoreApi.class);
            assertThat(eventStoreApi.findAllSubscriptions("principal")).isNotNull();

            assertThat(ctx).hasSingleBean(CdcApi.class);
            CdcApi cdcApi = ctx.getBean(CdcApi.class);
            var cdcStatus = cdcApi.getStatus("principal");
            assertThat(cdcStatus).isNotNull();
            assertThat(cdcStatus.availability()).isNotNull();
            assertThat(cdcStatus.configuration()).isNotNull();
            assertThat(cdcStatus.slot()).isNotNull();
            assertThat(cdcStatus.configuration().enabled()).isTrue();

            assertThat(ctx).hasSingleBean(PostgresqlEventStoreStatisticsApi.class);
            PostgresqlEventStoreStatisticsApi postgresqlEventStoreStatisticsApi = ctx.getBean(PostgresqlEventStoreStatisticsApi.class);
            assertThat(postgresqlEventStoreStatisticsApi.fetchTableActivityStatistics("principal")).isNotNull();

            assertThat(ctx).hasSingleBean(dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleApi.class);
            assertThat(ctx).hasSingleBean(dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleStatisticsApi.class);
            assertThat(ctx).hasSingleBean(dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateArchiveApi.class);
            assertThat(ctx).hasSingleBean(dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateArchiveStatisticsApi.class);
            assertThat(ctx).hasSingleBean(dk.trustworks.essentials.components.eventsourced.aggregates.archive.AggregateArchiveExporter.class);
            assertThat(ctx).hasSingleBean(dk.trustworks.essentials.components.eventsourced.aggregates.archive.AggregateArchiveDestination.class);
            assertThat(ctx).hasSingleBean(dk.trustworks.essentials.components.eventsourced.aggregates.archive.AggregateGenerationArchiver.class);
        });
    }

    @Test
    void configures_pgoutput_plugin_and_exposes_publication_in_cdc_api() {
        cdcEnabledContextRunner
                .withPropertyValues(
                        "essentials.eventstore.cdc.plugin=pgoutput",
                        "essentials.eventstore.cdc.pg-output.publication-name=essentials_cdc_publication"
                )
                .run(ctx -> {
                    assertThat(ctx).hasBean("configuredLogicalDecodingPlugin");
                    var plugin = ctx.getBean("configuredLogicalDecodingPlugin", LogicalDecodingPlugin.class);
                    assertThat(plugin).isInstanceOf(PgOutputLogicalDecodingPlugin.class);
                    assertThat(plugin.pluginName()).isEqualTo(PgOutputLogicalDecodingPlugin.PLUGIN_NAME);

                    var cdcStatus = ctx.getBean(CdcApi.class).getStatus("principal");
                    assertThat(cdcStatus.configuration().plugin()).isEqualTo(PgOutputLogicalDecodingPlugin.PLUGIN_NAME);
                    assertThat(cdcStatus.configuration().pgOutputPublicationName()).isEqualTo("essentials_cdc_publication");
                });
    }

    /**
     * The {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver}
     * SPI has a single slot, so collecting subscription statistics must compose with the Micrometer observer instead of
     * replacing it - and there must still be exactly one observer bean, or {@code eventStore(...)} cannot be injected.
     */
    @Test
    void subscription_statistics_are_collected_by_decorating_the_measurement_observer() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(SubscriptionStatisticsRegistry.class);
            assertThat(ctx).hasSingleBean(EventStoreSubscriptionObserver.class);

            var observer = ctx.getBean(EventStoreSubscriptionObserver.class);
            assertThat(observer).isInstanceOf(StatisticsCollectingEventStoreSubscriptionObserver.class);
            assertThat(((StatisticsCollectingEventStoreSubscriptionObserver) observer).getDelegate())
                    .isInstanceOf(MeasurementEventStoreSubscriptionObserver.class);

            assertThat(ctx.getBean(EventStoreApi.class).findAllSubscriptionStatistics("principal")).isNotNull();
        });
    }

    @Test
    void statistics_collection_can_be_turned_off_leaving_the_measurement_observer_in_place() {
        contextRunner
                .withPropertyValues("essentials.eventstore.subscription-manager.statistics.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(SubscriptionStatisticsRegistry.class);
                    assertThat(ctx.getBean(EventStoreSubscriptionObserver.class))
                            .isInstanceOf(MeasurementEventStoreSubscriptionObserver.class);
                    assertThat(ctx.getBean(EventStoreApi.class).findAllSubscriptionStatistics("principal")).isEmpty();
                });
    }

    @Test
    void verify_essentials_properties() {
        contextRunner
                .withPropertyValues("essentials.event-store.use-event-stream-gap-handler=true")
                .run(ctx -> {
                    EssentialsEventStoreProperties props = ctx.getBean(EssentialsEventStoreProperties.class);
                    assertThat(props.isUseEventStreamGapHandler()).isTrue();
                });
    }

    @Test
    void cdc_health_is_up_in_auto_mode_when_cdc_has_failed() {
        cdcEnabledContextRunner
                .withPropertyValues("essentials.eventstore.cdc.mode=AUTO")
                .run(ctx -> {
                    assertThat(ctx).hasBean("cdcHealthIndicator");
                    var availability = ctx.getBean(CdcAvailability.class);
                    availability.failed("slot-it", "wal2json plugin not usable");

                    var health = ctx.getBean("cdcHealthIndicator", HealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("state", CdcAvailability.State.FAILED.name());
                    assertThat(health.getDetails()).containsEntry("mode", CdcMode.AUTO.name());
                });
    }

    @Test
    void cdc_health_is_down_in_require_mode_when_cdc_has_failed() {
        cdcEnabledContextRunner
                .withPropertyValues("essentials.eventstore.cdc.mode=REQUIRE")
                .run(ctx -> {
                    assertThat(ctx).hasBean("cdcHealthIndicator");
                    var availability = ctx.getBean(CdcAvailability.class);
                    availability.failed("slot-it", "wal2json plugin not usable");

                    var health = ctx.getBean("cdcHealthIndicator", HealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("state", CdcAvailability.State.FAILED.name());
                    assertThat(health.getDetails()).containsEntry("mode", CdcMode.REQUIRE.name());
                });
    }

    @Test
    void cdc_availability_metrics_are_exposed_and_updated() {
        cdcEnabledContextRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> {
                    var availability = ctx.getBean(CdcAvailability.class);
                    var meterRegistry = ctx.getBean(MeterRegistry.class);

                    // Initial state is INACTIVE
                    assertThat(meterRegistry.get("essentials.cdc.active").gauge().value()).isEqualTo(0.0d);

                    availability.active("slot-it");
                    assertThat(meterRegistry.get("essentials.cdc.active").gauge().value()).isEqualTo(1.0d);

                    availability.failed("slot-it", "wal2json plugin not usable");
                    assertThat(meterRegistry.get("essentials.cdc.active").gauge().value()).isEqualTo(0.0d);

                    // start_failures_total is always reason-tagged; the per-reason series carries
                    // the failure (a "none" baseline series is registered at startup).
                    assertThat(meterRegistry.get("essentials.cdc.start_failures_total")
                                            .tag("reason", "wal2json_plugin_not_usable")
                                            .counter()
                                            .count())
                            .isGreaterThanOrEqualTo(1.0d);

                    availability.fallbackUsed();
                    assertThat(meterRegistry.get("essentials.cdc.fallback_total").counter().count())
                            .isGreaterThanOrEqualTo(1.0d);
                });
    }
}
