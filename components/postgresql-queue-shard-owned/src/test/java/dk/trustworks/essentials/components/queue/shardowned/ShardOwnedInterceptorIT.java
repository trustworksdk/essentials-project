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

package dk.trustworks.essentials.components.queue.shardowned;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.operations.*;
import dk.trustworks.essentials.shared.interceptor.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The interceptor chain: what it can do that a {@link QueueObserver} cannot.
 * <p>
 * Every test here exercises a capability that is the reason interception exists at all — changing
 * what is written, refusing to proceed, composing in a defined order, and failing the operation when
 * it throws. An observer can do none of those, which is why both mechanisms exist rather than one.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedInterceptorIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 2;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private PostgresqlMessageQueue queue(String instanceId) {
        return new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
    }

    /** Modifying what gets written is the capability an observer structurally cannot have. */
    @Test
    void an_interceptor_can_rewrite_the_batch_before_it_is_written() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = queue("icept-1")) {
            queue.addInterceptor(new MessageQueueInterceptor() {
                @Override
                public List<MessageId> intercept(EnqueueMessages operation,
                                                 InterceptorChain<EnqueueMessages, List<MessageId>, MessageQueueInterceptor> chain) {
                    operation.setMessages(operation.getMessages().stream()
                                                   .map(message -> Message.of(("enriched:" + new String(message.payload(),
                                                                                                        StandardCharsets.UTF_8))
                                                                                      .getBytes(StandardCharsets.UTF_8),
                                                                              message.payloadType()))
                                                   .toList());
                    return chain.proceed();
                }
            });
            queue.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());

            queue.enqueue(List.of(Message.of("one".getBytes(StandardCharsets.UTF_8), 1)));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(delivered).containsExactly("enriched:one"));
        }
    }

    /** Not proceeding stops the write entirely, and the caller sees whatever the chain returns. */
    @Test
    void an_interceptor_that_does_not_proceed_enqueues_nothing() throws Exception {
        try (var queue = queue("icept-2")) {
            queue.addInterceptor(new MessageQueueInterceptor() {
                @Override
                public List<MessageId> intercept(EnqueueMessages operation,
                                                 InterceptorChain<EnqueueMessages, List<MessageId>, MessageQueueInterceptor> chain) {
                    return List.of();
                }
            });

            assertThat(queue.enqueue(List.of(Message.of("blocked".getBytes(StandardCharsets.UTF_8), 1)))).isEmpty();
            assertThat(queue.depth().total()).describedAs("nothing was written").isZero();
        }
    }

    /**
     * Skipping the handler acknowledges the message. Asserted because it is a footgun as much as a
     * feature: a dropped message and a processed one look identical afterwards.
     */
    @Test
    void an_interceptor_can_skip_the_handler_and_the_message_is_acknowledged() throws Exception {
        var handled = new CopyOnWriteArrayList<String>();
        try (var queue = queue("icept-3")) {
            queue.addInterceptor(new MessageQueueInterceptor() {
                @Override
                public Void intercept(HandleMessage operation,
                                      InterceptorChain<HandleMessage, Void, MessageQueueInterceptor> chain) {
                    if (new String(operation.payload(), StandardCharsets.UTF_8).startsWith("skip")) {
                        return null;
                    }
                    return chain.proceed();
                }
            });
            queue.consume((key, payload, payloadType) -> handled.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());

            queue.enqueue(List.of(Message.of("skip-me".getBytes(StandardCharsets.UTF_8), 1),
                                  Message.of("keep-me".getBytes(StandardCharsets.UTF_8), 1)));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(handled).containsExactly("keep-me"));
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.depth().total())
                              .describedAs("the skipped message is acknowledged, not left behind")
                              .isZero());
            assertThat(queue.depth().deadLettered()).isZero();
        }
    }

    /**
     * An interceptor is in the call path, so its exception is the operation's — the opposite of an
     * observer, whose exception must not touch the message.
     */
    @Test
    void an_interceptor_that_throws_fails_the_delivery_and_the_message_is_retried() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var queue = queue("icept-4")) {
            queue.addInterceptor(new MessageQueueInterceptor() {
                @Override
                public Void intercept(HandleMessage operation,
                                      InterceptorChain<HandleMessage, Void, MessageQueueInterceptor> chain) {
                    if (attempts.incrementAndGet() <= 2) {
                        throw new IllegalStateException("interceptor refuses this attempt");
                    }
                    return chain.proceed();
                }
            });
            var handled = new CountDownLatch(1);
            queue.consume((key, payload, payloadType) -> handled.countDown(),
                          new ConsumerOptions(8, Integer.MAX_VALUE, 5, Duration.ofMillis(50), 1.0d, Duration.ofMillis(50)));

            queue.enqueue(List.of(Message.of("retried".getBytes(StandardCharsets.UTF_8), 1)));

            assertThat(handled.await(30, TimeUnit.SECONDS))
                    .describedAs("the engine retried until the interceptor let it through")
                    .isTrue();
            assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void interceptors_run_in_the_order_the_annotation_declares() throws Exception {
        var order = new CopyOnWriteArrayList<String>();
        try (var queue = queue("icept-5")) {
            // Registered last-first deliberately: if registration order won, this would record
            // "late" before "early" and the annotation would be decorative.
            queue.addInterceptor(new Late(order));
            queue.addInterceptor(new Early(order));
            queue.consume((key, payload, payloadType) -> {
            }, ConsumerOptions.defaults());

            queue.enqueue(List.of(Message.of("ordered".getBytes(StandardCharsets.UTF_8), 1)));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(order).containsExactly("early", "late"));
        }
    }

    /**
     * The engine's cost argument is about what a message costs, so a chain that is not used must not
     * be walked. Asserted structurally — an interceptor added after {@code consume} would otherwise
     * be the only way to notice the fast path had stopped being taken.
     */
    @Test
    void nothing_is_intercepted_when_no_interceptor_is_registered() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = queue("icept-6")) {
            queue.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());
            queue.enqueue(List.of(Message.of("plain".getBytes(StandardCharsets.UTF_8), 1)));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(delivered).containsExactly("plain"));
        }
    }

    /** A transactional enqueue is interceptable too, and the interceptor can see the connection. */
    @Test
    void a_transactional_enqueue_reaches_the_chain_with_its_connection() throws Exception {
        var sawConnection = new java.util.concurrent.atomic.AtomicBoolean();
        try (var queue = queue("icept-7")) {
            queue.addInterceptor(new MessageQueueInterceptor() {
                @Override
                public List<MessageId> intercept(EnqueueMessages operation,
                                                 InterceptorChain<EnqueueMessages, List<MessageId>, MessageQueueInterceptor> chain) {
                    sawConnection.set(operation.getConnection().isPresent());
                    return chain.proceed();
                }
            });
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                queue.enqueue(connection, List.of(Message.of("tx".getBytes(StandardCharsets.UTF_8), 1)));
                connection.commit();
            }
            assertThat(sawConnection)
                    .describedAs("an interceptor writing its own rows needs the caller's connection")
                    .isTrue();
            assertThat(queue.depth().total()).isEqualTo(1);
        }
    }

    @InterceptorOrder(1)
    private record Early(List<String> order) implements MessageQueueInterceptor {
        @Override
        public Void intercept(HandleMessage operation,
                              InterceptorChain<HandleMessage, Void, MessageQueueInterceptor> chain) {
            order.add("early");
            return chain.proceed();
        }
    }

    @InterceptorOrder(2)
    private record Late(List<String> order) implements MessageQueueInterceptor {
        @Override
        public Void intercept(HandleMessage operation,
                              InterceptorChain<HandleMessage, Void, MessageQueueInterceptor> chain) {
            order.add("late");
            return chain.proceed();
        }
    }
}
