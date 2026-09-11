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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.adminapi.rest.AdminApiPrincipalResolver;
import dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.rest.ShardOwnedQueuesController;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.api.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.shared.security.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin surface as it is actually reached: through the auto-configuration, over the factory.
 *
 * <h2>What this covers that the engine's own API test cannot</h2>
 * {@code ShardOwnedQueuesApiIT} drives {@link DefaultShardOwnedQueuesApi} against a hand-written
 * {@link MessageQueues}, which is the right way to test authorisation and redaction and says nothing
 * about the two things that only exist here:
 * <ul>
 *     <li>{@link ShardOwnedQueueFactory} as a {@link MessageQueues} — listing from the registry rather
 *         than from the queues this process happens to have built, and resolving a name into a queue
 *         without starting to consume from it.</li>
 *     <li>The conditional wiring. The endpoints must appear when the admin API is present and must
 *         not fail a context that does not have it — an auto-configuration that hard-required the
 *         admin API would force an event store onto every application that wanted a queue.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedQueuesAdminApiIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    @BeforeEach
    void resetSchema() throws Exception {
        ShardOwnedSchema.recreate(dataSource());
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                // Both, because the admin beans live in their own auto-configuration: they are
                // @ConditionalOnBean, so they have to be ordered after the admin API's, and the engine's
                // own auto-configuration is ordered BEFORE EssentialsComponentsConfiguration so its
                // DurableQueues bean can displace the default. One class cannot be both.
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                         ShardOwnedQueueAutoConfiguration.class,
                                                         ShardOwnedQueuesAdminApiAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "spring.datasource.username=" + postgres.getUsername(),
                                    "spring.datasource.password=" + postgres.getPassword(),
                                    "essentials.shard-owned-queue.queues.orders=4");
    }

    // ------------------------------------------------------------ the wiring

    @Test
    void the_endpoints_appear_when_the_admin_api_is_present() {
        runner().withUserConfiguration(AdminApiStubConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ShardOwnedQueuesApi.class);
                    assertThat(context).hasSingleBean(ShardOwnedQueuesController.class);
                });
    }

    /**
     * Without an {@link EssentialsSecurityProvider} there is nothing to authorise against, so the API
     * bean must not be created — and, crucially, the context must still start. A queue-only
     * application is the common case; failing it in order to serve endpoints nobody asked for would
     * make the admin API a hidden requirement of using the queue.
     */
    @Test
    void an_application_with_no_security_provider_still_gets_a_working_queue() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ShardOwnedQueuesApi.class);
            assertThat(context).doesNotHaveBean(ShardOwnedQueuesController.class);
            assertThat(context).hasSingleBean(ShardOwnedQueueFactory.class);
        });
    }

    /**
     * An application that already declares its own API bean keeps it. The starter contributes a
     * default, it does not take the decision.
     */
    @Test
    void an_application_supplied_api_bean_wins() {
        runner().withUserConfiguration(AdminApiStubConfiguration.class)
                .withBean(ShardOwnedQueuesApi.class, () -> new DefaultShardOwnedQueuesApi(new AllowAll(), new EmptyQueues()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ShardOwnedQueuesApi.class);
                    assertThat(context.getBean(ShardOwnedQueuesApi.class).getQueueNames("anyone"))
                            .describedAs("the application's own bean, not one built over the factory")
                            .isEmpty();
                });
    }

    // ------------------------------------------------- the factory as a registry

    @Test
    void the_factory_lists_what_the_registry_holds_not_what_it_has_built() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.shipments=2")
                .run(context -> {
                    var factory = context.getBean(ShardOwnedQueueFactory.class);

                    assertThat(factory.queueNames())
                            .describedAs("both registered queues, though the factory has built neither")
                            .containsExactlyInAnyOrder(QueueName.of("orders"), QueueName.of("shipments"));
                });
    }

    @Test
    void an_unregistered_name_resolves_to_nothing_rather_than_throwing() {
        runner().run(context -> {
            var factory = context.getBean(ShardOwnedQueueFactory.class);
            assertThat(factory.findQueue(QueueName.of("never-registered"))).isEmpty();
            assertThat(factory.findQueue(QueueName.of("orders"))).isPresent();
        });
    }

    /**
     * Inspecting a queue must not make this process start competing for its shards. An admin request
     * against a queue the pod does not serve would otherwise silently enlist it as a consumer — and
     * the shards it took would be served by a process that has no handler for them.
     */
    @Test
    void resolving_a_queue_for_inspection_does_not_start_consuming_it() {
        runner().run(context -> {
            var factory = context.getBean(ShardOwnedQueueFactory.class);
            var queue   = factory.findQueue(QueueName.of("orders")).orElseThrow();

            assertThat(queue.health().unorderedOwned())
                    .describedAs("no shard is owned by merely looking at the queue")
                    .isZero();
            assertThat(queue.health().orderedOwned()).isZero();
            // 4 unordered shards, as configured, plus the ordered lane's fixed routing space. This
            // read 8 when both lanes sized themselves from shardCount; the ordered lane has routed
            // over ORDERED_UNITS since, and nothing re-ran this suite to notice.
            assertThat(queue.health().unownedShards())
                    .isEqualTo(4 + ShardOwnedSchema.ORDERED_UNITS);
        });
    }

    // ------------------------------------------------------- end to end

    @Test
    void a_message_enqueued_through_the_factory_is_readable_through_the_api() {
        runner().withUserConfiguration(AdminApiStubConfiguration.class)
                .run(context -> {
                    var factory = context.getBean(ShardOwnedQueueFactory.class);
                    var api     = context.getBean(ShardOwnedQueuesApi.class);
                    var orders  = QueueName.of("orders");

                    var id = factory.queue(orders).enqueue(Message.of("payload".getBytes(StandardCharsets.UTF_8), 3));

                    var admin = "admin-principal";
                    assertThat(api.getQueueNames(admin)).contains(orders);

                    var message = api.getMessage(admin, orders, id).orElseThrow();
                    assertThat(message.id()).isEqualTo(id.toString());
                    assertThat(MessageId.parse(message.id()))
                            .describedAs("the id in the response is the one the API accepts back")
                            .isEqualTo(id);
                    assertThat(message.payload()).isEqualTo("payload");
                    assertThat(message.payloadType()).isEqualTo(3);

                    var status = api.getQueueStatus(admin, orders).orElseThrow();
                    assertThat(status.unorderedDepth()).isEqualTo(1);
                    assertThat(status.fullyOwned())
                            .describedAs("nothing is consuming this queue")
                            .isFalse();

                    assertThat(api.deleteMessage(admin, orders, id)).isTrue();
                    assertThat(api.getMessage(admin, orders, id)).isEmpty();
                });
    }

    // ---------------------------------------------------------- the wire format

    /**
     * A queue name must go on the wire as a string.
     * <p>
     * This engine's {@code QueueName} is a plain record — {@code types} would drag kotlin-reflect onto
     * a module that depends on {@code shared} alone — so it is not a {@code CharSequenceType} and the
     * admin API's own Jackson module does not cover it. Left alone it serialises as
     * {@code {"value":"orders"}}: readable by nobody, and different from how the identical concept
     * renders on the durable-queues endpoints.
     */
    @Test
    void a_queue_name_serialises_as_a_string_not_as_an_object() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder()
                                                           .addModule(new dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.rest.ShardOwnedQueueJacksonModule())
                                                           .build();

        assertThat(mapper.writeValueAsString(QueueName.of("orders"))).isEqualTo("\"orders\"");
        assertThat(mapper.writeValueAsString(List.of(QueueName.of("orders"), QueueName.of("shipments"))))
                .isEqualTo("[\"orders\",\"shipments\"]");
        assertThat(mapper.writeValueAsString(new ApiShardOwnedQueueStatus(QueueName.of("orders"),
                                                                          4, 64, 1, 0, 0, 0, 0, 68, false, 0, 64)))
                .describedAs("nested in a DTO too, which is where it actually reaches a client")
                .contains("\"queueName\":\"orders\"");
    }

    /**
     * And the module must be wired, not merely written — a Jackson module that exists and is never
     * registered changes nothing about the response.
     */
    @Test
    void the_jackson_module_is_registered_with_the_admin_api_present() {
        runner().withUserConfiguration(AdminApiStubConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.rest.ShardOwnedQueueJacksonModule.class));
    }

    // ---------------------------------------------------------- test doubles

    /**
     * Stands in for what {@code spring-boot-starter-admin-api} contributes, so the conditions this
     * starter declares are exercised without booting a web application and an event store.
     */
    static class AdminApiStubConfiguration {
        @Bean
        EssentialsSecurityProvider securityProvider() {
            return new AllowAll();
        }

        @Bean
        AdminApiPrincipalResolver principalResolver() {
            return new AdminApiPrincipalResolver(new EssentialsAuthenticatedUser() {
                @Override
                public Object getPrincipal() {
                    return "admin-principal";
                }

                @Override
                public boolean isAuthenticated() {
                    return true;
                }

                @Override
                public void logout() {
                }
            });
        }
    }

    static class AllowAll implements EssentialsSecurityProvider {
        @Override
        public boolean isAllowed(Object principal, String requiredRole) {
            return true;
        }

        @Override
        public Optional<String> getPrincipalName(Object principal) {
            return Optional.of(String.valueOf(principal));
        }
    }

    static class EmptyQueues implements MessageQueues {
        @Override
        public List<QueueName> queueNames() {
            return List.of();
        }

        @Override
        public Optional<MessageQueue> findQueue(QueueName queueName) {
            return Optional.empty();
        }
    }
}
