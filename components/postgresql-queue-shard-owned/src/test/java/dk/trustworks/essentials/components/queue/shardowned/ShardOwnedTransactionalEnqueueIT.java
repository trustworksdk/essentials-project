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
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * The outbox property: a business write and its enqueue commit together, or neither does.
 * <p>
 * This is the requirement the current implementation cannot meet — its {@code FullyTransactional}
 * mode gives the caller a transaction but breaks redelivery, because a rollback also reverts the
 * attempt count. Here the two are separate concerns by construction: the enqueue joins the caller's
 * transaction, while attempt counting and dead-lettering happen later, in the owner, outside any
 * caller's rollback scope.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedTransactionalEnqueueIT {

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
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS orders");
            statement.execute("CREATE TABLE orders (id text PRIMARY KEY)");
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void a_business_write_and_its_enqueue_commit_together() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "outbox-1")) {
            queue.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());

            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                insertOrder(connection, "order-1");
                queue.enqueue(connection, List.of(Message.of("order-1-placed".getBytes(StandardCharsets.UTF_8), 1)));
                connection.commit();
            }

            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .until(() -> delivered.contains("order-1-placed"));
            assertThat(orderIds()).containsExactly("order-1");
        }
    }

    @Test
    void a_rollback_takes_the_messages_with_it() throws Exception {
        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "outbox-2")) {
            queue.consume((key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());

            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                insertOrder(connection, "order-2");
                queue.enqueue(connection, List.of(Message.of("order-2-placed".getBytes(StandardCharsets.UTF_8), 1),
                                                  Message.ordered("o".getBytes(StandardCharsets.UTF_8), 1, "k", 0L)));
                connection.rollback();
            }

            // Enough time for the notification to have been acted on, had one escaped the rollback.
            // It cannot have: pg_notify inside a transaction is discarded with it.
            Thread.sleep(2_000);

            assertThat(delivered).describedAs("a rolled-back enqueue must deliver nothing").isEmpty();
            assertThat(orderIds()).describedAs("the business write rolled back too").isEmpty();
            assertThat(queue.depth().total()).describedAs("no rows survive the rollback").isZero();
        }
    }

    /**
     * A failing business write after the enqueue must take the messages with it. This is the shape
     * that actually bites in an outbox: the enqueue succeeded, so a caller could reasonably believe
     * the message is safe, and only the later failure decides otherwise.
     */
    @Test
    void a_business_write_that_fails_after_the_enqueue_undoes_it() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "outbox-3")) {
            insertOrderCommitted("duplicate");

            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                queue.enqueue(connection, List.of(Message.of("never-sent".getBytes(StandardCharsets.UTF_8), 1)));
                assertThatThrownBy(() -> insertOrder(connection, "duplicate"))
                        .describedAs("the primary key violation is the injected failure, and it must really fire")
                        .isInstanceOf(java.sql.SQLException.class);
                connection.rollback();
            }

            assertThat(queue.depth().total())
                    .describedAs("the enqueue is undone by the failure that followed it")
                    .isZero();
        }
    }

    /**
     * A connection in autocommit cannot carry a transaction for the enqueue to join, so accepting one
     * would silently give the caller the non-transactional behaviour they were trying to avoid.
     */
    @Test
    void an_autocommit_connection_is_refused_rather_than_quietly_committing() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "outbox-4");
             var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            assertThatThrownBy(() -> queue.enqueue(connection,
                                                   List.of(Message.of("x".getBytes(StandardCharsets.UTF_8), 1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("autocommit");
            assertThat(queue.depth().total()).isZero();
        }
    }

    private void insertOrder(java.sql.Connection connection, String id) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO orders (id) VALUES (?)")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private void insertOrderCommitted(String id) throws Exception {
        try (var connection = dataSource.getConnection()) {
            insertOrder(connection, id);
        }
    }

    private List<String> orderIds() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT id FROM orders ORDER BY id");
             var resultSet = statement.executeQuery()) {
            var ids = new ArrayList<String>();
            while (resultSet.next()) {
                ids.add(resultSet.getString(1));
            }
            return ids;
        }
    }
}
