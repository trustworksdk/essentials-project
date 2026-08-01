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

package dk.trustworks.essentials.components.boot.autoconfigure.admin.api;

import dk.trustworks.essentials.components.adminapi.rest.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.foundation.fencedlock.api.DBFencedLockApi;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.DurableQueuesApi;
import dk.trustworks.essentials.components.foundation.postgresql.api.PostgresqlQueryStatisticsApi;
import dk.trustworks.essentials.components.foundation.scheduler.api.SchedulerApi;
import dk.trustworks.essentials.shared.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wiring of the admin API auto-configuration. No database is involved — the SPI beans are mocked, which is all the
 * HTTP layer needs.
 */
class EssentialsAdminApiAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(EssentialsAdminApiAutoConfiguration.class))
                    .withBean(DBFencedLockApi.class, () -> mock(DBFencedLockApi.class))
                    .withBean(SchedulerApi.class, () -> mock(SchedulerApi.class))
                    .withBean(PostgresqlQueryStatisticsApi.class, () -> mock(PostgresqlQueryStatisticsApi.class))
                    .withBean(DurableQueuesApi.class, () -> mock(DurableQueuesApi.class))
                    .withBean(EventStoreApi.class, () -> mock(EventStoreApi.class))
                    .withBean(CdcApi.class, () -> mock(CdcApi.class))
                    .withBean(PostgresqlEventStoreStatisticsApi.class, () -> mock(PostgresqlEventStoreStatisticsApi.class))
                    .withBean(EssentialsAuthenticatedUser.class, EssentialsAuthenticatedUser.NoAccessAuthenticatedUser::new)
                    .withBean(EssentialsSecurityProvider.class, EssentialsSecurityProvider.NoAccessSecurityProvider::new);

    @Test
    void the_api_is_wired_by_default_when_the_starter_is_on_the_classpath() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(FencedLocksController.class)
                                                        .hasSingleBean(SchedulerController.class)
                                                        .hasSingleBean(PostgresqlQueryStatisticsController.class)
                                                        .hasSingleBean(DurableQueuesController.class)
                                                        .hasSingleBean(EventStoreController.class)
                                                        .hasSingleBean(CdcController.class)
                                                        .hasSingleBean(EventStoreStatisticsController.class)
                                                        .hasSingleBean(AdminApiPrincipalResolver.class)
                                                        .hasSingleBean(AdminApiExceptionHandler.class)
                                                        .hasSingleBean(AdminApiJacksonModule.class));
    }

    @Test
    void the_api_can_be_switched_off_entirely() {
        contextRunner.withPropertyValues("essentials.admin-api.enabled=false")
                     .run(context -> assertThat(context).doesNotHaveBean(FencedLocksController.class)
                                                        .doesNotHaveBean(DurableQueuesController.class)
                                                        .doesNotHaveBean(AdminApiPrincipalResolver.class));
    }

    @Test
    void the_mount_point_defaults_to_the_contract_base_path() {
        contextRunner.run(context -> assertThat(context.getBean(EssentialsAdminApiProperties.class).getBasePath())
                .isEqualTo(AdminApiPaths.DEFAULT_BASE_PATH));
    }

    @Test
    void the_mount_point_can_be_relocated_behind_a_gateway_prefix() {
        contextRunner.withPropertyValues("essentials.admin-api.base-path=/internal/essentials/admin/v1")
                     .run(context -> assertThat(context.getBean(EssentialsAdminApiProperties.class).getBasePath())
                             .isEqualTo("/internal/essentials/admin/v1"));
    }

    /** The consumer's own implementations must win over anything this module contributes. */
    @Test
    void a_consumer_supplied_principal_resolver_is_not_overridden() {
        var custom = new AdminApiPrincipalResolver(new EssentialsAuthenticatedUser.AllAccessAuthenticatedUser());

        contextRunner.withBean("customPrincipalResolver", AdminApiPrincipalResolver.class, () -> custom)
                     .run(context -> assertThat(context.getBean(AdminApiPrincipalResolver.class)).isSameAs(custom));
    }
}
