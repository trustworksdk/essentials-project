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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-JVM takeovers with in-JVM event logs: each node is an {@link EventLog} on its own
 * {@code DataSource} running one periodic {@code FRESH} and one in-transaction {@code CONTIGUOUS} exclusive subscription;
 * a writer on a third event log appends throughout. The forked-JVM family is {@code EventLogForkedTakeoverIT}.
 */
class EventLogTakeoverIT extends AbstractLeaseIT {
    static final String PERIODIC = "periodic";
    static final String PAGES    = "pages";
    static final String VIEW     = "el_test_view";

    private final Set<Long>        periodicDelivered = new ConcurrentSkipListSet<>();
    private final AtomicBoolean    writing           = new AtomicBoolean(true);
    private final AtomicLong       lastWritten       = new AtomicLong();
    private Thread                 writer;

    /** One node: its event log and its two exclusive subscriptions */
    final class Node {
        final EventLog         log;
        final String           name;
        final AtomicLong       firstHandledNanos = new AtomicLong();
        final List<Long>       handled           = new CopyOnWriteArrayList<>();
        Subscription           periodic;
        Subscription           pages;

        Node(String name) throws SQLException {
            this(name, LEASE);
        }

        Node(String name, EventLogSettings settings) throws SQLException {
            this.name = name;
            this.log = eventLog(name, settings);
        }

        Node start() throws SQLException {
            periodic = EventLogTakeoverIT.this.periodic(log, PERIODIC, event -> {
                firstHandledNanos.compareAndSet(0, System.nanoTime());
                handled.add(event.globalOrder());
                periodicDelivered.add(event.globalOrder());
            }, TestSeams.NONE);
            pages = inTransaction(log, PAGES, ConsistencyMode.CONTIGUOUS, viewWriter(VIEW, name), TestSeams.NONE);
            return this;
        }

        boolean owns() {
            return periodic.lease().state() == Lease.State.OWNED && pages.lease().state() == Lease.State.OWNED;
        }
    }

    @BeforeEach
    void startWriter() throws SQLException {
        var writerLog = eventLog("writer");
        createView(VIEW);
        installProgressLog();
        writer = Thread.ofVirtual().start(() -> {
            while (writing.get()) {
                try {
                    var orders = append(writerLog, "order-" + (lastWritten.get() % 7), 1);
                    lastWritten.set(orders.getLast());
                    Thread.sleep(20);
                } catch (Exception e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stopWriter() throws InterruptedException {
        writing.set(false);
        writer.join();
    }

    /** Stops the writer and waits until the owner has applied everything: the view equals the reference fold, each event once */
    private void assertFoldEqual(Node owner) throws Exception {
        writing.set(false);
        writer.join();
        var last = lastWritten.get();
        awaitTrue(() -> {
            try {
                return queryLong("SELECT count(*) FROM " + VIEW + " WHERE writer = '" + owner.name + "'") > 0 && queryLong("SELECT coalesce(max(global_order), 0) FROM " + VIEW) >= last;
            } catch (SQLException e) {
                return false;
            }
        });
        assertThat(viewDefects(VIEW, last)).as("view rows duplicated or missing").isZero();
        awaitTrue(() -> periodicDelivered.size() >= last);
        assertThat(periodicDelivered).as("every event reached the periodic subscription at least once").hasSize((int) last);
    }

    private void awaitOwns(Node node) {
        awaitTrue(node::owns);
    }

    @Test
    void a_crashed_owner_is_taken_over_within_a_lease_and_a_claim_interval() throws Exception {
        var a = new Node("node-a").start();
        awaitOwns(a);
        var b = new Node("node-b").start();
        awaitTrue(() -> a.handled.size() >= 20);

        // A lost JVM: no release, no further write
        var crashedAt = System.nanoTime();
        a.periodic.crash();
        a.pages.crash();
        awaitOwns(b);
        awaitTrue(() -> b.firstHandledNanos.get() != 0);
        var takeover = Duration.ofNanos(b.firstHandledNanos.get() - crashedAt);
        assertThat(takeover).as("crash to the standby's first handled event")
                            .isLessThanOrEqualTo(LEASE.leaseDuration().plus(LEASE.leaseRenewalInterval()).plusSeconds(1));
        assertFoldEqual(b);
    }

    /**
     * A lease long enough that an expiry could never pass for a release: with {@link #LEASE}'s 2 s, an expiry hands over
     * 1.5 s after the stop at the earliest, the same as any bound tight enough to tell the two apart, and a loaded build
     * missed it (1.73 s)
     */
    static final EventLogSettings LONG_LEASE = LEASE.withLease(Duration.ofSeconds(10), LEASE.leaseRenewalInterval(), LEASE.leaseLocalStop());

    @Test
    void a_graceful_stop_hands_over_within_a_claim_interval_without_replay() throws Exception {
        var a = new Node("node-a", LONG_LEASE).start();
        awaitOwns(a);
        var b = new Node("node-b", LONG_LEASE).start();
        awaitTrue(() -> a.handled.size() >= 20);

        var stoppedAt = System.nanoTime();
        a.periodic.stop();
        a.pages.stop();
        var lastOfA = a.handled.getLast();
        awaitOwns(b);
        awaitTrue(() -> b.firstHandledNanos.get() != 0);
        // An expiry would take at least the lease less a renewal interval (9.5 s); a release takes a claim interval plus the start
        assertThat(Duration.ofNanos(b.firstHandledNanos.get() - stoppedAt)).as("stop to the standby's first handled event")
                                                                         .isLessThanOrEqualTo(LEASE.leaseRenewalInterval().plusSeconds(3));
        // A stop that lands while the handler runs leaves that one event unhandled (it may have been cut short), so it comes again
        assertThat(b.handled.getFirst()).as("the release saved the resume point: nothing but the interrupted event replayed").isGreaterThanOrEqualTo(lastOfA);
        assertFoldEqual(b);
    }

    @Test
    void a_rolling_chain_of_owners_draws_strictly_increasing_fences() throws Exception {
        var a = new Node("node-a").start();
        awaitOwns(a);
        var b = new Node("node-b").start();
        var c = new Node("node-c").start();
        var current = a;
        var chain   = List.of(b, c);
        for (var next : chain) {
            var owner = current;
            awaitTrue(() -> owner.handled.size() >= 10);
            owner.periodic.stop();
            owner.pages.stop();
            awaitOwns(next);
            current = next;
        }
        // ... and back to A: a new incarnation on A's event log
        var again = a.start();
        var last  = current;
        awaitTrue(() -> last.handled.size() >= 10);
        last.periodic.stop();
        last.pages.stop();
        awaitOwns(again);

        for (var subscriber : List.of(PERIODIC, PAGES)) {
            var fences = progressLog(subscriber).stream().map(LoggedVersion::fence).filter(Objects::nonNull).distinct().toList();
            assertThat(fences).as("%s's fences in commit order", subscriber).hasSize(4).isSorted();
        }
        assertFoldEqual(a);
    }

    // ---- the automatic retry of a parked aggregate across a takeover ----

    /** @return how long after the stored {@code next_retry_at} the new owner's first automatic retry started, in ms by the database's clock */
    private long takeoverRetryLateness(WatermarkMutant... mutants) throws Exception {
        var settings = LEASE.withParkedRetry(Duration.ofSeconds(3), Duration.ofSeconds(3));
        var poisoned = new AtomicBoolean(true);
        PageHandler handler = PageHandler.perEvent((event, tx) -> {
            if (poisoned.get() && event.aggregateId().equals("poison")) {
                throw new IllegalStateException("poison fails");
            }
        });
        var a     = eventLog("node-a", settings);
        var first = inTransaction(a, "parker", ConsistencyMode.FRESH, handler, TestSeams.NONE);
        awaitOwned(first);
        var head = append(a, "poison", 1).getFirst();
        var sql  = "SELECT next_retry_at FROM " + EventLogSchema.PARKED_EVENTS_TABLE + " WHERE subscriber = 'parker' AND global_order = " + head;
        awaitTrue(() -> {
            try {
                return queryLong("SELECT count(*) FROM " + EventLogSchema.PARKED_EVENTS_TABLE + " WHERE subscriber = 'parker' AND next_retry_at IS NOT NULL") == 1;
            } catch (SQLException e) {
                return false;
            }
        });
        String stored;
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            stored = resultSet.getString(1);
        }
        first.stop();

        var lateness = new java.util.concurrent.CompletableFuture<Long>();
        var b = eventLog("node-b", settings);
        poisoned.set(false);
        var second = inTransaction(b, "parker", ConsistencyMode.FRESH, handler, seams(new TestSeams.Hooks() {
            @Override
            public void beforeParkedRetry(String subscriber, long parkedHead, boolean auto) throws Exception {
                if (auto && parkedHead == head && !lateness.isDone()) {
                    try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                         var resultSet = statement.executeQuery("SELECT next_retry_at, round(extract(epoch FROM clock_timestamp() - next_retry_at) * 1000) FROM " +
                                                                        EventLogSchema.PARKED_EVENTS_TABLE + " WHERE subscriber = 'parker' AND global_order = " + head)) {
                        resultSet.next();
                        if (!stored.equals(resultSet.getString(1))) {
                            lateness.completeExceptionally(new AssertionError("next_retry_at moved from " + stored + " to " + resultSet.getString(1)));
                        } else {
                            lateness.complete(resultSet.getLong(2));
                        }
                    }
                }
            }
        }, mutants));
        awaitOwned(second);
        var result = lateness.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        awaitTrue(() -> {
            try {
                return queryLong("SELECT count(*) FROM " + EventLogSchema.PARKED_EVENTS_TABLE + " WHERE subscriber = 'parker'") == 0;
            } catch (SQLException e) {
                return false;
            }
        });
        return result;
    }

    @Test
    void a_new_owner_continues_the_stored_retry_schedule_of_a_parked_aggregate() throws Exception {
        assertThat(takeoverRetryLateness()).as("ms after next_retry_at").isNotNegative();
    }

    @Test
    void must_fail_when_the_schedule_is_not_loaded_a_new_owner_retries_at_once() throws Exception {
        assertThat(takeoverRetryLateness(WatermarkMutant.PARKED_RETRY_SCHEDULE_NOT_LOADED)).as("ms after next_retry_at").isNegative();
    }
}

