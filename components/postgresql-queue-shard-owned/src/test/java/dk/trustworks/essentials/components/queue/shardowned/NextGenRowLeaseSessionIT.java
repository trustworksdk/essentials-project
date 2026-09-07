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
 * Pull sessions at message and batch granularity — the scopes that take individual rows rather than
 * whole shards, and so leave the rest of the shard to everybody else.
 * <p>
 * The row lease has to do three things, and each is asserted here rather than argued: hide a claimed
 * row from the shard's owner, hand it back on its own when the session stops renewing, and refuse an
 * acknowledgement from a session whose lease has lapsed.
 */
@Testcontainers(disabledWithoutDocker = true)
class NextGenRowLeaseSessionIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);
        NextGenSchema.create(dataSource, SHARD_COUNT);
        NextGenSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private MessageQueue queue(String instanceId) {
        return new NextGenMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
    }

    private List<Message> messages(int count, String prefix) {
        var messages = new ArrayList<Message>();
        for (var index = 0; index < count; index++) {
            messages.add(Message.of((prefix + index).getBytes(StandardCharsets.UTF_8), 1));
        }
        return messages;
    }

    @Test
    void message_scope_hands_out_one_message_at_a_time_and_batch_scope_hands_out_many() throws Exception {
        try (var queue = queue("row-1")) {
            queue.enqueue(messages(12, "m-"));

            try (var single = queue.openSession(SessionScope.MESSAGE, Duration.ofSeconds(30))) {
                assertThat(single.poll(10))
                        .as("MESSAGE scope must never hand out more than the one message being worked on")
                        .hasSize(1);
            }
            try (var batch = queue.openSession(SessionScope.BATCH, Duration.ofSeconds(30))) {
                var pulled = new ArrayList<QueueSession.PulledMessage>();
                for (var attempt = 0; attempt < 20 && pulled.size() < 12; attempt++) {
                    pulled.addAll(batch.poll(12 - pulled.size()));
                }
                assertThat(pulled).hasSize(12);
                assertThat(batch.acknowledge(pulled.stream().map(QueueSession.PulledMessage::id).toList())).isTrue();
            }
            assertThat(queue.depth().total()).isZero();
        }
    }

    /**
     * Two sessions on the same shards must not both be handed the same row. This is the property that
     * makes several pullers possible at all, and it is the one {@code SKIP LOCKED} in the claim is
     * there for.
     */
    @Test
    void two_sessions_pulling_at_once_never_receive_the_same_message() throws Exception {
        var total = 400;
        try (var queue = queue("row-2")) {
            queue.enqueue(messages(total, "p-"));

            var seen  = ConcurrentHashMap.<Long>newKeySet();
            var dupes = new java.util.concurrent.atomic.AtomicInteger();
            var pool  = Executors.newFixedThreadPool(4);
            var done  = new CountDownLatch(4);
            for (var worker = 0; worker < 4; worker++) {
                pool.submit(() -> {
                    try (var session = queue.openSession(SessionScope.BATCH, Duration.ofSeconds(60))) {
                        for (var attempt = 0; attempt < 200 && seen.size() < total; attempt++) {
                            var pulled = session.poll(7);
                            for (var message : pulled) {
                                if (!seen.add(message.id().sequence())) {
                                    dupes.incrementAndGet();
                                }
                            }
                            session.acknowledge(pulled.stream().map(QueueSession.PulledMessage::id).toList());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(90, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(dupes.get()).as("two sessions must never be handed the same row").isZero();
            assertThat(seen).hasSize(total);
            assertThat(queue.depth().total()).isZero();
        }
    }

    /**
     * The crash case, which is what the expiry exists for. A session that stops renewing must not
     * strand the messages it was holding — and must not be able to acknowledge them afterwards, since
     * by then they belong to whoever picked them up.
     */
    @Test
    void a_lapsed_session_hands_its_messages_back_and_can_no_longer_acknowledge_them() throws Exception {
        try (var queue = queue("row-3")) {
            queue.enqueue(messages(5, "lapse-"));

            var session = queue.openSession(SessionScope.BATCH, Duration.ofMillis(700));
            var pulled = new ArrayList<QueueSession.PulledMessage>();
            for (var attempt = 0; attempt < 20 && pulled.size() < 5; attempt++) {
                pulled.addAll(session.poll(5 - pulled.size()));
            }
            assertThat(pulled).hasSize(5);

            // While the lease is live the rows are the session's, so a consumer sees nothing.
            var delivered = ConcurrentHashMap.<String>newKeySet();
            try (var consumer = queue("row-3-consumer")) {
                var subscription = consumer.consume(
                        (key, payload) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                        ConsumerOptions.defaults());
                // Wait for the consumer to actually be owning shards before concluding it saw
                // nothing — otherwise this asserts that a consumer which had not started yet
                // delivered nothing, which is true of any code at all.
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .until(() -> subscription.shardsHeld() > 0);
                Thread.sleep(500L);
                assertThat(delivered).as("a consumer must not see rows a live session holds").isEmpty();

                // Stop renewing. The lease lapses on its own and the rows come back — no coordination,
                // no clean-up, nothing the dead session had to do on its way out.
                Awaitility.await().atMost(Duration.ofSeconds(30))
                          .untilAsserted(() -> assertThat(delivered).hasSize(5));
                subscription.close();
            }

            assertThat(session.acknowledge(pulled.stream().map(QueueSession.PulledMessage::id).toList()))
                    .as("a lapsed session must not delete work that has since been redelivered")
                    .isFalse();
            session.close();
        }
    }

    @Test
    void a_live_session_can_extend_and_fail_hands_a_message_straight_back() throws Exception {
        try (var queue = queue("row-4")) {
            queue.enqueue(messages(3, "x-"));

            try (var session = queue.openSession(SessionScope.BATCH, Duration.ofSeconds(2))) {
                var pulled = new ArrayList<QueueSession.PulledMessage>();
                for (var attempt = 0; attempt < 20 && pulled.size() < 3; attempt++) {
                    pulled.addAll(session.poll(3 - pulled.size()));
                }
                assertThat(pulled).hasSize(3);
                assertThat(session.extendLease()).isTrue();

                session.fail(pulled.getFirst().id(), new IllegalStateException("nope"));
                // Handed back rather than held hidden until the lease lapses: another session sees it
                // immediately.
                try (var other = queue.openSession(SessionScope.BATCH, Duration.ofSeconds(10))) {
                    var again = new ArrayList<QueueSession.PulledMessage>();
                    for (var attempt = 0; attempt < 20 && again.isEmpty(); attempt++) {
                        again.addAll(other.poll(5));
                    }
                    assertThat(again).as("a failed message must be available again at once").hasSize(1);
                    assertThat(again.getFirst().id().sequence()).isEqualTo(pulled.getFirst().id().sequence());
                }

                assertThat(session.acknowledge(pulled.subList(1, 3).stream()
                                                     .map(QueueSession.PulledMessage::id).toList())).isTrue();
            }
        }
    }

    @Test
    void key_scope_says_why_it_cannot_exist_rather_than_pretending() {
        try (var queue = queue("row-5")) {
            assertThatThrownBy(() -> queue.openSession(SessionScope.KEY, Duration.ofSeconds(10)))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("per-dispatch query")
                    .hasMessageContaining("SHARD scope");
        }
    }
}
