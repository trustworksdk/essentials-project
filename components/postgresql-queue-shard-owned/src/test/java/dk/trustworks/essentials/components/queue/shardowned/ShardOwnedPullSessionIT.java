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
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Pull sessions (§8). The three requirements the old pull method bundled behind one signature, each
 * served explicitly: a caller that controls its own transaction boundary, a caller that drives its
 * own loop, and a handler that outlives any queue-wide timeout.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedPullSessionIT {

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

    private MessageQueue queue(String instanceId) {
        return new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
    }

    @Test
    void a_session_pulls_acknowledges_and_leaves_the_queue_empty() throws Exception {
        try (var queue = queue("pull-1")) {
            var messages = new ArrayList<Message>();
            for (var index = 0; index < 40; index++) {
                messages.add(Message.of(("m-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            queue.enqueue(messages);

            var pulled = new ArrayList<QueueSession.PulledMessage>();
            try (var session = queue.openSession(SessionScope.SHARD, Duration.ofSeconds(30))) {
                // The caller drives the loop, which is the second of the three requirements.
                for (var attempt = 0; attempt < 20 && pulled.size() < 40; attempt++) {
                    pulled.addAll(session.poll(10));
                }
                assertThat(pulled).hasSize(40);
                assertThat(pulled.stream().map(QueueSession.PulledMessage::id).distinct().count())
                        .as("a session must not hand out the same message twice")
                        .isEqualTo(40L);

                assertThat(session.acknowledge(pulled.stream().map(QueueSession.PulledMessage::id).toList()))
                        .as("the session holds the leases, so its acknowledgement must be accepted")
                        .isTrue();
            }
            assertThat(queue.depth().total()).isZero();
        }
    }

    /**
     * A session holds shards, so a consumer cannot take them — and gets them back the moment the
     * session closes. That exclusivity is the point: it is what lets a session acknowledge at all.
     */
    @Test
    void a_session_excludes_consumers_while_it_holds_its_shards_and_releases_them_on_close() throws Exception {
        try (var queue = queue("pull-2")) {
            var session = queue.openSession(SessionScope.SHARD, Duration.ofSeconds(30));
            assertThat(((ShardQueueSession) session).shardsHeld()).isEqualTo(SHARD_COUNT);

            try (var other = queue("consumer")) {
                var subscription = other.consume((key, payload, payloadType) -> {
                }, ConsumerOptions.defaults());
                assertThat(subscription.unorderedShardsHeld())
                        .as("a consumer must not take UNORDERED shards a live SHARD-scope session holds; "
                            + "the ordered lane is unaffected by such a session, which is why this asks "
                            + "the lane rather than the total")
                        .isZero();
                subscription.close();
            }

            // Closing hands them straight back rather than making the next consumer wait out a lease.
            session.close();
            try (var other = queue("consumer-2")) {
                var subscription = other.consume((key, payload, payloadType) -> {
                }, ConsumerOptions.defaults());
                assertThat(subscription.shardsHeld())
                        .as("closing a session must release its shards immediately")
                        .isPositive();
                subscription.close();
            }
        }
    }

    /**
     * The long-running-handler requirement. A session that keeps its lease alive stays able to
     * acknowledge; one that has been superseded is refused, which is the same fencing every other
     * write in the engine is subject to.
     */
    @Test
    void a_session_can_extend_its_lease_and_is_refused_once_superseded() throws Exception {
        try (var queue = queue("pull-3")) {
            queue.enqueue(Message.of("only".getBytes(StandardCharsets.UTF_8), 1));

            try (var session = queue.openSession(SessionScope.SHARD, Duration.ofSeconds(2))) {
                var pulled = new ArrayList<QueueSession.PulledMessage>();
                for (var attempt = 0; attempt < 10 && pulled.isEmpty(); attempt++) {
                    pulled.addAll(session.poll(10));
                }
                assertThat(pulled).hasSize(1);

                assertThat(session.extendLease()).as("a live session must be able to extend").isTrue();

                // Supersede it: expire the leases and let someone else take them.
                try (var connection = dataSource.getConnection();
                     var statement = connection.prepareStatement(
                             "UPDATE " + ShardOwnedSchema.LEASE_TABLE + " SET lease_until = now() - interval '1 second'"
                             + " WHERE queue_id = ?")) {
                    statement.setShort(1, QUEUE_ID);
                    statement.executeUpdate();
                }
                var storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
                for (var shard = 0; shard < SHARD_COUNT; shard++) {
                    storage.acquireLease("unordered", shard, "someone-else", 60_000L);
                }

                assertThat(session.extendLease())
                        .as("a superseded session must discover it has lost its shards")
                        .isFalse();
                assertThat(session.acknowledge(pulled.stream().map(QueueSession.PulledMessage::id).toList()))
                        .as("and must not be able to delete work the new owner now owns")
                        .isFalse();
            }

            // The message survived the superseded session's attempt to acknowledge it.
            assertThat(queue.depth().total()).isEqualTo(1L);
        }
    }

    /**
     * Every scope the enum declares opens a session. There is no scope that compiles and then
     * refuses — the per-key rung was removed rather than left throwing, so "KEY is not supported" is
     * now a compile error instead of a runtime one, and this test can assert the whole enum.
     */
    @Test
    void every_declared_scope_opens_a_session() throws Exception {
        try (var queue = queue("pull-4")) {
            for (var scope : SessionScope.values()) {
                try (var session = queue.openSession(scope, Duration.ofSeconds(10))) {
                    assertThat(session).as("%s scope must open", scope).isNotNull();
                }
            }
        }
    }
}
