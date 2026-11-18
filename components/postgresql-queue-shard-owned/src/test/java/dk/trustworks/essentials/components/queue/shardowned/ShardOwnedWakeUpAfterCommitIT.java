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
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MessageQueue#enqueueWithoutWakeup} writes the rows without the in-transaction {@code NOTIFY}, and
 * {@link MessageQueue#wakeUp} wakes the owners after commit: in memory for a unit this process serves, by a batched
 * notification for one served elsewhere. The owners' backstop and head sweep are set to a minute, so a delivery within
 * seconds can only have come from the wake-up under test; the listener's and the notifier's counts say which way it
 * went.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedWakeUpAfterCommitIT {
    private static final int      SHARD_COUNT = 2;
    private static final Duration NO_SCAN     = Duration.ofMinutes(1);

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;
    private QueueName        name;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        name = QueueName.of("wake-up-after-commit");
        ShardOwnedSchema.registerQueue(dataSource, name, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var closeable : closeables.reversed()) {
            closeable.close();
        }
        dataSource.close();
    }

    @Test
    void a_message_woken_after_commit_reaches_the_owner_in_this_process_without_a_notification() throws Exception {
        var runtime   = runtime();
        var queue     = queue(runtime, "local");
        var delivered = consume(queue);

        var ids = enqueueInTransaction(queue, true, Message.of(bytes("unordered"), 1), Message.ordered(bytes("ordered"), 1, "key-1", 0));
        queue.wakeUp(ids);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).containsExactlyInAnyOrder("unordered", "ordered"));
        assertThat(runtime.notificationsReceived()).as("notifications").isZero();
        assertThat(runtime.notifier().keysSent()).as("keys notified").isZero();
    }

    @Test
    void nothing_is_delivered_before_the_commit_and_the_wake_up() throws Exception {
        var runtime   = runtime();
        var queue     = queue(runtime, "local");
        var delivered = consume(queue);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var ids = queue.enqueueWithoutWakeup(connection, List.of(Message.of(bytes("pending"), 1)));
            Thread.sleep(1_000);
            assertThat(delivered).as("delivered before commit").isEmpty();
            connection.commit();
            Thread.sleep(1_000);
            assertThat(delivered).as("delivered after commit, before the wake-up - the backstop is a minute").isEmpty();
            queue.wakeUp(ids);
        }

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).containsExactly("pending"));
        assertThat(runtime.notificationsReceived()).isZero();
    }

    @Test
    void a_rolled_back_enqueue_is_neither_delivered_nor_notified() throws Exception {
        var runtime   = runtime();
        var queue     = queue(runtime, "local");
        var delivered = consume(queue);

        enqueueInTransaction(queue, false, Message.of(bytes("rolled-back"), 1));

        Thread.sleep(1_500);
        assertThat(delivered).isEmpty();
        assertThat(runtime.notificationsReceived()).isZero();
        assertThat(runtime.notifier().keysSent()).isZero();
    }

    /**
     * A second runtime stands in for another process: it consumes, and the producing runtime serves nothing, so every
     * wake-up has to travel as a notification - sent after commit, by the producing runtime's notifier.
     */
    @Test
    void a_unit_served_elsewhere_is_notified_after_commit() throws Exception {
        var consumingRuntime = runtime();
        var delivered        = consume(queue(consumingRuntime, "consumer"));
        var producingRuntime = runtime();
        var producer         = queue(producingRuntime, "producer");

        var ids = enqueueInTransaction(producer, true, Message.of(bytes("u-1"), 1), Message.of(bytes("u-2"), 1), Message.ordered(bytes("o-1"), 1, "key-1", 0));
        producer.wakeUp(ids);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).containsExactlyInAnyOrder("u-1", "u-2", "o-1"));
        assertThat(producingRuntime.notifier().keysSent()).as("one key per unit woken").isEqualTo(Set.copyOf(ids.stream().map(id -> id.lane() + ":" + id.shard()).toList()).size());
        assertThat(consumingRuntime.notificationsReceived()).isPositive();
    }

    /**
     * A producing queue with no runtime, and none shared for its {@code DataSource}, sends its wake-ups through a
     * notifier of its own - it must not stand up pumps to find out whether anyone in the process consumes.
     */
    @Test
    void a_queue_without_a_runtime_notifies_through_its_own_notifier() throws Exception {
        var consumingRuntime = runtime();
        var delivered        = consume(queue(consumingRuntime, "consumer"));
        var producer = PostgresqlMessageQueue.builder().setDataSource(dataSource).setQueueName(name).setInstanceId("bare-producer").build();
        closeables.add(producer);

        producer.wakeUp(enqueueInTransaction(producer, true, Message.of(bytes("from-a-bare-producer"), 1)));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).containsExactly("from-a-bare-producer"));
        assertThat(ShardRuntime.sharedIfPresent(dataSource)).as("no runtime stood up").isNull();
    }

    @Test
    void the_queues_own_transaction_wakes_the_owner_in_this_process_after_commit_without_a_notification() throws Exception {
        var runtime   = runtime();
        var queue     = queue(runtime, "local");
        var delivered = consume(queue);

        queue.enqueue(List.of(Message.of(bytes("own-transaction"), 1), Message.ordered(bytes("own-ordered"), 1, "key-2", 0)));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).containsExactlyInAnyOrder("own-transaction", "own-ordered"));
        assertThat(runtime.notificationsReceived()).isZero();
        assertThat(runtime.notifier().keysSent()).isZero();
    }

    /**
     * The in-transaction notification is unchanged for {@link MessageQueue#enqueue(java.sql.Connection, List)}, which
     * leaves the commit, and so the wake-up, to the caller's transaction.
     */
    @Test
    void enqueue_on_a_callers_connection_still_notifies_inside_the_transaction() throws Exception {
        var runtime   = runtime();
        var queue     = queue(runtime, "local");
        var delivered = consume(queue);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            queue.enqueue(connection, List.of(Message.of(bytes("notified-in-transaction"), 1)));
            connection.commit();
        }

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(delivered).containsExactly("notified-in-transaction"));
        assertThat(runtime.notificationsReceived()).isPositive();
    }

    private List<MessageId> enqueueInTransaction(MessageQueue queue, boolean commit, Message... messages) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var ids = queue.enqueueWithoutWakeup(connection, List.of(messages));
            if (commit) {
                connection.commit();
            } else {
                connection.rollback();
            }
            return ids;
        }
    }

    private Set<String> consume(PostgresqlMessageQueue queue) throws Exception {
        var delivered    = ConcurrentHashMap.<String>newKeySet();
        var subscription = queue.consume((messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                         ConsumerOptions.defaults());
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .until(() -> subscription.unorderedShardsHeld() == SHARD_COUNT && subscription.orderedUnitsHeld() == ShardOwnedSchema.ORDERED_UNITS);
        // Past the start-up, so what follows is served by wake-ups alone
        Thread.sleep(1_500);
        return delivered;
    }

    private PostgresqlMessageQueue queue(ShardRuntime runtime, String instanceId) {
        var queue = PostgresqlMessageQueue.builder()
                                          .setDataSource(dataSource)
                                          .setQueueName(name)
                                          .setInstanceId(instanceId)
                                          .setSettings(withoutFullScan())
                                          .setRuntime(runtime)
                                          .build();
        closeables.add(queue);
        return queue;
    }

    private ShardRuntime runtime() {
        var runtime = new ShardRuntime(dataSource, withoutFullScan());
        closeables.add(runtime);
        return runtime;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code pollBackstop} and the head sweep at a minute - see {@code ShardOwnedPumpScheduleIT}
     */
    private static ShardOwnerSettings withoutFullScan() {
        var d = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(),
                                      d.holeExpiry(), NO_SCAN, d.maxHolesPerChase(), d.keyConcurrency(),
                                      NO_SCAN, NO_SCAN, 2, d.shedGrace(), d.leaseTtl(), d.watermarkCap());
    }
}
