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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/**
 * A takeover that keeps failing is retried {@link ShardPump#TAKEOVER_RETRY_NANOS} after each failure, about five times
 * a second, while its shard stays leased and unserved. That state must be counted where {@code ShardOwnedQueue.metrics()}
 * sees it, and logged at ERROR once a minute at most per subscription (one metrics object; one per queue unless a queue
 * is consumed twice in one process): not once per retry, and not once per shard.
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
        clock.set(1_000_000_000L + ErrorWindow.EVERY_NANOS);
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
        clock.set(1_000_000_000L + 2 * ErrorWindow.EVERY_NANOS);
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

    /**
     * What makes a takeover fail is rarely one shard's: a trigger or constraint refusing the dead-letter move is
     * database-wide, and an {@code onTakeover} bug throws for every owner. After a crash or rebalance one instance takes
     * over dozens of shards at once, often on more than one pump. Throttled per shard, that logged a stack-trace ERROR for
     * each of them, then one a minute for each. Throttled per subscription (on the shared metrics; here one per queue), it
     * is one ERROR a minute that names one shard and counts the others; {@code shardsAwaitingTakeover} stays exact.
     */
    @Test
    void shards_stalling_together_are_logged_at_error_once_a_minute_per_subscription_not_once_per_shard() {
        var metrics   = new ShardOwnerMetrics();
        var pumpZero  = new TakeoverStalls("pump-0", clock::get);
        var pumpOne   = new TakeoverStalls("pump-1", clock::get);
        var first     = new StubOwner((short) 4, 0, metrics, "ordered");
        var second    = new StubOwner((short) 4, 1, metrics, "ordered");
        var third     = new StubOwner((short) 4, 2, metrics);
        var start     = clock.get();

        pumpZero.failed(first, "threw during its takeover", new IllegalStateException("broken"));
        pumpZero.failed(second, "threw during its takeover", new IllegalStateException("broken"));
        pumpOne.failed(third, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(metrics.shardsAwaitingTakeover.get()).as("still exact, per shard").isEqualTo(3);
        assertThat(levels()).as("one ERROR for the subscription, the others at DEBUG").containsExactly(Level.ERROR, Level.DEBUG, Level.DEBUG);
        assertThat(logged.list.getFirst().getFormattedMessage())
                .contains("pump-0: queue 4, ordered shard 0 threw during its takeover")
                .contains("0 other shard(s) of this subscription are also awaiting their takeover")
                .as("the window the ERROR describes is the subscription's, not the queue's")
                .contains("at ERROR at most once a minute per subscription (the consumer reading this shard)");
        assertThat(logged.list.get(1).getFormattedMessage())
                .contains("pump-0: queue 4, ordered shard 1 threw during its takeover")
                .contains("java.lang.IllegalStateException: broken");
        assertThat(logged.list.get(1).getThrowableProxy()).as("no stack trace at DEBUG").isNull();

        // Retried for a minute: all DEBUG
        for (var retry = 0; retry < 5; retry++) {
            clock.addAndGet(ShardPump.TAKEOVER_RETRY_NANOS);
            pumpZero.failed(first, "threw during its takeover", new IllegalStateException("broken"));
            pumpZero.failed(second, "threw during its takeover", new IllegalStateException("broken"));
            pumpOne.failed(third, "threw during its takeover", new IllegalStateException("broken"));
        }
        assertThat(levels().stream().filter(Level.ERROR::equals).count()).isEqualTo(1);

        // A minute on, the first shard to retry gets the subscription's one ERROR, counting the other two
        clock.set(start + ErrorWindow.EVERY_NANOS);
        var before = logged.list.size();
        pumpOne.failed(third, "threw during its takeover", new IllegalStateException("broken"));
        pumpZero.failed(first, "threw during its takeover", new IllegalStateException("broken"));
        pumpZero.failed(second, "threw during its takeover", new IllegalStateException("broken"));
        var minuteLater = logged.list.subList(before, logged.list.size());
        assertThat(minuteLater.stream().map(ILoggingEvent::getLevel).toList()).containsExactly(Level.ERROR, Level.DEBUG, Level.DEBUG);
        assertThat(minuteLater.getFirst().getFormattedMessage())
                .contains("pump-1: queue 4, unordered shard 2 has still not completed its takeover: 7 failed attempt(s) in 60 s")
                .contains("2 other shard(s) of this subscription are also awaiting their takeover")
                .contains("Logged at most once a minute per subscription (the consumer reading this shard)");
        assertThat(minuteLater.getFirst().getThrowableProxy()).as("the periodic ERROR carries the stack trace").isNotNull();

        // Once no shard of the subscription is stalled, the next stall is reported in full again
        pumpZero.completed(first);
        pumpZero.completed(second);
        pumpOne.completed(third);
        assertThat(metrics.shardsAwaitingTakeover.get()).isZero();
        clock.addAndGet(ShardPump.TAKEOVER_RETRY_NANOS);
        pumpZero.failed(second, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(levels().getLast()).isEqualTo(Level.ERROR);
        assertThat(logged.list.getLast().getFormattedMessage()).contains("queue 4, ordered shard 1 threw during its takeover");
    }

    /**
     * A queue consumed twice in one process has two subscriptions, each with its own metrics object, so each has its own
     * ERROR window and its own count of stalled shards: a stall in one neither takes the other's window nor counts in
     * the other's ERROR. Pins what the docs say, "per subscription", rather than the "per queue" they used to say.
     */
    @Test
    void two_subscriptions_on_one_queue_have_their_own_error_window_and_count() {
        var firstSubscription  = new ShardOwnerMetrics();
        var secondSubscription = new ShardOwnerMetrics();
        var pump               = new TakeoverStalls("pump-0", clock::get);
        var a                  = new StubOwner((short) 4, 0, firstSubscription);
        var b                  = new StubOwner((short) 4, 1, firstSubscription);
        var c                  = new StubOwner((short) 4, 2, secondSubscription);

        pump.failed(a, "threw during its takeover", new IllegalStateException("broken"));
        pump.failed(b, "threw during its takeover", new IllegalStateException("broken"));
        pump.failed(c, "threw during its takeover", new IllegalStateException("broken"));

        assertThat(levels()).as("an ERROR window per subscription, though both are queue 4")
                            .containsExactly(Level.ERROR, Level.DEBUG, Level.ERROR);
        assertThat(logged.list.get(2).getFormattedMessage())
                .contains("queue 4, unordered shard 2 threw during its takeover")
                .as("the second subscription's count leaves out the first's stalled shards")
                .contains("0 other shard(s) of this subscription are also awaiting their takeover");
        assertThat(firstSubscription.shardsAwaitingTakeover.get()).isEqualTo(2);
        assertThat(secondSubscription.shardsAwaitingTakeover.get()).isEqualTo(1);
    }

    /**
     * The subscription's last stall ending reopens the window, but between its count reaching zero and the reopen, a pump on
     * another thread can count a new stall and take the window for that stall's ERROR. Reopening unconditionally then
     * undid that take, and the new stall's next retry, {@link ShardPump#TAKEOVER_RETRY_NANOS} later, logged a second
     * stack-trace ERROR within the minute. The interleaving is forced by running the other pump's failure from inside the
     * ending stall's decrement, which is exactly where the other thread can run.
     */
    @Test
    void a_stall_ending_does_not_reopen_the_window_another_pump_took_after_the_count_reached_zero() {
        var metrics = new ShardOwnerMetrics();
        var window  = metrics.nextTakeoverStallErrorNanos;
        var other   = new StubOwner((short) 4, 1, metrics);
        var pumpOne = new TakeoverStalls("pump-1", clock::get);
        // The ending stall had the subscription's ERROR more than a minute ago, so the window is open again by the clock
        assertThat(ErrorWindow.take(window, clock.get())).isTrue();
        clock.addAndGet(ErrorWindow.EVERY_NANOS + TimeUnit.SECONDS.toNanos(1));

        var endingStall = new AtomicInteger(1);
        TakeoverStalls.noLongerAwaiting(() -> {
            var left = endingStall.decrementAndGet();
            pumpOne.failed(other, "threw during its takeover", new IllegalStateException("broken"));
            return left;
        }, window);
        assertThat(levels()).as("the other pump's new stall took the window").containsExactly(Level.ERROR);

        clock.addAndGet(ShardPump.TAKEOVER_RETRY_NANOS);
        pumpOne.failed(other, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(levels()).as("its retry inside the minute is at DEBUG, not a second ERROR").containsExactly(Level.ERROR, Level.DEBUG);
    }

    /**
     * The other half: with nothing taking the window in between, the last stall ending does reopen it, so the next stall
     * is reported in full at once even inside the minute.
     */
    @Test
    void the_last_stall_ending_reopens_the_window_when_nobody_took_it_since() {
        var window = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        assertThat(ErrorWindow.take(window, clock.get())).isTrue();

        var awaiting = new AtomicInteger(2);
        TakeoverStalls.noLongerAwaiting(awaiting::decrementAndGet, window);
        assertThat(ErrorWindow.isOpen(window.get(), clock.get())).as("another shard still stalled: stays closed").isFalse();

        TakeoverStalls.noLongerAwaiting(awaiting::decrementAndGet, window);
        assertThat(window.get()).isEqualTo(ErrorWindow.NEVER_TAKEN);
    }

    @Test
    void the_logs_state_the_retry_interval_the_pump_actually_uses() {
        var stalls = new TakeoverStalls("pump-0", clock::get);

        stalls.failed(new StubOwner((short) 1, 0, new ShardOwnerMetrics()), "threw during its takeover", new IllegalStateException("broken"));

        assertThat(logged.list.getFirst().getFormattedMessage())
                .contains("the takeover is retried every " + TimeUnit.NANOSECONDS.toMillis(ShardPump.TAKEOVER_RETRY_NANOS) + " ms with no limit");
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
        assertThat(metrics.shardsAwaitingTakeover.get()).as("per subscription, across its shards").isEqualTo(3);

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

    /**
     * A non-critical {@code Error} (an {@code AssertionError} from a test double, say) is an observer bug like any
     * {@code RuntimeException}: caught only the latter, it escaped to the pump thread outside its per-owner guard, ended
     * the pump and left every shard it served leased and unserved. A critical one (a {@code LinkageError}, a
     * {@code VirtualMachineError}) still propagates, as it does from the owners' handler paths.
     */
    @Test
    void an_observer_throwing_a_non_critical_error_does_not_escape_to_the_pump_but_a_critical_one_does() {
        var throwing = new AtomicReference<Error>(new AssertionError("observer test double"));
        var metrics = new ShardOwnerMetrics(new QueueObserver() {
            @Override
            public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
                throw throwing.get();
            }
        });
        var owner  = new StubOwner((short) 2, 3, metrics);
        var stalls = new TakeoverStalls("pump-0", clock::get);

        stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken"));
        assertThat(metrics.shardsAwaitingTakeover.get()).isEqualTo(1);
        stalls.completed(owner);
        assertThat(metrics.shardsAwaitingTakeover.get()).isZero();
        stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken"));
        stalls.forget(owner);
        assertThat(metrics.shardsAwaitingTakeover.get()).isZero();

        var observerErrors = logged.list.stream()
                                        .filter(event -> event.getLevel() == Level.ERROR)
                                        .filter(event -> event.getFormattedMessage().contains("That is a bug in the observer"))
                                        .toList();
        assertThat(observerErrors).as("each throw is logged like a RuntimeException").hasSize(4);
        assertThat(observerErrors.getFirst().getThrowableProxy().getClassName()).isEqualTo(AssertionError.class.getName());

        var critical = new LinkageError("critical");
        throwing.set(critical);
        assertThatThrownBy(() -> stalls.failed(owner, "threw during its takeover", new IllegalStateException("broken")))
                .isSameAs(critical);
        assertThat(metrics.shardsAwaitingTakeover.get()).as("counted before the observer is told").isEqualTo(1);
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
        public LeaseRow leaseRow() {
            return LeaseRow.HELD;
        }

        @Override
        public void endKeepingLeaseRow() {
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
