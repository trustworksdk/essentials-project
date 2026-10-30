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

package dk.trustworks.essentials.components.distributed.fencedlock.postgresql;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LockSpreading}: three lock managers that each ask for the same locks asynchronously, as three instances of an
 * application with exclusive event processors do.
 * <p>
 * The control: without spreading, the instance that asks first takes every free lock. With it, the locks spread over
 * the instances, and when one instance stops, its locks spread over the survivors rather than landing on one.
 */
@Testcontainers
class PostgresqlFencedLockSpreadingIT {
    private static final int      LOCKS                      = 30;
    private static final Duration LOCK_TIME_OUT              = Duration.ofSeconds(3);
    private static final Duration LOCK_CONFIRMATION_INTERVAL = Duration.ofMillis(500);

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("lock-db");

    private final List<PostgresqlFencedLockManager>   managers = new ArrayList<>();
    /**
     * Lock name to the instance id of the manager whose callback holds it now
     */
    private final Map<String, String>                 holders  = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        jdbi().useHandle(handle -> handle.execute("DROP TABLE IF EXISTS " + PostgresqlFencedLockStorage.DEFAULT_FENCED_LOCKS_TABLE_NAME));
    }

    @AfterEach
    void tearDown() {
        managers.forEach(manager -> {
            for (var index = 0; index < LOCKS; index++) {
                manager.cancelAsyncLockAcquiring(lockName(index));
            }
            manager.stop();
        });
    }

    @Test
    void without_spreading_the_instance_that_asks_first_takes_every_free_lock() {
        startManagers(false);
        managers.forEach(this::acquireAllLocksAsync);

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> holders.size() == LOCKS);
        assertThat(locksHeldBy()).as("locks held per instance").containsEntry("node1", LOCKS);
    }

    @Test
    void with_spreading_free_locks_spread_over_the_instances_and_a_stopped_instances_locks_over_the_survivors() {
        startManagers(true);
        // Every instance has joined and read the others before the first lock is asked for
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> membershipRows() == managers.size());
        sleep(LOCK_CONFIRMATION_INTERVAL.multipliedBy(2));
        managers.forEach(this::acquireAllLocksAsync);

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> holders.size() == LOCKS);
        var spread = locksHeldBy();
        assertThat(spread).as("locks held per instance").containsOnlyKeys("node1", "node2", "node3");
        spread.values().forEach(held -> assertThat(held).as("locks held per instance: %s", spread).isGreaterThanOrEqualTo(LOCKS / 6));

        var stopped = managers.removeLast();
        for (var index = 0; index < LOCKS; index++) {
            stopped.cancelAsyncLockAcquiring(lockName(index));
        }
        stopped.stop();
        var stoppedHeld = spread.get("node3");
        Awaitility.await().atMost(Duration.ofSeconds(20))
                  .until(() -> holders.size() == LOCKS && !holders.containsValue("node3"));
        var afterStop = locksHeldBy();
        System.out.printf("%nLock spreading: %s, after node3 stopped: %s%n%n", spread, afterStop);
        assertThat(afterStop).as("locks held per instance after node3 stopped").containsOnlyKeys("node1", "node2");
        assertThat(afterStop.get("node1") - spread.get("node1"))
                .as("node3's %d locks went to both survivors: before %s, after %s", stoppedHeld, spread, afterStop)
                .isPositive();
        assertThat(afterStop.get("node2") - spread.get("node2"))
                .as("node3's %d locks went to both survivors: before %s, after %s", stoppedHeld, spread, afterStop)
                .isPositive();
    }

    private void startManagers(boolean spreadLocks) {
        for (var node = 1; node <= 3; node++) {
            var jdbi = jdbi();
            var manager = PostgresqlFencedLockManager.builder()
                                                     .setJdbi(jdbi)
                                                     .setUnitOfWorkFactory(new JdbiUnitOfWorkFactory(jdbi))
                                                     .setLockManagerInstanceId("node" + node)
                                                     .setLockTimeOut(LOCK_TIME_OUT)
                                                     .setLockConfirmationInterval(LOCK_CONFIRMATION_INTERVAL)
                                                     .setSpreadLocks(spreadLocks)
                                                     .build();
            manager.start();
            managers.add(manager);
        }
    }

    private void acquireAllLocksAsync(PostgresqlFencedLockManager manager) {
        for (var index = 0; index < LOCKS; index++) {
            manager.acquireLockAsync(lockName(index),
                                     LockCallback.builder()
                                                 .onLockAcquired(lock -> holders.put(lock.getName().toString(), lock.getLockedByLockManagerInstanceId()))
                                                 .onLockReleased(lock -> holders.remove(lock.getName().toString(), manager.getLockManagerInstanceId()))
                                                 .build());
        }
    }

    private Map<String, Integer> locksHeldBy() {
        return holders.values().stream().collect(Collectors.groupingBy(holder -> holder, TreeMap::new, Collectors.summingInt(holder -> 1)));
    }

    private long membershipRows() {
        return jdbi().withHandle(handle -> handle.createQuery("SELECT count(*) FROM " + PostgresqlFencedLockStorage.DEFAULT_FENCED_LOCKS_TABLE_NAME
                                                                      + " WHERE lock_name LIKE '" + LockSpreading.MEMBERSHIP_LOCK_NAME_PREFIX + "%' AND locked_by_lockmanager_instance_id IS NOT NULL")
                                                 .mapTo(Long.class)
                                                 .one());
    }

    private static LockName lockName(int index) {
        return LockName.of("lock-" + index);
    }

    private static Jdbi jdbi() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        return jdbi;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
