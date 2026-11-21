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

import com.jayway.jsonpath.JsonPath;
import dk.trustworks.essentials.components.adminapi.rest.*;
import dk.trustworks.essentials.components.eventlog.processor.ViewProjection;
import dk.trustworks.essentials.components.eventlog.processor.api.EventLogSubscriptionsApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.shared.security.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The event-log admin operations reached over REST, as an application reaches them: every auto-configuration on the
 * class path, so {@code EventLogSubscriptionsController} (admin API starter) is registered over this starter's
 * {@link EventLogSubscriptionsApi} bean, on the context's {@code EventLog}.
 */
@Testcontainers(disabledWithoutDocker = true)
class EventLogAdminApiIT {
    private static final String SUBSCRIPTIONS = AdminApiPaths.DEFAULT_BASE_PATH + "/event-log/subscriptions";
    private static final String ORDERS_VIEW   = SUBSCRIPTIONS + "/orders-view/aggregate-types/Orders";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

    @BeforeEach
    void emptyDatabase() throws SQLException {
        EventLogAutoConfigurationIT.PROJECTED.clear();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        }
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(EventLogAutoConfigurationIT.Application.class, EventLogAutoConfigurationIT.OrdersConfiguration.class)
                .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                .withBean(EssentialsAuthenticatedUser.AllAccessAuthenticatedUser.class)
                .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "spring.datasource.username=" + postgres.getUsername(),
                                    "spring.datasource.password=" + postgres.getPassword(),
                                    "essentials.event-log.enabled=true");
    }

    @Test
    void the_subscription_operations_are_reachable_over_rest() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(EventLogSubscriptionsApi.class);
            assertThat(ctx).hasSingleBean(EventLogSubscriptionsController.class);
            var unitOfWorkFactory = ctx.getBean(EventStoreUnitOfWorkFactory.class);
            @SuppressWarnings("unchecked")
            StatefulAggregateRepository<TestOrder.OrderId, Object, TestOrder> repository = ctx.getBean(StatefulAggregateRepository.class);
            unitOfWorkFactory.usingUnitOfWork(() -> repository.save(new TestOrder(TestOrder.OrderId.random(), 3)));
            await().atMost(Duration.ofSeconds(20)).until(() -> EventLogAutoConfigurationIT.PROJECTED.size() == 1);
            var mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();

            // OrdersConfiguration has two views on Orders; orders-annotated-view writes its progress row asynchronously,
            // so whether it is listed yet depends on timing. The list is ordered by aggregate type, subscriber, instance name
            var body = mockMvc.perform(get(SUBSCRIPTIONS)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<List<String>>read(body, "$[?(@.subscriber == 'orders-view')].aggregateType")).as(body).containsExactly("Orders");
            assertThat(JsonPath.<List<String>>read(body, "$[*].subscriber")).as(body).isSorted();
            mockMvc.perform(get(SUBSCRIPTIONS).param("aggregateType", "Payments"))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$").isEmpty());
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    mockMvc.perform(get(ORDERS_VIEW))
                           .andExpect(status().isOk())
                           .andExpect(jsonPath("$.subscriber").value("orders-view"))
                           .andExpect(jsonPath("$.resumePoint").value(2)));
            mockMvc.perform(get(SUBSCRIPTIONS + "/no-such-view/aggregate-types/Orders"))
                   .andExpect(status().isNotFound());
            mockMvc.perform(get(ORDERS_VIEW + "/parked-events"))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$.events").isEmpty());

            // A ViewProjection bean is a view: a reset that only moved its resume point would re-apply events on top of
            // its rows, so the admin refuses it and points to rebuild(truncate)
            ctx.getBean("ordersView", ViewProjection.class).stop();
            var resumePointBefore = com.jayway.jsonpath.JsonPath.<Number>read(mockMvc.perform(get(ORDERS_VIEW)).andReturn().getResponse().getContentAsString(),
                                                                              "$.resumePoint").longValue();
            mockMvc.perform(post(ORDERS_VIEW + "/reset").param("resumeFrom", "1"))
                   .andExpect(status().isBadRequest());
            mockMvc.perform(get(ORDERS_VIEW))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$.resumePoint").value(resumePointBefore));
            mockMvc.perform(post(SUBSCRIPTIONS + "/no-such-view/aggregate-types/Orders/reset").param("resumeFrom", "1"))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$.reset").value(false));
        });
    }

    @Test
    void without_the_applications_own_security_every_request_is_rejected() {
        new WebApplicationContextRunner()
                .withUserConfiguration(EventLogAutoConfigurationIT.Application.class)
                .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "spring.datasource.username=" + postgres.getUsername(),
                                    "spring.datasource.password=" + postgres.getPassword(),
                                    "essentials.event-log.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(EventLogSubscriptionsController.class);
                    MockMvcBuilders.webAppContextSetup(ctx).build()
                                   .perform(get(SUBSCRIPTIONS))
                                   .andExpect(status().is4xxClientError());
                });
    }
}
