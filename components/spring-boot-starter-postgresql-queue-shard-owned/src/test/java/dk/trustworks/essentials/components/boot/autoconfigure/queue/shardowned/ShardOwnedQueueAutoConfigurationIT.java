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

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
// Single-type imports, not the package: foundation...queue and shardowned.spi both export QueueName,
// Message and QueuedMessage, and this class imports the spi package wholesale.
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueuesInterceptor;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueues;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter, booted against a real PostgreSQL.
 * <p>
 * The properties worth asserting are the ones an auto-configuration can get wrong in a way that only
 * shows up in production: a schema step that is destructive, a runtime per queue instead of per
 * process, two {@code MessageQueue}s for one name competing with each other, and a shutdown that
 * leaves shards held.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedQueueAutoConfigurationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    /**
     * The container is static, so it is shared by every method in this class and the registry
     * survives between them. Without a reset, one test registering {@code orders} with two shards
     * makes another declaring four fail — which is the protection working correctly against a test
     * that forgot it was sharing a database.
     */
    @BeforeEach
    void resetSchema() throws Exception {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
                                                     postgres.getUsername(),
                                                     postgres.getPassword());
        ShardOwnedSchema.recreate(dataSource);
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                         ShardOwnedQueueAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + postgres.getJdbcUrl(),
                                    "spring.datasource.username=" + postgres.getUsername(),
                                    "spring.datasource.password=" + postgres.getPassword());
    }

    @Test
    void the_context_starts_and_wires_one_runtime_for_the_process() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.orders=4",
                                    "essentials.shard-owned-queue.queues.shipments=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ShardRuntime.class);
                    assertThat(context).hasSingleBean(ShardOwnedQueueFactory.class);

                    var factory = context.getBean(ShardOwnedQueueFactory.class);
                    // One runtime, shared: the connections and threads are the process's, not each
                    // queue's. A runtime per queue is what once exhausted a 500-connection pool.
                    assertThat(factory.runtime()).isSameAs(context.getBean(ShardRuntime.class));
                    assertThat(factory.runtime().isStarted()).isTrue();

                    var dataSource = context.getBean(javax.sql.DataSource.class);
                    assertThat(ShardOwnedSchema.queueNames(dataSource))
                            .describedAs("the configured queues are registered at start-up")
                            .contains(QueueName.of("orders"), QueueName.of("shipments"));
                    assertThat(ShardOwnedSchema.resolve(dataSource, QueueName.of("orders")).orElseThrow().shardCount())
                            .isEqualTo(4);
                });
    }

    @Test
    void properties_reach_the_engine() {
        runner().withPropertyValues("essentials.shard-owned-queue.pump-threads=3",
                                    "essentials.shard-owned-queue.lease-ttl=12s",
                                    "essentials.shard-owned-queue.hole-expiry=7s")
                .run(context -> {
                    var settings = context.getBean(ShardOwnerSettings.class);
                    assertThat(settings.pumpThreads()).isEqualTo(3);
                    // The two used to be one knob: leaseTtl was holeExpiry x 3. Asserting both
                    // proves they are independently settable rather than merely both present.
                    assertThat(settings.leaseTtl()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(settings.holeExpiry()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(context.getBean(ShardRuntime.class).pumpCount()).isEqualTo(3);
                });
    }

    @Test
    void a_queue_from_the_factory_round_trips_a_message() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.orders=2")
                .run(context -> {
                    var queue = context.getBean(ShardOwnedQueueFactory.class).queue("orders");
                    var delivered = ConcurrentHashMap.<String>newKeySet();
                    queue.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                  ConsumerOptions.defaults());
                    queue.enqueue(List.of(Message.of("hello".getBytes(StandardCharsets.UTF_8), 1)));

                    Awaitility.await().atMost(Duration.ofSeconds(30))
                              .untilAsserted(() -> assertThat(delivered).contains("hello"));
                });
    }

    /**
     * Two instances for one name would register as two competing consumers of the same queue in one
     * process, halve each other's fair share, and each serve half of it for no reason.
     */
    @Test
    void the_factory_returns_the_same_queue_for_a_name() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.orders=2")
                .run(context -> {
                    var factory = context.getBean(ShardOwnedQueueFactory.class);
                    assertThat(factory.queue("orders")).isSameAs(factory.queue(QueueName.of("orders")));
                });
    }

    @Test
    void an_unregistered_queue_is_refused_rather_than_invented() {
        runner().run(context -> {
            var factory = context.getBean(ShardOwnedQueueFactory.class);
            assertThat(context).hasNotFailed();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> factory.queue("not-configured"))
                                           .isInstanceOf(IllegalStateException.class)
                                           .hasMessageContaining("not-configured");
        });
    }

    /**
     * Re-declaring a queue with a different shard count must fail the context. Accepting it would
     * re-route every key and leave shards nobody owns — a silent delivery failure discovered later.
     */
    @Test
    void a_shard_count_that_disagrees_with_the_registry_fails_the_context() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.payments=4")
                .run(context -> assertThat(context).hasNotFailed());

        runner().withPropertyValues("essentials.shard-owned-queue.queues.payments=8")
                .run(context -> assertThat(context).hasFailed()
                                                   .getFailure()
                                                   .hasMessageContaining("payments"));
    }

    /**
     * Schema initialisation runs on every boot, so it must never destroy anything. The engine's only
     * schema entry point used to be the destructive one.
     */
    @Test
    void restarting_the_context_keeps_the_data_that_was_there() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.durable=2")
                .run(context -> {
                    var queue = context.getBean(ShardOwnedQueueFactory.class).queue("durable");
                    queue.enqueue(List.of(Message.of("survives".getBytes(StandardCharsets.UTF_8), 1)));
                    assertThat(queue.depth().total()).isEqualTo(1);
                });

        // A second context over the same database is a restart in every way that matters here.
        runner().withPropertyValues("essentials.shard-owned-queue.queues.durable=2")
                .run(context -> {
                    var queue = context.getBean(ShardOwnedQueueFactory.class).queue("durable");
                    assertThat(queue.depth().total())
                            .describedAs("start-up must not drop the queue tables")
                            .isEqualTo(1);
                });
    }

    @Test
    void schema_initialisation_can_be_turned_off_for_a_migration_managed_deployment() {
        runner().withPropertyValues("essentials.shard-owned-queue.initialize-schema=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ShardOwnedQueueFactory.class);
                });
    }

    /**
     * Interceptor and observer beans are picked up from the context and attached before a queue
     * consumes — which is the only moment either can be attached and still see anything.
     */
    @Test
    void interceptor_and_observer_beans_are_applied_to_every_queue() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.orders=2")
                .withUserConfiguration(RecordingConfiguration.class)
                .run(context -> {
                    var queue = context.getBean(ShardOwnedQueueFactory.class).queue("orders");
                    var recorded = context.getBean(Recorder.class);

                    queue.enqueue(List.of(Message.of("through".getBytes(StandardCharsets.UTF_8), 7)));

                    Awaitility.await().atMost(Duration.ofSeconds(30))
                              .untilAsserted(() -> assertThat(recorded.enqueued)
                                      .describedAs("the interceptor bean saw the enqueue")
                                      .isTrue());
                    assertThat(recorded.observedEnqueue)
                            .describedAs("the observer bean saw it too")
                            .isTrue();
                });
    }

    /**
     * The {@code DurableQueuesInterceptor} beans reach the adapter when this engine backs
     * {@code DurableQueues}.
     * <p>
     * {@code EssentialsComponentsConfiguration} applies them to {@code PostgresqlDurableQueues} and
     * this bean displaces that one, so leaving the list uninjected dropped every interceptor an
     * application had — including the framework's own
     * {@code RecordExecutionTimeDurableQueueInterceptor}. Nothing threw: turning on this engine simply
     * removed the queue timers, with nothing in the log to say so.
     * <p>
     * The serializer and unit-of-work factory are mocks because this asserts wiring and nothing else —
     * the bean method only hands them to the builder, and real ones would drag a Jdbi and a Jackson
     * flavor into a test about which beans get attached.
     */
    @Test
    void durable_queues_interceptor_beans_reach_the_adapter() {
        runner().withPropertyValues("essentials.shard-owned-queue.durable-queues-enabled=true")
                .withUserConfiguration(DurableQueuesConfiguration.class)
                .run(context -> {
                    var durableQueues = context.getBean(DurableQueues.class);
                    assertThat(durableQueues).isInstanceOf(ShardOwnedDurableQueues.class);
                    assertThat(((ShardOwnedDurableQueues) durableQueues).getInterceptors())
                            .describedAs("the interceptor bean was attached to the adapter")
                            .containsExactly(context.getBean(DurableQueuesInterceptor.class));
                });
    }

    /** Without the flag the adapter is not built at all, and the default engine stays in place. */
    @Test
    void durable_queues_stay_off_unless_asked() {
        runner().withUserConfiguration(DurableQueuesConfiguration.class)
                .run(context -> assertThat(context).doesNotHaveBean(DurableQueues.class));
    }

    @org.springframework.context.annotation.Configuration
    static class DurableQueuesConfiguration {
        @org.springframework.context.annotation.Bean
        JSONSerializer jsonSerializer() {
            return org.mockito.Mockito.mock(JSONSerializer.class);
        }

        @org.springframework.context.annotation.Bean
        UnitOfWorkFactory<?> unitOfWorkFactory() {
            return org.mockito.Mockito.mock(UnitOfWorkFactory.class);
        }

        @org.springframework.context.annotation.Bean
        DurableQueuesInterceptor durableQueuesInterceptor() {
            return durableQueues -> {
            };
        }
    }

    static class Recorder {
        volatile boolean enqueued;
        volatile boolean observedEnqueue;
    }

    @org.springframework.context.annotation.Configuration
    static class RecordingConfiguration {
        private final Recorder recorder = new Recorder();

        @org.springframework.context.annotation.Bean
        Recorder recorder() {
            return recorder;
        }

        @org.springframework.context.annotation.Bean
        dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueueInterceptor recordingInterceptor() {
            return new dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueueInterceptor() {
                @Override
                public List<dk.trustworks.essentials.components.queue.shardowned.spi.MessageId> intercept(
                        dk.trustworks.essentials.components.queue.shardowned.spi.operations.EnqueueMessages operation,
                        dk.trustworks.essentials.shared.interceptor.InterceptorChain<
                                dk.trustworks.essentials.components.queue.shardowned.spi.operations.EnqueueMessages,
                                List<dk.trustworks.essentials.components.queue.shardowned.spi.MessageId>,
                                dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueueInterceptor> chain) {
                    recorder.enqueued = true;
                    return chain.proceed();
                }
            };
        }

        @org.springframework.context.annotation.Bean
        QueueObserver recordingObserver() {
            return new QueueObserver() {
                @Override
                public void enqueued(int messageCount, boolean ordered) {
                    recorder.observedEnqueue = true;
                }
            };
        }
    }

    /**
     * The instance identity is the hostname, as everywhere else in Essentials — the fenced lock
     * manager and the scheduler both use {@code Network.hostName()} bare. An operator correlating a
     * shard hand-over with a lock hand-over should not have to translate between naming schemes, and
     * a per-boot UUID means nothing in a log line or in the membership table.
     */
    @Test
    void the_instance_id_defaults_to_the_hostname() {
        runner().run(context -> assertThat(context.getBean(ShardOwnedQueueFactory.class).instanceId())
                .isEqualTo(dk.trustworks.essentials.shared.network.Network.hostName()));
    }

    @Test
    void the_instance_id_can_be_overridden_where_the_hostname_is_not_unique_per_process() {
        runner().withPropertyValues("essentials.shard-owned-queue.instance-id=pod-7-consumer-a")
                .run(context -> assertThat(context.getBean(ShardOwnedQueueFactory.class).instanceId())
                        .isEqualTo("pod-7-consumer-a"));
    }

    /**
     * The ordered routing space is a per-queue, registration-time choice the engine has always
     * supported and the starter did not expose — it called the three-argument registerQueue, so every
     * configured queue took the default whatever the deployment needed. A queue expecting more than
     * ORDERED_UNITS instances on its ordered lane had no way to say so from configuration.
     */
    @Test
    void a_queue_can_be_given_a_larger_ordered_routing_space() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.wide=2",
                                    "essentials.shard-owned-queue.ordered-units.wide=128")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(orderedUnitsOf("wide"))
                            .as("the configured space is recorded on the registry row")
                            .isEqualTo(128);
                    assertThat(shardCountOf("wide"))
                            .as("and the shard count remains the unordered lane's own number")
                            .isEqualTo(2);
                });
    }

    /** A queue not named there takes the engine's default, which is the case for almost all of them. */
    @Test
    void a_queue_without_an_explicit_space_takes_the_default() {
        runner().withPropertyValues("essentials.shard-owned-queue.queues.plain=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(orderedUnitsOf("plain")).isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                });
    }

    private int orderedUnitsOf(String queueName) throws Exception {
        return registryColumn(queueName, "ordered_units");
    }

    private int shardCountOf(String queueName) throws Exception {
        return registryColumn(queueName, "shard_count");
    }

    private int registryColumn(String queueName, String column) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                                                                   postgres.getUsername(),
                                                                   postgres.getPassword());
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM shard_queue_registry WHERE queue_name = ?")) {
            statement.setString(1, queueName);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("queue '%s' must be registered", queueName).isTrue();
                return resultSet.getInt(1);
            }
        }
    }
}
