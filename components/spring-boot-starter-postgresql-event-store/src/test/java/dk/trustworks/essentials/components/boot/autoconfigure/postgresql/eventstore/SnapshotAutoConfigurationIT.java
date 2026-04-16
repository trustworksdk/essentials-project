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
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SnapshotAutoConfigurationIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("snapshot-starter-test-db")
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
                            SnapshotConfiguration.class
                    ))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withUserConfiguration(SnapshotTestAggregatesConfiguration.class)
                    .withInitializer(ctx -> TestPropertyValues.of(
                            "spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.password=" + postgreSQLContainer.getPassword()
                    ).applyTo(ctx.getEnvironment()));

    @Test
    void sync_snapshot_mode_registers_store_repository_and_metadata_beans() {
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.snapshots.enabled=true",
                        "essentials.eventstore.snapshots.durable.enabled=false"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AggregateSnapshotPolicyRegistry.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotPolicyBeanPostProcessor.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotConfigurationResolver.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotStore.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotRepositoryProvider.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotRepository.class);
                    assertThat(ctx.getBean(AggregateSnapshotRepository.class)).isInstanceOf(PostgresqlAggregateSnapshotRepository.class);
                    assertThat(ctx).doesNotHaveBean(AggregateSnapshotJobRepository.class);
                    assertThat(ctx).doesNotHaveBean(DurableAsyncSnapshotManager.class);
                });
    }

    @Test
    void snapshots_disabled_does_not_register_snapshot_runtime_beans() {
        contextRunner
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AggregateSnapshotPolicyRegistry.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotPolicyBeanPostProcessor.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotConfigurationResolver.class);
                    assertThat(ctx).doesNotHaveBean(AggregateSnapshotStore.class);
                    assertThat(ctx).doesNotHaveBean(AggregateSnapshotRepositoryProvider.class);
                    assertThat(ctx).doesNotHaveBean(AggregateSnapshotRepository.class);
                    assertThat(ctx).doesNotHaveBean(AggregateSnapshotJobRepository.class);
                    assertThat(ctx).doesNotHaveBean(DurableAsyncSnapshotManager.class);
                });
    }

    @Test
    void durable_snapshot_mode_registers_job_processing_beans_and_durable_repository() {
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.snapshots.enabled=true",
                        "essentials.eventstore.snapshots.default-mode=async-durable"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AggregateSnapshotStore.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotJobRepository.class);
                    assertThat(ctx).hasSingleBean(PostgresqlAggregateSnapshotJobProcessor.class);
                    assertThat(ctx).hasSingleBean(DurableAsyncSnapshotManager.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotRepositoryProvider.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotRepository.class);
                    assertThat(ctx.getBean(AggregateSnapshotRepository.class)).isInstanceOf(DurableAsyncAggregateSnapshotRepository.class);
                });
    }

    @Test
    void user_provided_snapshot_repository_causes_starter_repository_to_back_off() {
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.snapshots.enabled=true",
                        "essentials.eventstore.snapshots.durable.enabled=false"
                )
                .withUserConfiguration(UserProvidedSnapshotRepositoryConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AggregateSnapshotRepository.class);
                    assertThat(ctx.getBean(AggregateSnapshotRepository.class)).isSameAs(ctx.getBean("customAggregateSnapshotRepository"));
                    assertThat(ctx).hasSingleBean(AggregateSnapshotStore.class);
                    assertThat(ctx).hasSingleBean(AggregateSnapshotRepositoryProvider.class);
                });
    }

    @Test
    void repository_provider_resolves_per_aggregate_repository_types() {
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.snapshots.enabled=true",
                        "essentials.eventstore.snapshots.default-mode=sync",
                        "essentials.eventstore.snapshots.aggregates.Orders.mode=async-durable",
                        "essentials.eventstore.snapshots.aggregates.Accounts.mode=async-in-memory"
                )
                .run(ctx -> {
                    var provider = ctx.getBean(AggregateSnapshotRepositoryProvider.class);

                    assertThat(provider.resolve(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType.of("Orders"),
                                                OrdersAggregate.class))
                            .isPresent()
                            .get()
                            .isInstanceOf(DurableAsyncAggregateSnapshotRepository.class);

                    assertThat(provider.resolve(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType.of("Accounts"),
                                                AccountsAggregate.class))
                            .isPresent()
                            .get()
                            .isInstanceOf(AsyncAggregateSnapshotRepository.class);

                    assertThat(provider.resolve(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType.of("Disabled"),
                                                DisabledAggregate.class))
                            .isEmpty();
                });
    }

    @Configuration
    static class SnapshotTestAggregatesConfiguration {
        @Bean
        OrdersAggregate ordersAggregate() {
            return new OrdersAggregate();
        }

        @Bean
        AccountsAggregate accountsAggregate() {
            return new AccountsAggregate();
        }

        @Bean
        DisabledAggregate disabledAggregate() {
            return new DisabledAggregate();
        }
    }

    @Configuration
    static class UserProvidedSnapshotRepositoryConfiguration {
        @Bean
        AggregateSnapshotRepository customAggregateSnapshotRepository() {
            return new AggregateSnapshotRepository() {
                @Override
                public <ID, AGGREGATE_IMPL_TYPE> Optional<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadSnapshot(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType aggregateType,
                                                                                                                    ID aggregateId,
                                                                                                                    Class<AGGREGATE_IMPL_TYPE> aggregateImplType) {
                    return Optional.empty();
                }

                @Override
                public <ID, AGGREGATE_IMPL_TYPE> Optional<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadSnapshot(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType aggregateType,
                                                                                                                    ID aggregateId,
                                                                                                                    dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder withLastIncludedEventOrderLessThanOrEqualTo,
                                                                                                                    Class<AGGREGATE_IMPL_TYPE> aggregateImplType) {
                    return Optional.empty();
                }

                @Override
                public <ID, AGGREGATE_IMPL_TYPE> List<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadAllSnapshots(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType aggregateType,
                                                                                                                    ID aggregateId,
                                                                                                                    Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                                                                                    boolean includeSnapshotPayload) {
                    return List.of();
                }

                @Override
                public <ID, AGGREGATE_IMPL_TYPE> void aggregateUpdated(AGGREGATE_IMPL_TYPE aggregate,
                                                                       dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateEventStream<ID> persistedEvents) {
                }

                @Override
                public <AGGREGATE_IMPL_TYPE> void deleteAllSnapshots(Class<AGGREGATE_IMPL_TYPE> aggregateImplType) {
                }

                @Override
                public <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType aggregateType,
                                                                      ID aggregateId,
                                                                      Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType) {
                }

                @Override
                public <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType aggregateType,
                                                                      ID aggregateId,
                                                                      Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType,
                                                                      List<dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder> snapshotEventOrdersToDelete) {
                }
            };
        }
    }

    @AggregateSnapshotPolicy(aggregateType = "Orders", mode = SnapshotExecutionMode.ASYNC_DURABLE, everyNEvents = 100)
    static class OrdersAggregate {
    }

    @AggregateSnapshotPolicy(aggregateType = "Accounts", mode = SnapshotExecutionMode.ASYNC_IN_MEMORY, everyNEvents = 50)
    static class AccountsAggregate {
    }

    @AggregateSnapshotPolicy(aggregateType = "Disabled", enabled = false)
    static class DisabledAggregate {
    }
}
