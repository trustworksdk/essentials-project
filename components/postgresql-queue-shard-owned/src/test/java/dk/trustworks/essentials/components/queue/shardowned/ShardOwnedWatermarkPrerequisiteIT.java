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

import static org.assertj.core.api.Assertions.*;

/**
 * The ordered lane must refuse to start where its safety mechanism cannot work.
 * <p>
 * Its cursor passes a sequence value only once {@code max(backend_xid)} over {@code pg_stat_activity}
 * proves no running write transaction could still commit a lower one. Where that column is not
 * readable — a managed provider, a hardened role, a later major changing the rules — the query does
 * not fail. It returns a subset of the running transactions, and a subset is not a degraded answer
 * but a wrong one: the watermark advances over a live writer and steps over its message.
 * <p>
 * That is the failure mode this exists for, and it is why the probe constructs the condition rather
 * than inspecting the column: {@code backend_xid} is legitimately null for a backend that has not
 * written, so finding a null proves nothing either way.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedWatermarkPrerequisiteIT {

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource superuser;

    @BeforeEach
    void setUp() {
        superuser = pool(postgres.getUsername(), postgres.getPassword());
    }

    @AfterEach
    void tearDown() throws Exception {
        // Undo the revoke, whichever test ran: the container is shared across methods.
        try (var connection = superuser.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("GRANT SELECT ON pg_catalog.pg_stat_activity TO PUBLIC");
        }
        superuser.close();
    }

    @Test
    void an_ordinary_role_can_see_another_backends_transaction_id() throws Exception {
        // The everyday case, and worth pinning: PostgreSQL 17.5 shows backend_xid to a plain LOGIN
        // role with no pg_read_all_stats. If a future major or a provider changes that, this fails
        // and says so, rather than the engine losing messages somewhere far away.
        try (var appRole = ordinaryRole("queue_app_ok")) {
            assertThatCode(() -> ShardOwnedSchema.verifyWatermarkPrerequisites(appRole))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * The disconfirming half. Redaction cannot be simulated directly, so the view is made unreadable
     * instead — the same observable outcome the probe exists to catch, reached the only way this
     * database will allow.
     */
    @Test
    void the_ordered_lane_refuses_to_start_when_the_view_is_unreadable() throws Exception {
        try (var appRole = ordinaryRole("queue_app_blind")) {
            // Verify the injection worked before relying on it: a revoke that silently did nothing
            // would make this test pass against a broken probe.
            revokeStatActivity();
            try (var connection = appRole.getConnection();
                 var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM pg_stat_activity"))
                        .as("the revoke must actually have taken effect")
                        .hasMessageContaining("permission denied");
            }

            assertThatThrownBy(() -> ShardOwnedSchema.verifyWatermarkPrerequisites(appRole))
                    .as("the ordered lane must refuse rather than compute a bound it cannot trust")
                    .hasMessageContaining("pg_read_all_stats");

            // And the refusal must reach anyone starting an ordered consumer, not just the probe.
            // Grant AFTER recreate: recreate drops and re-makes the tables, so grants issued when the
            // role was created no longer apply to them, and the consumer would fail on a permission
            // error before ever reaching the probe.
            ShardOwnedSchema.recreate(superuser);
            ShardOwnedSchema.registerQueue(superuser, (short) 1, 4);
            grantTables("queue_app_blind");
            try (var queue = new ShardOwnedQueue(appRole, (short) 1, 4, "blind-1")) {
                // start() wraps whatever went wrong, so the actionable text is in the cause chain
                // rather than the top-level message — which is where a reader of the log will find
                // it too.
                assertThatThrownBy(() -> queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
                }, ShardOwnerSettings.defaults(), ShardOwnedSchema.ORDERED_UNITS))
                        .as("startConsumingOrdered must fail loudly on such a database")
                        .hasStackTraceContaining("pg_read_all_stats");
            }
        }
    }

    private void grantTables(String role) throws Exception {
        try (var connection = superuser.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + role);
            statement.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + role);
        }
    }

    private void revokeStatActivity() throws Exception {
        try (var connection = superuser.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("REVOKE SELECT ON pg_catalog.pg_stat_activity FROM PUBLIC");
        }
    }

    /** A plain LOGIN role: no superuser, no pg_read_all_stats, table privileges only. */
    private HikariDataSource ordinaryRole(String role) throws Exception {
        try (var connection = superuser.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP ROLE IF EXISTS " + role);
            statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'probe'");
            statement.execute("GRANT ALL ON SCHEMA public TO " + role);
            statement.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + role);
            statement.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + role);
        }
        return pool(role, "probe");
    }

    /**
     * A {@code consume()} that fails part-way must leave nothing registered and nothing running.
     * <p>
     * {@code consume()} builds two consumers, one per lane, and starts them in order. The ordered one
     * starts with {@code verifyWatermarkPrerequisites}, so on a database that hides {@code backend_xid}
     * the second start throws after the first has already leased shards — which makes this the natural
     * place to pin the failure path.
     * <p>
     * It used to register both consumers before starting either. The throw then escaped with the
     * unordered consumer <em>running</em>, holding leases, and reachable by nobody: {@code consume()}
     * returns the only handle and it never returned. A caller retrying — the obvious response to a
     * start-up failure — took the next subscription index, so one process registered as two instances,
     * and {@code fairShare} then held each of them to half the shards of a cluster that did not exist.
     */
    @Test
    void a_failed_consume_leaves_nothing_running() throws Exception {
        try (var appRole = ordinaryRole("queue_app_halfstart")) {
            ShardOwnedSchema.recreate(superuser);
            ShardOwnedSchema.registerQueue(superuser, (short) 1, 4);
            grantTables("queue_app_halfstart");
            revokeStatActivity();

            var queue = PostgresqlMessageQueue.builder()
                                              .setDataSource(appRole)
                                              .setQueueId((short) 1)
                                              .setShardCount(4)
                                              .setInstanceId("halfstart-1")
                                              .build();

            assertThatThrownBy(() -> queue.consume((messageId, key, payload, payloadType) -> {
            }, ConsumerOptions.defaults()))
                    .as("the ordered lane's probe must fail the whole subscription")
                    .hasStackTraceContaining("pg_read_all_stats");

            assertThat(queue.isStarted())
                    .as("a subscription that threw must not leave the queue reporting started")
                    .isFalse();
            assertThat(ownedLeases())
                    .as("the unordered consumer started before the ordered one threw; it must not "
                        + "still be holding leases that no handle can release")
                    .isZero();

            // The retry a caller actually makes. Without the rollback this registered a SECOND
            // instance id, which is what turned a start-up failure into stranded shards.
            assertThatThrownBy(() -> queue.consume((messageId, key, payload, payloadType) -> {
            }, ConsumerOptions.defaults()))
                    .hasStackTraceContaining("pg_read_all_stats");
            assertThat(liveInstances())
                    .as("retrying a failed consume must not accumulate instances")
                    .isLessThanOrEqualTo(1);
        }
    }

    private int ownedLeases() throws Exception {
        return countOf("SELECT count(*) FROM shard_queue_lease WHERE owner IS NOT NULL");
    }

    private int liveInstances() throws Exception {
        return countOf("SELECT count(*) FROM shard_queue_instance");
    }

    private int countOf(String sql) throws Exception {
        try (var connection = superuser.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private HikariDataSource pool(String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(6);
        return new HikariDataSource(config);
    }
}
