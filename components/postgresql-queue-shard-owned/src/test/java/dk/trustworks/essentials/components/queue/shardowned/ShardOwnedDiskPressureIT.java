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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * The disk under the database filling up, and what the engine does about it.
 *
 * <h2>Why this is not covered by the other failure tests</h2>
 * {@code ShardOwnedDatabaseFailureIT} takes the database away and gives it back; a full disk leaves it
 * answering. Connections are healthy, reads work, the server is not down — and every write fails.
 * That is the combination none of the other tests produce, and it reaches the engine in three places
 * at once: the enqueue a caller is waiting on, the acknowledgement flush that retires delivered work,
 * and the dead-letter write that is supposed to be the escape hatch for a message that cannot be
 * handled.
 * <p>
 * The bar is the same as every other failure here: <b>recover, and lose nothing.</b> At-least-once
 * permits a message handled during the outage to be delivered again after it, because its
 * acknowledgement could not be written — that is the contract working, not a defect.
 *
 * <h2>What a full disk actually does to PostgreSQL, which is worse than it sounds</h2>
 * Not a write that returns an error. A WAL write that cannot complete is a {@code PANIC}: the backend
 * takes the whole cluster down with it, every connection dies at once, and the postmaster comes back
 * through crash recovery. Measured here — {@code PANIC: could not write to file "pg_wal/xlogtemp.NN":
 * No space left on device} after 65 MB of filler. So "the disk filled up" reaches an application as a
 * server that vanished mid-statement, and it will do it again on the next write until somebody frees
 * space.
 * <p>
 * That is also why the space is given back from <b>outside</b> the database. A full cluster cannot
 * {@code TRUNCATE} its way out — that is a write too — which is why the operational advice is to keep
 * a ballast file on the volume and delete it in the emergency. This test keeps one, and deleting it is
 * the recovery.
 *
 * <h2>How the disk is filled</h2>
 * The whole data directory is a small tmpfs, so a filler table can exhaust it in seconds. Filling this
 * way makes the WAL and the queue's own tables share the shortage exactly as they would on a real
 * volume — which is the point, since it is the WAL that fails first.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedDiskPressureIT {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedDiskPressureIT.class);

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 2;

    /**
     * Above what {@code initdb} needs — an empty cluster is around 40 MB — and small enough that
     * filling it is quick. Too tight and the server never starts, which reads as a failure of the
     * engine rather than of the test.
     */
    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.createWithSizedDataDirectory("256m");

    /** The emergency reserve, in megabytes: the thing deleted to give a full cluster room to move. */
    private static final int BALLAST_MB = 48;

    private static final String BALLAST = LabPostgres.DATA_DIRECTORY + "/ballast";

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(5_000);
        dataSource = new HikariDataSource(config);

        awaitAccepting();
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        execute("DROP TABLE IF EXISTS disk_filler");
        execute("CREATE TABLE disk_filler (id bigserial PRIMARY KEY, blob bytea)");
        layBallast();
    }

    @AfterEach
    void tearDown() throws Exception {
        postgres.execInContainer("rm", "-f", BALLAST);
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void the_engine_survives_a_full_disk_and_loses_nothing_when_the_space_comes_back() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1", new ShardOwnerMetrics())) {
            queue.startConsuming((messageId, payload, type) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(), SHARD_COUNT);

            for (var index = 0; index < 20; index++) {
                queue.enqueue(List.of(("before-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(distinct(received, "before-")).isEqualTo(20L));

            var outage = fillTheDisk();
            log.info("Disk full after {} MB of filler; PostgreSQL said: {}", outage.megabytesWritten(), outage.failure());
            assertThat(outage.failure())
                    .as("the failure being tested is the WAL running out of room, not a table refusing a row")
                    .contains("No space left on device");

            // What matters is not WHERE the queue starts failing — that depends on whether its next
            // row needs a new page — but that each enqueue either lands or throws. An accepted
            // message that is not there afterwards is the only unacceptable outcome, so the accepted
            // ones are recorded and checked after recovery.
            var accepted = new ArrayList<String>();
            var refused = 0;
            for (var index = 0; index < 20; index++) {
                var message = "during-" + index;
                try {
                    queue.enqueue(List.of(message.getBytes(StandardCharsets.UTF_8)), 1);
                    accepted.add(message);
                } catch (Exception e) {
                    refused++;
                }
            }
            log.info("With the disk full, {} enqueues were accepted and {} refused", accepted.size(), refused);

            // The engine has to still be there. A pump thread that died on the write failure leaves a
            // shard held and unserved, which is the failure mode this whole suite exists to catch.
            assertThat(queue.shardsHeld())
                    .as("a full disk must not cost the engine its shards")
                    .isEqualTo(SHARD_COUNT);

            // From outside the database, because a cluster with no room cannot write its way out —
            // TRUNCATE is a write too. This is the ballast file an operator is told to keep.
            postgres.execInContainer("rm", "-f", BALLAST);
            awaitAccepting();
            execute("TRUNCATE disk_filler");
            log.info("Space returned");

            // Recovery is the whole point: no restart, no intervention.
            for (var index = 0; index < 20; index++) {
                queue.enqueue(List.of(("after-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(distinct(received, "after-"))
                              .as("the engine must resume delivering once the space is back")
                              .isEqualTo(20L));

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(received)
                              .as("every enqueue the full disk ACCEPTED must still be delivered - "
                                  + "refusing is allowed, losing is not")
                              .containsAll(accepted));

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(queue.remaining())
                              .as("and must retire what it delivered, including anything whose "
                                  + "acknowledgement could not be written during the outage")
                              .isZero());
        }
    }

    /**
     * Fills the volume in decreasing row sizes, because "the disk is full" is not one state.
     * <p>
     * A megabyte failing to land does not mean thirty bytes will: the first attempt at this stopped
     * at {@code could not extend file} for the filler's own relation while a queue insert still fit
     * comfortably — free space in an already-allocated page, and room left for its WAL record. Real
     * volumes behave the same way, which is why an application often sees its first disk-full error
     * long before the disk stops taking writes altogether. Stepping the size down drives it to the
     * state this test is about: the one where even a small write has nowhere to go.
     */
    private Outage fillTheDisk() {
        var bytesWritten = 0L;
        var lastFailure = "";
        for (var rowSize : new int[]{1024 * 1024, 64 * 1024, 8 * 1024, 512}) {
            for (var attempt = 0; attempt < 8192; attempt++) {
                try (var connection = dataSource.getConnection();
                     var statement = connection.prepareStatement(
                             "INSERT INTO disk_filler (blob) VALUES (?)")) {
                    // Random, because TOAST compresses a megabyte of one repeated byte into almost
                    // nothing and the disk would never fill.
                    statement.setBytes(1, random(rowSize));
                    statement.executeUpdate();
                    bytesWritten += rowSize;
                } catch (Exception e) {
                    lastFailure = String.valueOf(e.getMessage());
                    log.info("Writes of {} bytes stopped after {} MB: {}",
                             rowSize, bytesWritten / (1024 * 1024), lastFailure);
                    break;
                }
            }
        }
        if (lastFailure.isBlank()) {
            throw new IllegalStateException("The data directory did not fill - is it really a sized tmpfs?");
        }
        return new Outage((int) (bytesWritten / (1024 * 1024)), lastFailure);
    }

    private record Outage(int megabytesWritten, String failure) {
    }

    /** A file to delete when there is no room left to do anything else. */
    private void layBallast() throws Exception {
        var result = postgres.execInContainer("dd", "if=/dev/zero", "of=" + BALLAST,
                                              "bs=1M", "count=" + BALLAST_MB);
        assertThat(result.getExitCode()).as("could not lay the ballast: %s", result.getStderr()).isZero();
    }

    /**
     * Wait for the cluster to answer again.
     * <p>
     * A {@code PANIC} takes every connection with it and the postmaster returns through crash
     * recovery, so both the fill and the recovery leave a window where there is no server to talk to.
     * Probing on a fresh connection keeps that wait independent of the pool the engine is also
     * recovering.
     */
    private void awaitAccepting() {
        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .pollInterval(Duration.ofMillis(500))
                  .until(() -> {
                      try (var connection = java.sql.DriverManager.getConnection(
                              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                           var statement = connection.createStatement()) {
                          statement.execute("SELECT 1");
                          return true;
                      } catch (Exception e) {
                          return false;
                      }
                  });
    }

    private static byte[] random(int size) {
        var bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private static long distinct(List<String> received, String prefix) {
        return received.stream().filter(message -> message.startsWith(prefix)).distinct().count();
    }

    private void execute(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
