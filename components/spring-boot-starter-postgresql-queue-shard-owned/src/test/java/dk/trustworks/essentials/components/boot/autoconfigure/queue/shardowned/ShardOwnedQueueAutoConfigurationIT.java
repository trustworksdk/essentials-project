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

import dk.trustworks.essentials.components.queue.shardowned.*;
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
}
