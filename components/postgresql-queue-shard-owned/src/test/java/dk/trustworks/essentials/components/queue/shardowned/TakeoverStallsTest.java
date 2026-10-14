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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A takeover that keeps failing is retried on every pump pass, about five times a second, while its shard stays
 * leased and unserved. That state must be counted where {@code ShardOwnedQueue.metrics()} sees it, and logged at ERROR
 * once a minute at most, not once per retry.
 */
class TakeoverStallsTest {

    private final AtomicLong                    clock = new AtomicLong(1_000_000_000L);
    private       ListAppender<ILoggingEvent>   logged;
    private       ch.qos.logback.classic.Logger pumpLogger;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        pumpLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardPump.class);
        pumpLogger.setLevel(Level.DEBUG);
        pumpLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        pumpLogger.detachAppender(logged);
        pumpLogger.setLevel(null);
    }

    @Test
    void a_failing_takeover_is_counted_as_awaiting_until_it_completes_and_logged_at_error_once_a_minute() {
        var metrics = new ShardOwnerMetrics();
        var owner   = new StubOwner((short) 3, 5, metrics);
        var stalls  = new TakeoverStalls("pump-0", clock::get);

        stalls.failed(owner, "failed its takeover on a healthy connection", new SQLException("refused"));
        assertThat(metrics.shardsAwaitingTakeover.get()).isEqualTo(1);
        assertThat(metrics.takeoverFailures.sum()).isEqualTo(1);
        assertThat(levels()).containsExactly(Level.ERROR);
        assertThat(logged.list.getFirst().getFormattedMessage())
                .contains("pump-0: queue 3, unordered shard 5 failed its takeover on a healthy connection")
                .contains("ShardOwnedQueue.metrics() reports it under shardsAwaitingTakeover")
                .contains("through MessageQueue it is reported to QueueObserver.takeoverStalled")
                .contains("essentials.queue.shards.awaiting.takeover exists only where a MicrometerQueueObserver is registered")
                .contains("the Spring starter registers one");
        assertThat(logged.list.getFirst().getThrowableProxy()).as("the first carries the stack trace").isNotNull();

        // Retried every 200 ms: counted, logged at DEBUG, and still one shard awaiting
        for (var retry = 0; retry < 5; retry++) {
            clock.addAndGet(200_000_000L);
            stalls.failed(owner, "failed its takeover on a healthy connection", new SQLException("refused"));
        }
        assertThat(metrics.takeoverFailures.sum()).isEqualTo(6);
        assertThat(metrics.shardsAwaitingTakeover.get()).isEqualTo(1);
        assertThat(levels()).containsExactly(Level.ERROR, Level.DEBUG, Level.DEBUG, Level.DEBUG, Level.DEBUG, Level.DEBUG);
        assertThat(logged.list.getLast().getThrowableProxy()).as("a repeat carries no stack trace").isNull();
        assertThat(logged.list.getLast().getFormattedMessage())
                .contains("(failed attempt 6): java.sql.SQLException: refused");

        // A minute after the first ERROR: one more, carrying the count
        clock.set(1_000_000_000L + TakeoverStalls.ERROR_EVERY_NANOS);
        stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(levels().getLast()).isEqualTo(Level.ERROR);
        assertThat(logged.list.getLast().getFormattedMessage())
                .contains("queue 3, unordered shard 5 has still not completed its takeover: 7 failed attempt(s) in 60 s")
                .contains("the latest threw during its takeover");
        assertThat(logged.list.getLast().getThrowableProxy()).as("the periodic ERROR carries the stack trace").isNotNull();

        // The limit re-arms: the next retry is back at DEBUG, not another ERROR on every pass
        clock.addAndGet(200_000_000L);
        stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(levels().getLast()).isEqualTo(Level.DEBUG);

        // ... until a minute after the second ERROR
        clock.set(1_000_000_000L + 2 * TakeoverStalls.ERROR_EVERY_NANOS);
        stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(levels().getLast()).isEqualTo(Level.ERROR);
        assertThat(logged.list.getLast().getFormattedMessage())
                .contains("has still not completed its takeover: 9 failed attempt(s) in 120 s");
        assertThat(levels().stream().filter(Level.ERROR::equals).count()).as("first + two a minute apart").isEqualTo(3);

        stalls.completed(owner);
        assertThat(metrics.shardsAwaitingTakeover.get()).isZero();
        assertThat(metrics.takeoverFailures.sum()).as("a running total").isEqualTo(9);
        assertThat(levels().getLast()).isEqualTo(Level.INFO);
        assertThat(logged.list.getLast().getFormattedMessage()).contains("completed its takeover after 9 failed attempt(s)");

        // A later failure is a new stall, reported in full again
        stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(levels().getLast()).isEqualTo(Level.ERROR);
        assertThat(metrics.shardsAwaitingTakeover.get()).isEqualTo(1);
    }

    @Test
    void a_completed_takeover_that_never_failed_changes_nothing() {
        var metrics = new ShardOwnerMetrics();
        var stalls  = new TakeoverStalls("pump-0", clock::get);

        stalls.completed(new StubOwner((short) 1, 0, metrics));
        stalls.forget(new StubOwner((short) 1, 0, metrics));

        assertThat(metrics.shardsAwaitingTakeover.get()).isZero();
        assertThat(metrics.takeoverFailures.sum()).isZero();
        assertThat(logged.list).isEmpty();
    }

    @Test
    void a_stalled_shard_stops_counting_when_its_lease_ends_or_the_pump_stops() {
        var metrics = new ShardOwnerMetrics();
        var first   = new StubOwner((short) 1, 0, metrics);
        var second  = new StubOwner((short) 1, 1, metrics);
        var third   = new StubOwner((short) 1, 2, metrics);
        var stalls  = new TakeoverStalls("pump-0", clock::get);
        stalls.failed(first, "threw during its takeover", new IllegalStateException("broken"));
        stalls.failed(second, "threw during its takeover", new IllegalStateException("broken"));
        stalls.failed(third, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(metrics.shardsAwaitingTakeover.get()).as("per queue, across its shards").isEqualTo(3);

        stalls.forget(first);
        assertThat(metrics.shardsAwaitingTakeover.get()).isEqualTo(2);

        stalls.forgetAll();
        assertThat(metrics.shardsAwaitingTakeover.get()).isZero();
        assertThat(metrics.takeoverFailures.sum()).isEqualTo(3);
    }

    /**
     * {@code ShardOwnerMetrics} is private behind a {@code MessageQueue}, so the observer is how the Spring starter and the
     * adapter see a stall. Told once when it starts and once when it ends, never per retry, and with the lane, since shard
     * numbers repeat across lanes.
     */
    @Test
    void the_observer_is_told_when_a_stall_starts_and_when_it_ends_but_not_on_every_retry() {
        var told    = new ArrayList<String>();
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                told.add(lane + "/" + shard + "=" + stalled);
            }
        });
        var completes = new StubOwner((short) 1, 4, metrics);
        var leaseEnds = new StubOwner((short) 1, 5, metrics);
        var pumpStops = new StubOwner((short) 1, 6, metrics, "ordered");
        var stalls    = new TakeoverStalls("pump-0", clock::get);

        for (var retry = 0; retry < 3; retry++) {
            stalls.failed(completes, "threw during its takeover", new IllegalStateException("broken"));
            stalls.failed(leaseEnds, "threw during its takeover", new IllegalStateException("broken"));
            stalls.failed(pumpStops, "threw during its takeover", new IllegalStateException("broken"));
        }
        assertThat(told).as("once per stall, not per retry").containsExactly("UNORDERED/4=true", "UNORDERED/5=true", "ORDERED/6=true");

        stalls.completed(completes);
        stalls.forget(leaseEnds);
        stalls.forgetAll();
        assertThat(told).as("every stall ends, whichever way").containsExactly("UNORDERED/4=true", "UNORDERED/5=true", "ORDERED/6=true",
                                                                                "UNORDERED/4=false", "UNORDERED/5=false", "ORDERED/6=false");
    }

    /**
     * Called on the pump's thread outside its per-owner guard: a throw here used to have nowhere to go but out of the pump,
     * ending the thread and every shard it serves. Caught and logged instead, and the counters stay right.
     */
    @Test
    void an_observer_that_throws_does_not_escape_to_the_pump_and_leaves_the_counters_right() {
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                throw new IllegalStateException("observer bug");
            }
        });
        var owner  = new StubOwner((short) 2, 3, metrics);
        var stalls = new TakeoverStalls("pump-0", clock::get);

        stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(metrics.shardsAwaitingTakeover.get()).isEqualTo(1);
        stalls.completed(owner);
        assertThat(metrics.shardsAwaitingTakeover.get()).isZero();
        stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken"));
        stalls.forgetAll();
        assertThat(metrics.shardsAwaitingTakeover.get()).isZero();

        var observerErrors = logged.list.stream()
                                        .filter(event -> event.getLevel() == Level.ERROR)
                                        .filter(event -> event.getFormattedMessage().contains("That is a bug in the observer"))
                                        .toList();
        assertThat(observerErrors).as("each throw is logged, naming the queue and shard").hasSize(4);
        assertThat(observerErrors.getFirst().getFormattedMessage()).contains("queue 2, unordered shard 3").contains("stalled=true");
        assertThat(observerErrors.getFirst().getThrowableProxy().getMessage()).isEqualTo("observer bug");
    }

    private List<Level> levels() {
        return logged.list.stream().map(ILoggingEvent::getLevel).toList();
    }

    private record StubOwner(short queueId, int shard, ShardOwnerMetrics ownerMetrics, String lane) implements LeasedOwner {
        StubOwner(short queueId, int shard, ShardOwnerMetrics ownerMetrics) {
            this(queueId, shard, ownerMetrics, "unordered");
        }

        @Override
        public int pumpOnce(Connection connection) {
            return 0;
        }

        @Override
        public boolean needsAttention() {
            return false;
        }

        @Override
        public void onTakeover(Connection connection) {
        }

        @Override
        public void flushOnStop(Connection connection) {
        }

        @Override
        public long parkDeadlineMillis() {
            return Long.MAX_VALUE;
        }

        @Override
        public long fence() {
            return 1;
        }

        @Override
        public boolean leaseHeld() {
            return true;
        }

        @Override
        public void beginDraining() {
        }

        @Override
        public int inFlight() {
            return 0;
        }

        @Override
        public void onLeaseEnded(LeaseEnd reason) {
        }
    }
}
