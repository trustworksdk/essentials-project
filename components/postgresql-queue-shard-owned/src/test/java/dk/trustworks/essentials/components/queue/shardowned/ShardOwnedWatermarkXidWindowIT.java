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
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedStorage.OrderedPayload;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watermark's premise, taken at its word: "a transaction is assigned its xid no later than the value it
 * allocates".
 * <p>
 * PostgreSQL does not promise that. {@code nextval()} is non-transactional and assigns no xid; a transaction gets
 * one lazily, at its first heap write, and for {@code INSERT ... VALUES (..., nextval(...))} that write happens
 * AFTER the value is taken. In between, the writer holds a sequence value while its {@code backend_xid} is null,
 * so the watermark sees no running writer and steps over the value. The row commits a moment later below the
 * cursor, where only the head sweep finds it - after a later {@code key_order} of the same key has been
 * delivered.
 * <p>
 * Found in the trading demo, with two instances producing into the same ordered units: one ordering violation in
 * roughly twenty minutes, on a key whose producer numbered and committed strictly in order. The window is
 * microseconds wide there. Here a {@code BEFORE INSERT} trigger holds it open for seconds: it runs after the
 * {@code VALUES} expressions, {@code nextval} included, and before the heap insert that would assign the xid.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedWatermarkXidWindowIT {

    private static final short  QUEUE_ID    = 1;
    private static final int    SHARD_COUNT = 4;
    private static final String HELD_KEY    = "K-held";

    /**
     * Sweeps 20 s apart, so the head sweep cannot recover a stepped-over row inside this test and make a
     * failure look like a slow success. {@code watermarkCap} is longer than the test for the same reason.
     */
    private static final ShardOwnerSettings SETTINGS = new ShardOwnerSettings(500,
                                                                              200,
                                                                              Duration.ofMillis(1),
                                                                              Duration.ofMillis(2),
                                                                              Duration.ofMillis(200),
                                                                              Duration.ofSeconds(20),   // sweepInterval
                                                                              1_000,
                                                                              8,
                                                                              Duration.ofMillis(500),
                                                                              Duration.ofSeconds(20),   // maxSweepInterval
                                                                              2,
                                                                              Duration.ofSeconds(5),
                                                                              Duration.ofSeconds(30),
                                                                              Duration.ofSeconds(30));  // watermarkCap

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            // Pauses exactly one insert, between nextval and the heap write.
            statement.execute("""
                              CREATE OR REPLACE FUNCTION test_pause_before_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                              BEGIN
                                  IF NEW.msg_key = '%s' AND NEW.key_order = 0 THEN
                                      PERFORM pg_sleep(3);
                                  END IF;
                                  RETURN NEW;
                              END $$""".formatted(HELD_KEY));
            statement.execute("CREATE TRIGGER test_pause_before_insert BEFORE INSERT ON " + ShardOwnedSchema.ORDERED_TABLE
                                      + " FOR EACH ROW EXECUTE FUNCTION test_pause_before_insert()");
            // nextval() assigns an xid only when it WAL-logs, which a sequence does once per 32 values -
            // pre-logging the next 32 as it goes. The first call on a fresh sequence is such a call, and
            // would hide the window. Taking one value here makes the calls under test the ordinary kind,
            // which is 31 in every 32 of them in production.
            statement.execute("SELECT nextval('" + ShardOwnedSchema.orderedSequenceName(QUEUE_ID) + "')");
        }
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void a_value_allocated_before_its_transaction_has_an_xid_is_not_stepped_over() throws Exception {
        var storage   = new ShardOwnedStorage(dataSource, QUEUE_ID);
        var units     = storage.orderedUnits();
        var unit      = ShardOwnedSchema.unitForKey(HELD_KEY, units);
        var neighbour = keyInUnit(unit, units);
        var received  = new CopyOnWriteArrayList<String>();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsumingOrdered((messageId, key, payload, payloadType) ->
                                                received.add(key + "/" + new String(payload, StandardCharsets.UTF_8)),
                                        SETTINGS,
                                        SHARD_COUNT);

            // Writer A takes its sequence value and is held before the heap insert.
            var writerA = CompletableFuture.runAsync(() -> enqueue(queue, HELD_KEY, 0));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(this::writerIsPausedInTrigger);

            // Writer B takes a HIGHER value in the same unit and commits. The owner reads it and, if it
            // cannot see A, advances the watermark past A's value.
            enqueue(queue, neighbour, 0);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> received.contains(neighbour + "/0"));

            // A commits below the watermark, and the producer - strictly in order - sends the next one.
            writerA.get(10, TimeUnit.SECONDS);
            enqueue(queue, HELD_KEY, 1);

            Awaitility.await().atMost(Duration.ofSeconds(15))
                      .untilAsserted(() -> assertThat(received.stream().filter(entry -> entry.startsWith(HELD_KEY + "/")).toList())
                              .containsExactly(HELD_KEY + "/0", HELD_KEY + "/1"));
            assertThat(queue.metrics().orderViolations.sum()).isZero();
        }
    }

    private boolean writerIsPausedInTrigger() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep'");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1) == 1;
        }
    }

    private static String keyInUnit(int unit, int units) {
        for (var index = 0; ; index++) {
            var candidate = "neighbour-" + index;
            if (ShardOwnedSchema.unitForKey(candidate, units) == unit) {
                return candidate;
            }
        }
    }

    private static void enqueue(ShardOwnedQueue queue, String key, long keyOrder) {
        try {
            queue.enqueueOrdered(List.of(new OrderedPayload(key, keyOrder, Long.toString(keyOrder).getBytes(StandardCharsets.UTF_8), 1)));
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
}
