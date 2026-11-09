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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema.*;
import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * A takeover's charge is made once per ownership, and the exhaustion dead-letter pass that follows it is retried until
 * it has run once - but only then. Mostly driven through {@code onTakeover} on an owner built by hand, with the
 * dead-letter insert made to fail by a trigger: the move throws, and {@code onTakeover} is called again on the same
 * owner, which is what the pump does - 200 ms later while the connection is healthy, after a reconnect when not.
 * <ul>
 *     <li>On a healthy connection the pump must retry the pass without reconnecting, and keep serving its other
 *     shards meanwhile, at their full rate: only the failing shard's retry is paced, not the pump's passes. It used
 *     to treat the failure as a lost connection: it reconnected several times a second and
 *     logged that at WARN. A pass runs every takeover before it pumps any shard, so the throw ended each pass before
 *     any shard was pumped, and every shard the pump served stalled, whatever its position among the pump's
 *     owners.</li>
 *     <li>An owner whose takeover failed is not pumped until it completes. On the ordered lane that is what keeps a key
 *     behind its dead letter: the blocks are loaded at the end of {@code onTakeover}, after the exhaustion pass, so a
 *     failed pass has not loaded them.</li>
 *     <li>An unchecked exception from {@code onTakeover} is retried the same way, and does not end the pump thread. It
 *     used to, and every shard on the pump stalled with it.</li>
 *     <li>While it keeps failing, the shard is leased but not served, which {@code QueueHealth.unownedShards} cannot
 *     see. The owner's metrics must report it ({@code shardsAwaitingTakeover}, {@code takeoverFailures}), and it is
 *     logged at ERROR once rather than on every retry. It used to log a stack trace per retry and count nothing.
 *     The count falls again when the shard's lease ends or its pump stops, the takeover still failing. A stall
 *     outlives the pump's connection: after a reconnect the shard is still counted once, and its stall neither ends
 *     nor starts again.</li>
 *     <li>The retry must not charge the window a second time. The flag used to be set only after the pass, so a
 *     pass that failed left the charge unrecorded and every reconnect charged again.</li>
 *     <li>A plain reconnect after the pass has run must not run it again: it would dead-letter a row this owner
 *     itself is delivering on its last attempt.</li>
 *     <li>The dead letter's text, and the cause the observer is given, must hold for a row a pull session failed
 *     before the takeover - the live lanes keep no error, so it cannot claim that no failure was ever recorded.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedTakeoverExhaustionRetryIT {

    private static final short  QUEUE_ID     = 1;
    private static final int    SHARD_COUNT  = 4;
    private static final int    SHARD        = 0;
    private static final int    MAX_ATTEMPTS = 3;
    private static final String OWNER        = "owner-1";
    /**
     * The {@code application_name} of a pump pool kept apart from the test's own, so its backends can be terminated alone.
     */
    private static final String PUMP_APPLICATION_NAME = "takeover-retry-pump";

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource  dataSource;
    private ShardOwnedStorage storage;
    private List<DeadLettered> deadLettered;
    /**
     * Every {@code QueueObserver.takeoverStalled} call's {@code stalled}, in order, from every owner built by {@link #metrics()}.
     */
    private List<Boolean> takeoverStallTransitions;
    private ListAppender<ILoggingEvent>   pumpLog;
    private ch.qos.logback.classic.Logger pumpLogger;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
        deadLettered = new CopyOnWriteArrayList<>();
        takeoverStallTransitions = new CopyOnWriteArrayList<>();
        pumpLog = new ListAppender<>();
        pumpLog.start();
        pumpLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardPump.class);
        pumpLogger.setLevel(Level.DEBUG);
        pumpLogger.addAppender(pumpLog);
    }

    @AfterEach
    void tearDown() {
        pumpLogger.detachAppender(pumpLog);
        pumpLogger.setLevel(null);
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void an_unordered_takeover_whose_dead_letter_pass_fails_charges_once_and_parks_the_exhausted_row_on_the_retry() throws Exception {
        // seq 1 has one attempt left, the first of them failed by a row lease session; seq 2 has never been delivered
        insertUnordered(1, MAX_ATTEMPTS - 2, -7L);
        try (var connection = dataSource.getConnection()) {
            assertThat(storage.failSessionRow(connection, SHARD, 1, -7L)).isTrue();
        }
        insertUnordered(2, 0, null);
        var fence = storage.acquireLease("unordered", SHARD, OWNER, 60_000).orElseThrow();
        var owner = new ShardOwner(storage, SHARD, fence, ShardOwnerSettings.defaults(), (id, payload, type) -> { },
                                   metrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(),
                                   OWNER, HandlerDispatch.inline(), false);

        assertTakeoverRetriedAfterAFailedDeadLetterPass(owner, UNORDERED_TABLE, MessageId.Lane.UNORDERED);

        // A plain reconnect: a row this owner is delivering on its last attempt is not parked under it
        insertUnordered(3, MAX_ATTEMPTS, null);
        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }
        assertThat(attempts(UNORDERED_TABLE, 3)).as("not charged by a reconnect").isEqualTo(MAX_ATTEMPTS);
        assertThat(deadLetterError(MessageId.Lane.UNORDERED, 3)).as("not dead-lettered by a reconnect").isEmpty();
    }

    @Test
    void an_ordered_takeover_whose_dead_letter_pass_fails_charges_once_and_parks_the_exhausted_row_on_the_retry() throws Exception {
        insertOrdered("key-1", 0, 1, MAX_ATTEMPTS - 1);
        insertOrdered("key-2", 0, 2, 0);
        var fence = storage.acquireLease("ordered", SHARD, OWNER, 60_000).orElseThrow();
        var owner = new OrderedShardOwner(storage, SHARD, fence, ShardOwnerSettings.defaults(), (id, key, payload, type) -> { },
                                          metrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), OWNER,
                                          new ShardWakeup(), HandlerDispatch.inline(), false);

        assertTakeoverRetriedAfterAFailedDeadLetterPass(owner, ORDERED_TABLE, MessageId.Lane.ORDERED);

        insertOrdered("key-3", 0, 3, MAX_ATTEMPTS);
        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }
        assertThat(attempts(ORDERED_TABLE, 3)).as("not charged by a reconnect").isEqualTo(MAX_ATTEMPTS);
        assertThat(deadLetterError(MessageId.Lane.ORDERED, 3)).as("not dead-lettered by a reconnect").isEmpty();
    }

    @Test
    void a_pump_retries_a_failing_dead_letter_pass_on_a_healthy_connection_and_keeps_serving_its_other_shards() throws Exception {
        // Shard 0: seq 1 reaches the limit with the takeover charge, seq 2 is charged once and then delivered
        insertUnordered(1, MAX_ATTEMPTS - 1, null);
        insertUnordered(2, 0, null);
        var otherShard = SHARD + 1;
        try (var connection = dataSource.getConnection()) {
            storage.enqueueBatch(connection, otherShard, List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}), 1);
        }
        var failingDeliveries = new CopyOnWriteArrayList<MessageId>();
        var otherDeliveries   = new CopyOnWriteArrayList<MessageId>();
        var failingMetrics    = metrics();
        var otherMetrics      = metrics();
        var failing = new ShardOwner(storage, SHARD, storage.acquireLease("unordered", SHARD, OWNER, 60_000).orElseThrow(),
                                     ShardOwnerSettings.defaults(), (id, payload, type) -> failingDeliveries.add(id), failingMetrics,
                                     RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(), OWNER,
                                     HandlerDispatch.inline(), false);
        var other = new ShardOwner(storage, otherShard, storage.acquireLease("unordered", otherShard, OWNER, 60_000).orElseThrow(),
                                   ShardOwnerSettings.defaults(), (id, payload, type) -> otherDeliveries.add(id), otherMetrics,
                                   RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(), OWNER,
                                   HandlerDispatch.inline(), false);
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = new ShardPump(storage, ShardOwnerSettings.defaults().runtimeScoped(), pumpMetrics, running, new AtomicBoolean(false),
                                        "pump-under-test", Optional::empty);
        failDeadLetterInserts(true);
        pump.add(failing);
        pump.add(other);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> assertThat(otherDeliveries).as("the other shard keeps delivering").hasSize(3));
            await().atMost(Duration.ofSeconds(10))
                   .untilAsserted(() -> assertThat(deadLetterInsertRefusals()).as("the failing pass is retried, 200 ms apart")
                                                                              .isGreaterThan(1));
            // Paced, not spinning: the failing owner never ran a sweep, so its own park deadline is always 0, and
            // folding it in parked the pump for 1 ms at a time (about 1 000 timed-out parks a second) while the
            // takeover failed. Its only deadline is its takeover retry: about five a second.
            var backstopPollsBefore = pumpMetrics.backstopPolls.sum();
            Thread.sleep(1_000);
            assertThat(pumpMetrics.backstopPolls.sum() - backstopPollsBefore)
                    .as("the pump parks until the failing takeover's retry is due, not 1 ms at a time")
                    .isLessThan(30);
            assertThat(pumpMetrics.connectionFailures.sum()).as("a failing statement is not a lost connection").isZero();
            assertThat(failingDeliveries).as("not pumped before its takeover has completed").isEmpty();
            assertThat(deadLetterError(MessageId.Lane.UNORDERED, 1)).as("the injected failure must reach the dead-letter move").isEmpty();
            assertThat(pumpErrors()).as("one ERROR for the stalled takeover, not one per retry").hasSize(1);
            // Leased but not served, so QueueHealth.unownedShards cannot see it: the owner's metrics must
            assertThat(failingMetrics.shardsAwaitingTakeover.get()).as("the stalled shard is reported").isEqualTo(1);
            assertThat(failingMetrics.takeoverFailures.sum()).as("every failed attempt is counted").isGreaterThan(1);
            assertThat(failingMetrics.snapshot()).containsEntry("shardsAwaitingTakeover", 1);
            assertThat(otherMetrics.shardsAwaitingTakeover.get()).isZero();
            assertThat(otherMetrics.takeoverFailures.sum()).isZero();
            assertThat(pumpMetrics.takeoverFailures.sum()).as("counted on the subscription's metrics, not the pump's").isZero();

            failDeadLetterInserts(false);
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> {
                       assertThat(deadLetterError(MessageId.Lane.UNORDERED, 1)).contains(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS);
                       assertThat(failingDeliveries).containsExactly(new MessageId(MessageId.Lane.UNORDERED, SHARD, 2));
                   });
            assertThat(failingMetrics.takeoverAttemptBumps.sum()).as("seq 1 and 2 charged once, however often the pass was retried")
                                                                 .isEqualTo(2);
            assertThat(deadLettered).containsExactly(new DeadLettered(new MessageId(MessageId.Lane.UNORDERED, SHARD, 1), MAX_ATTEMPTS,
                                                                      ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS));
            assertThat(pumpMetrics.connectionFailures.sum()).isZero();
            assertThat(failingMetrics.shardsAwaitingTakeover.get()).as("cleared once the takeover completed").isZero();
            assertThat(pumpErrors()).as("no further ERROR once it recovered").hasSize(1);
        } finally {
            stopPump(running, pump, thread);
        }
    }

    /**
     * The failing shard's takeover is paced, not the pump. Pacing the whole pump at one pass per 200 ms while one takeover
     * kept failing let the other shards run only at that rate: a backlog that takes a pass per row drained at five rows a
     * second instead of back to back.
     */
    @Test
    void a_failing_takeover_does_not_slow_the_other_shards_on_its_pump() throws Exception {
        insertUnordered(1, MAX_ATTEMPTS - 1, null);
        var otherShard        = SHARD + 1;
        var otherDeliveries   = new CopyOnWriteArrayList<MessageId>();
        var failingMetrics    = metrics();
        var running           = new AtomicBoolean(true);
        var pump              = new ShardPump(storage, ShardOwnerSettings.defaults().runtimeScoped(), new ShardOwnerMetrics(), running,
                                              new AtomicBoolean(false), "pump-under-test", Optional::empty);
        var otherWakeup       = new ShardWakeup(pump.wakeup());
        var defaults          = ShardOwnerSettings.defaults();
        // One row per read, so draining the backlog takes one pump pass per row
        var onePerRead        = ShardOwnerSettings.builder().setReadBatchSize(1).build();
        var failing = new ShardOwner(storage, SHARD, storage.acquireLease("unordered", SHARD, OWNER, 60_000).orElseThrow(),
                                     defaults, (id, payload, type) -> { }, failingMetrics,
                                     RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(pump.wakeup()), OWNER,
                                     HandlerDispatch.inline(), false);
        var other = new ShardOwner(storage, otherShard, storage.acquireLease("unordered", otherShard, OWNER, 60_000).orElseThrow(),
                                   onePerRead, (id, payload, type) -> otherDeliveries.add(id), metrics(),
                                   RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), otherWakeup, OWNER,
                                   HandlerDispatch.inline(), false);
        failDeadLetterInserts(true);
        pump.add(failing);
        pump.add(other);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> assertThat(failingMetrics.takeoverFailures.sum()).as("the takeover keeps failing").isGreaterThan(1));

            // Enqueued while the takeover is failing, and announced the way the listener would
            var backlog = 25;
            var payloads = new ArrayList<byte[]>();
            for (var index = 0; index < backlog; index++) {
                payloads.add(new byte[]{(byte) index});
            }
            var startedNanos = System.nanoTime();
            try (var connection = dataSource.getConnection()) {
                storage.enqueueBatch(connection, otherShard, payloads, 1);
            }
            otherWakeup.signal();
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> assertThat(otherDeliveries).hasSize(backlog));
            var elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

            // Paced at one pass per 200 ms, 25 passes take at least 4.8 s; back to back, a fraction of that
            assertThat(elapsed).as("the other shard drains its backlog at full speed, not one pass per 200 ms")
                               .isLessThan(Duration.ofMillis(2_500));
            assertThat(failingMetrics.shardsAwaitingTakeover.get()).as("still failing throughout").isEqualTo(1);
        } finally {
            stopPump(running, pump, thread);
        }
    }

    @Test
    void a_stalled_shard_stops_being_reported_when_its_lease_ends_or_its_pump_stops() throws Exception {
        // Both shards hold a row the takeover charge exhausts, so both takeovers fail while the trigger is on
        var otherShard = SHARD + 1;
        insertUnordered(SHARD, 1, MAX_ATTEMPTS - 1, null);
        insertUnordered(otherShard, 1, MAX_ATTEMPTS - 1, null);
        var endedMetrics   = metrics();
        var stoppedMetrics = metrics();
        var ended = new ShardOwner(storage, SHARD, storage.acquireLease("unordered", SHARD, OWNER, 60_000).orElseThrow(),
                                   ShardOwnerSettings.defaults(), (id, payload, type) -> { }, endedMetrics,
                                   RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(), OWNER,
                                   HandlerDispatch.inline(), false);
        var stopped = new ShardOwner(storage, otherShard, storage.acquireLease("unordered", otherShard, OWNER, 60_000).orElseThrow(),
                                     ShardOwnerSettings.defaults(), (id, payload, type) -> { }, stoppedMetrics,
                                     RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(), OWNER,
                                     HandlerDispatch.inline(), false);
        var running = new AtomicBoolean(true);
        var pump    = new ShardPump(storage, ShardOwnerSettings.defaults().runtimeScoped(), new ShardOwnerMetrics(), running, new AtomicBoolean(false),
                                    "pump-under-test", Optional::empty);
        failDeadLetterInserts(true);
        pump.add(ended);
        pump.add(stopped);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> {
                       assertThat(endedMetrics.shardsAwaitingTakeover.get()).isEqualTo(1);
                       assertThat(stoppedMetrics.shardsAwaitingTakeover.get()).isEqualTo(1);
                   });

            // The lease ends while the takeover is still failing: the pump drops the owner, and the shard is no
            // longer held here (unownedShards sees it now), so it must stop counting as awaiting its takeover
            ended.onLeaseEnded(LeasedOwner.LeaseEnd.RELEASED);
            pump.wakeup().signal();
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> assertThat(endedMetrics.shardsAwaitingTakeover.get()).as("cleared when the lease ended").isZero());
            assertThat(stoppedMetrics.shardsAwaitingTakeover.get()).as("the other stall is untouched").isEqualTo(1);
        } finally {
            stopPump(running, pump, thread);
        }
        // The pump stopped with the takeover still failing: none of its shards is served by it any more
        assertThat(stoppedMetrics.shardsAwaitingTakeover.get()).as("cleared when the pump stopped").isZero();
        assertThat(stoppedMetrics.takeoverFailures.sum()).as("a running total, not cleared").isGreaterThan(0);
    }

    /**
     * A stall outlives the connection it began on. {@code ShardPump} keeps its {@code TakeoverStalls} for its own lifetime
     * but {@code takenOver} and {@code takeoverRetryAtNanos} per connection, so after a reconnect the stalled shard's
     * takeover is retried at once and fails again into the same stall. It must still be counted once. Stalls kept per
     * connection would count it twice and never clear the first; stalls forgotten on reconnect would tell the observer
     * the stall had ended, report it as a new one, and recover with an attempt count that has lost the first connection's.
     * <p>
     * Only the pump's backends are terminated: it gets a pool of its own under {@link #PUMP_APPLICATION_NAME}, so the
     * test's own reads and the trigger switch are not disturbed.
     */
    @Test
    void a_stalled_takeover_stays_counted_once_across_a_pump_reconnect() throws Exception {
        insertUnordered(1, MAX_ATTEMPTS - 1, null);
        var pumpConfig = new HikariConfig();
        pumpConfig.setJdbcUrl(postgres.getJdbcUrl());
        pumpConfig.setUsername(postgres.getUsername());
        pumpConfig.setPassword(postgres.getPassword());
        pumpConfig.setMaximumPoolSize(2);
        pumpConfig.addDataSourceProperty("ApplicationName", PUMP_APPLICATION_NAME);
        try (var pumpDataSource = new HikariDataSource(pumpConfig)) {
            var pumpStorage    = new ShardOwnedStorage(pumpDataSource, QUEUE_ID);
            var failingMetrics = metrics();
            var failing = new ShardOwner(pumpStorage, SHARD, storage.acquireLease("unordered", SHARD, OWNER, 60_000).orElseThrow(),
                                         ShardOwnerSettings.defaults(), (id, payload, type) -> { }, failingMetrics,
                                         RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(), OWNER,
                                         HandlerDispatch.inline(), false);
            var pumpMetrics = new ShardOwnerMetrics();
            var running     = new AtomicBoolean(true);
            var pump        = new ShardPump(pumpStorage, ShardOwnerSettings.defaults().runtimeScoped(), pumpMetrics, running, new AtomicBoolean(false),
                                            "pump-under-test", Optional::empty);
            failDeadLetterInserts(true);
            pump.add(failing);
            var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
            try {
                await().atMost(Duration.ofSeconds(15))
                       .untilAsserted(() -> {
                           assertThat(failingMetrics.shardsAwaitingTakeover.get()).isEqualTo(1);
                           assertThat(failingMetrics.takeoverFailures.sum()).as("retried on the first connection").isGreaterThan(1);
                       });
                assertThat(pumpMetrics.connectionFailures.sum()).as("still on its first connection").isZero();
                var refusalsBeforeReconnect = deadLetterInsertRefusals();

                assertThat(terminateBackends(PUMP_APPLICATION_NAME)).as("the pump's connection is cut").isPositive();
                await().atMost(Duration.ofSeconds(15))
                       .untilAsserted(() -> assertThat(pumpMetrics.connectionFailures.sum()).as("the pump reconnects").isPositive());
                // Counted after the old connection failed, so any failure from here on is a new connection's
                var failuresBeforeRetry = failingMetrics.takeoverFailures.sum();
                await().atMost(Duration.ofSeconds(15))
                       .untilAsserted(() -> assertThat(failingMetrics.takeoverFailures.sum()).as("the takeover fails again after the reconnect")
                                                                                         .isGreaterThan(failuresBeforeRetry));
                // Read after the wait, never as its condition: a refusal is counted inside the INSERT, before its takeover failure is
                assertThat(deadLetterInsertRefusals()).as("failed again at the injected refusal, not at the lost connection")
                                                      .isGreaterThan(refusalsBeforeReconnect);
                assertThat(thread.isAlive()).isTrue();
                assertThat(failingMetrics.shardsAwaitingTakeover.get()).as("counted once, not once per connection").isEqualTo(1);
                assertThat(takeoverStallTransitions).as("the observer is told the stall began, once, and not that it ended")
                                                    .containsExactly(true);
                assertThat(firstTakeoverFailureReports()).as("one stall across the reconnect, reported as it began and never again")
                                                         .hasSize(1);
                assertThat(pumpErrors()).as("one ERROR for the stalled takeover").hasSize(1);

                failDeadLetterInserts(false);
                await().atMost(Duration.ofSeconds(15))
                       .untilAsserted(() -> {
                           assertThat(failingMetrics.shardsAwaitingTakeover.get()).as("cleared once the takeover completed").isZero();
                           assertThat(takeoverStallTransitions).as("the stall ended once").containsExactly(true, false);
                           assertThat(takeoverRecoveries()).as("one recovery INFO").hasSize(1);
                       });
                assertThat(deadLetterError(MessageId.Lane.UNORDERED, 1)).as("the takeover completed").contains(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS);
                assertThat(takeoverRecoveries().getFirst().getFormattedMessage())
                        .as("the recovery counts every failed attempt, on both connections")
                        .contains("completed its takeover after " + failingMetrics.takeoverFailures.sum() + " failed attempt(s)");
                assertThat(pumpErrors()).as("no further ERROR once it recovered").hasSize(1);
            } finally {
                stopPump(running, pump, thread);
            }
        }
    }

    /**
     * {@code OrderedShardOwner.onTakeover} loads the dead-letter and retry blocks after the exhaustion pass, so while that
     * pass keeps failing the owner does not know key-1 is blocked. Pumped anyway, it would deliver key-1's second message
     * past the dead letter of its first. Once the pass succeeds the message is parked behind that dead letter instead.
     */
    @Test
    void a_pump_does_not_dispatch_an_ordered_shard_before_its_takeover_has_loaded_the_dead_letter_blocks() throws Exception {
        // key-1: its first message is already dead-lettered, so its second must never be delivered
        insertDeadLetter("key-1", 0, 1);
        insertOrdered("key-1", 1, 2, 0);
        // key-2: the takeover charge brings it to the limit, so the exhaustion pass must move it - and is refused
        insertOrdered("key-2", 0, 3, MAX_ATTEMPTS - 1);
        // key-3: nothing holds it. Delivered once the takeover completes, which proves the owner is pumped from then on
        insertOrdered("key-3", 0, 4, 0);
        var deliveredKeys = new CopyOnWriteArrayList<String>();
        var ownerMetrics  = metrics();
        var owner = new OrderedShardOwner(storage, SHARD, storage.acquireLease("ordered", SHARD, OWNER, 60_000).orElseThrow(),
                                          ShardOwnerSettings.defaults(), (id, key, payload, type) -> deliveredKeys.add(key), ownerMetrics,
                                          RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), OWNER, new ShardWakeup(),
                                          HandlerDispatch.inline(), false);
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = new ShardPump(storage, ShardOwnerSettings.defaults().runtimeScoped(), pumpMetrics, running, new AtomicBoolean(false),
                                        "pump-under-test", Optional::empty);
        failDeadLetterInserts(true);
        pump.add(owner);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            // Fail fast: an owner pumped too early delivers its rows, and then the retried pass finds nothing left to move
            await().atMost(Duration.ofSeconds(15))
                   .failFast("pumped before its takeover had loaded the dead-letter blocks",
                             () -> assertThat(deliveredKeys).as("delivered while the takeover was failing").isEmpty())
                   .untilAsserted(() -> assertThat(ownerMetrics.takeoverFailures.sum()).as("the failing takeover is retried").isGreaterThan(2));
            // Read after the wait, never as its condition: a refusal is counted inside the INSERT, before its takeover failure is
            assertThat(deadLetterInsertRefusals()).as("the takeover failed at the injected refusal, not something else").isGreaterThan(2);
            assertThat(deadLetterError(MessageId.Lane.ORDERED, 3)).as("the injected failure must reach the exhaustion pass").isEmpty();
            assertThat(deliveredKeys).as("not pumped before its takeover has loaded the dead-letter blocks").isEmpty();

            failDeadLetterInserts(false);
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> {
                       assertThat(deliveredKeys).as("pumped once its takeover completed").containsExactly("key-3");
                       assertThat(blockedByKeyOrder(2)).as("parked behind key-1's dead letter, never delivered").contains(0L);
                   });
            assertThat(deadLetterError(MessageId.Lane.ORDERED, 3)).contains(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS);
            assertThat(pumpMetrics.connectionFailures.sum()).as("a failing statement is not a lost connection").isZero();
        } finally {
            stopPump(running, pump, thread);
        }
    }

    /**
     * The pump's other takeover failure: an unchecked exception rather than a failing statement. It used to end the pump
     * thread, and every shard on the pump with it, while the heartbeat went on renewing their leases. Thrown while
     * {@code failTakeover} is set rather than for a fixed number of calls, so the test decides when the takeover
     * completes and can observe the other shard delivering in the meantime.
     */
    @Test
    void a_pump_survives_a_takeover_that_throws_and_retries_it_while_serving_its_other_shards() throws Exception {
        insertUnordered(1, 0, null);
        var otherShard = SHARD + 1;
        try (var connection = dataSource.getConnection()) {
            storage.enqueueBatch(connection, otherShard, List.of(new byte[]{1}, new byte[]{2}, new byte[]{3}), 1);
        }
        var throwingDeliveries = new CopyOnWriteArrayList<MessageId>();
        var otherDeliveries    = new CopyOnWriteArrayList<MessageId>();
        var throwingMetrics    = metrics();
        var failTakeover       = new AtomicBoolean(true);
        var takeoverCalls      = new AtomicInteger();
        var throwing = new ThrowingTakeover(new ShardOwner(storage, SHARD, storage.acquireLease("unordered", SHARD, OWNER, 60_000).orElseThrow(),
                                                           ShardOwnerSettings.defaults(), (id, payload, type) -> throwingDeliveries.add(id),
                                                           throwingMetrics, RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS),
                                                           new ShardWakeup(), OWNER, HandlerDispatch.inline(), false),
                                            failTakeover, takeoverCalls);
        var other = new ShardOwner(storage, otherShard, storage.acquireLease("unordered", otherShard, OWNER, 60_000).orElseThrow(),
                                   ShardOwnerSettings.defaults(), (id, payload, type) -> otherDeliveries.add(id), metrics(),
                                   RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), new ShardWakeup(), OWNER,
                                   HandlerDispatch.inline(), false);
        var pumpMetrics = new ShardOwnerMetrics();
        var running     = new AtomicBoolean(true);
        var pump        = new ShardPump(storage, ShardOwnerSettings.defaults().runtimeScoped(), pumpMetrics, running, new AtomicBoolean(false),
                                        "pump-under-test", Optional::empty);
        pump.add(throwing);
        pump.add(other);
        var thread = Thread.ofPlatform().name("pump-under-test").start(pump);
        try {
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> {
                       assertThat(otherDeliveries).as("the other shard keeps delivering").hasSize(3);
                       assertThat(takeoverCalls.get()).as("the throwing takeover is retried").isGreaterThan(2);
                       // Inside the wait: a call is counted before it throws, its failure only after the pump has handled it
                       assertThat(throwingMetrics.takeoverFailures.sum()).as("every failed attempt is counted").isGreaterThan(2);
                   });
            assertThat(thread.isAlive()).as("an unchecked exception from a takeover does not end the pump").isTrue();
            assertThat(throwingDeliveries).as("not pumped before its takeover has completed").isEmpty();
            assertThat(throwingMetrics.shardsAwaitingTakeover.get()).as("the stalled shard is reported").isEqualTo(1);
            assertThat(pumpErrors()).as("one ERROR for the stalled takeover, not one per retry").hasSize(1);
            assertThat(pumpErrors().getFirst().getFormattedMessage()).contains("threw during its takeover");

            failTakeover.set(false);
            await().atMost(Duration.ofSeconds(15))
                   .untilAsserted(() -> assertThat(throwingDeliveries).as("delivers once its takeover completes")
                                                                      .containsExactly(new MessageId(MessageId.Lane.UNORDERED, SHARD, 1)));
            assertThat(thread.isAlive()).isTrue();
            assertThat(throwingMetrics.shardsAwaitingTakeover.get()).as("cleared once the takeover completed").isZero();
            assertThat(pumpMetrics.connectionFailures.sum()).as("an unchecked exception is not a lost connection").isZero();
        } finally {
            stopPump(running, pump, thread);
        }
    }

    /**
     * A real owner whose {@code onTakeover} throws an unchecked exception while {@code fail} is set, and otherwise delegates
     * every call.
     */
    private static final class ThrowingTakeover implements LeasedOwner {
        private final LeasedOwner   delegate;
        private final AtomicBoolean fail;
        private final AtomicInteger calls;

        ThrowingTakeover(LeasedOwner delegate, AtomicBoolean fail, AtomicInteger calls) {
            this.delegate = delegate;
            this.fail = fail;
            this.calls = calls;
        }

        @Override
        public void onTakeover(Connection connection) throws SQLException {
            calls.incrementAndGet();
            if (fail.get()) {
                throw new IllegalStateException("injected takeover failure");
            }
            delegate.onTakeover(connection);
        }

        @Override
        public int pumpOnce(Connection connection) throws SQLException {
            return delegate.pumpOnce(connection);
        }

        @Override
        public boolean needsAttention() {
            return delegate.needsAttention();
        }

        @Override
        public void flushOnStop(Connection connection) throws SQLException {
            delegate.flushOnStop(connection);
        }

        @Override
        public long nextAttentionNanos() {
            return delegate.nextAttentionNanos();
        }

        @Override
        public ShardWakeup wakeup() {
            return delegate.wakeup();
        }

        @Override
        public int shard() {
            return delegate.shard();
        }

        @Override
        public short queueId() {
            return delegate.queueId();
        }

        @Override
        public ShardOwnerMetrics ownerMetrics() {
            return delegate.ownerMetrics();
        }

        @Override
        public long fence() {
            return delegate.fence();
        }

        @Override
        public String lane() {
            return delegate.lane();
        }

        @Override
        public LeaseRow leaseRow() {
            return delegate.leaseRow();
        }

        @Override
        public void endKeepingLeaseRow() {
            delegate.endKeepingLeaseRow();
        }

        @Override
        public void beginDraining() {
            delegate.beginDraining();
        }

        @Override
        public int inFlight() {
            return delegate.inFlight();
        }

        @Override
        public void onLeaseEnded(LeaseEnd reason) {
            delegate.onLeaseEnded(reason);
        }

        @Override
        public boolean deliveryPermitted() {
            return delegate.deliveryPermitted();
        }
    }

    /**
     * Asserted, not assumed: a pump that does not exit when {@code running} is cleared would otherwise let the test pass
     * and leak its thread, and the pooled connection it holds, into the tests that follow.
     */
    private static void stopPump(AtomicBoolean running, ShardPump pump, Thread thread) throws InterruptedException {
        running.set(false);
        pump.wakeup().signal();
        assertThat(thread.join(Duration.ofSeconds(10))).as("the pump stops when running is cleared").isTrue();
    }

    /**
     * Seq 1 is one charge short of the limit and seq 2 has never been charged. The first {@code onTakeover} charges
     * both and then fails to park seq 1; the second must park it without charging seq 2 again.
     */
    private void assertTakeoverRetriedAfterAFailedDeadLetterPass(LeasedOwner owner, String table, MessageId.Lane lane) throws Exception {
        failDeadLetterInserts(true);
        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> owner.onTakeover(connection))
                    .as("the injected failure must reach the dead-letter move")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("injected dead-letter failure");
        }
        assertThat(attempts(table, 1)).as("charged, and still live").isEqualTo(MAX_ATTEMPTS);
        assertThat(attempts(table, 2)).isEqualTo(1);
        assertThat(deadLetterError(lane, 1)).isEmpty();

        // The pump reconnects and calls onTakeover again
        failDeadLetterInserts(false);
        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }
        assertThat(attempts(table, 2)).as("the window carries exactly one charge").isEqualTo(1);
        assertThat(attempts(table, 1)).as("moved out of the live lane").isZero();
        assertThat(deadLetterError(lane, 1)).contains(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS);
        assertThat(ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS).doesNotContain("without a failure ever being recorded");
        assertThat(deadLettered).containsExactly(new DeadLettered(new MessageId(lane, SHARD, 1), MAX_ATTEMPTS,
                                                                  ShardOwnedStorage.EXHAUSTED_BY_TAKEOVERS));
    }

    /**
     * Copied under the appender's own lock ({@code AppenderBase.doAppend} is synchronized): the pump thread appends
     * while the test reads.
     */
    private List<ILoggingEvent> pumpErrors() {
        return pumpLogged().stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    /**
     * {@code TakeoverStalls.failed} reporting a stall's first failure: at ERROR when the subscription's window is open,
     * else at DEBUG. Told apart from a repeat by the message pattern, not the level.
     */
    private List<ILoggingEvent> firstTakeoverFailureReports() {
        return pumpLogged().stream()
                           .filter(event -> event.getMessage().contains("the takeover is retried every")
                                   || event.getMessage().contains("now awaiting their takeover"))
                           .toList();
    }

    /**
     * {@code TakeoverStalls.completed}'s INFO.
     */
    private List<ILoggingEvent> takeoverRecoveries() {
        return pumpLogged().stream()
                           .filter(event -> event.getLevel() == Level.INFO && event.getMessage().contains("completed its takeover"))
                           .toList();
    }

    private List<ILoggingEvent> pumpLogged() {
        synchronized (pumpLog) {
            return new ArrayList<>(pumpLog.list);
        }
    }

    /**
     * Terminate every backend of this database connected under {@code applicationName}.
     *
     * @return how many were signalled
     */
    private int terminateBackends(String applicationName) throws SQLException {
        return queryForInt(dataSource, "Terminating the backends of " + applicationName,
                           "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity"
                           + " WHERE datname = current_database() AND application_name = ? AND pid <> pg_backend_pid()",
                           applicationName);
    }

    private ShardOwnerMetrics metrics() {
        return new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                deadLettered.add(new DeadLettered(id, attempts, cause.getMessage()));
            }

            @Override
            public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                takeoverStallTransitions.add(stalled);
            }
        });
    }

    private record DeadLettered(MessageId id, int attempts, String cause) {
    }

    /**
     * A trigger rather than a broken statement: the owner's own move has to be the thing that fails.
     */
    private void failDeadLetterInserts(boolean fail) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            if (fail) {
                // A sequence, because nextval survives the rollback the refusal causes: it counts the refused inserts
                statement.execute("DROP SEQUENCE IF EXISTS dead_letter_insert_refusals");
                statement.execute("CREATE SEQUENCE dead_letter_insert_refusals");
                statement.execute("""
                                  CREATE OR REPLACE FUNCTION fail_dead_letter_insert() RETURNS trigger AS $$
                                  BEGIN
                                      PERFORM nextval('dead_letter_insert_refusals');
                                      RAISE EXCEPTION 'injected dead-letter failure';
                                  END
                                  $$ LANGUAGE plpgsql""");
                statement.execute("CREATE TRIGGER fail_dead_letter_insert BEFORE INSERT ON " + DLQ_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_dead_letter_insert()");
            } else {
                statement.execute("DROP TRIGGER fail_dead_letter_insert ON " + DLQ_TABLE);
            }
        }
    }

    private long deadLetterInsertRefusals() throws SQLException {
        return queryForLong(dataSource, "Reading the dead_letter_insert_refusals sequence",
                            "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM dead_letter_insert_refusals");
    }

    private void insertOrdered(String key, long keyOrder, long seq, int attempts) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + ORDERED_TABLE + " (queue_id, shard, msg_key, key_order, seq, payload, payload_type, attempts)"
                             + " VALUES (?, ?, ?, ?, ?, '\\x00'::bytea, 1, ?)")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setString(3, key);
            statement.setLong(4, keyOrder);
            statement.setLong(5, seq);
            statement.setInt(6, attempts);
            statement.executeUpdate();
        }
    }

    private void insertDeadLetter(String key, long keyOrder, long seq) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + DLQ_TABLE + " (queue_id, shard, source_lane, msg_key, key_order, seq, payload, payload_type, attempts, last_error)"
                             + " VALUES (?, ?, 'ordered', ?, ?, ?, '\\x00'::bytea, 1, 1, 'failed')")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setString(3, key);
            statement.setLong(4, keyOrder);
            statement.setLong(5, seq);
            statement.executeUpdate();
        }
    }

    /**
     * Addressed by {@code (shard, seq, source_lane)}, like every dead letter: {@code blocked_by_key_order} is an
     * ordered-lane column, and an unordered dead letter can share an ordered one's {@code (shard, seq)}.
     *
     * @return the ordered dead letter's {@code blocked_by_key_order}: empty when there is no ordered dead letter for
     * {@code seq}, or it was dead-lettered by a failure of its own rather than parked behind another
     */
    private Optional<Long> blockedByKeyOrder(long seq) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT blocked_by_key_order FROM " + DLQ_TABLE
                                                                 + " WHERE queue_id = ? AND shard = ? AND seq = ? AND source_lane = 'ordered'")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setLong(3, seq);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.ofNullable(resultSet.getObject(1, Long.class)) : Optional.empty();
            }
        }
    }

    /**
     * @param sessionLease the row lease of a live pull session holding the row, or null for none
     */
    private void insertUnordered(long seq, int attempts, Long sessionLease) throws SQLException {
        insertUnordered(SHARD, seq, attempts, sessionLease);
    }

    private void insertUnordered(int shard, long seq, int attempts, Long sessionLease) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + UNORDERED_TABLE + " (queue_id, shard, seq, payload, payload_type, attempts, lease, lease_until)"
                             + " VALUES (?, ?, ?, '\\x00'::bytea, 1, ?, ?, CASE WHEN ?::bigint IS NULL THEN NULL ELSE now() + interval '1 hour' END)")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, shard);
            statement.setLong(3, seq);
            statement.setInt(4, attempts);
            statement.setObject(5, sessionLease);
            statement.setObject(6, sessionLease);
            statement.executeUpdate();
        }
    }

    private int attempts(String table, long seq) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return storage.storedAttempts(connection, table, SHARD, seq);
        }
    }

    /**
     * Addressed by {@code (shard, seq, source_lane)}, like every dead letter: unordered seqs are per shard and ordered
     * seqs per queue, so one {@code (shard, seq)} can be parked in both lanes.
     */
    private Optional<String> deadLetterError(MessageId.Lane lane, long seq) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT last_error FROM " + DLQ_TABLE
                                                                 + " WHERE queue_id = ? AND shard = ? AND seq = ? AND source_lane = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, SHARD);
            statement.setLong(3, seq);
            statement.setString(4, lane == MessageId.Lane.ORDERED ? "ordered" : "unordered");
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getString(1)) : Optional.empty();
            }
        }
    }
}
