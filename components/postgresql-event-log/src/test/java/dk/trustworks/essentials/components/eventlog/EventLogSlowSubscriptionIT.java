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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * I9, plan §16: <i>"A slow subscription never delays another"</i> (phase 3 §5.1).
 * <p>
 * Three subscriptions on one type, 100 ms backstop, a writer at 50 events/s (3 s by default, the plan's 20 s with the
 * opt-in {@code -Dbenchmark.run=true}); one handler blocks on a latch from its second event on. Asserts (a) the other
 * two receive every event, in per-aggregate order, while the blocked one is still blocked and after its inbox
 * overflowed; (b) their commit-to-handle p99 is within one backstop interval of the same run without the blocked
 * subscription; (c) once released, the blocked one receives every event exactly once, in per-aggregate order (F5).
 * The inbox holds 50 events, so the blocked one overflows within the short run. Must-fail variant:
 * {@link WatermarkMutant#BACK_PRESSURE} - the loop waits on the full inbox, so the others stop receiving.
 */
class EventLogSlowSubscriptionIT extends AbstractSubscriptionIT {
    private static final Duration BACKSTOP = Duration.ofMillis(100);
    private static final Duration WRITE    = Duration.ofSeconds(Boolean.getBoolean("benchmark.run") ? 20 : 3);
    /** How long the others may take to receive the last event once the writer stopped; about 100 ms when correct */
    private static final Duration SETTLE   = Duration.ofSeconds(5);

    @BeforeEach
    void slowSettings() throws Exception {
        restartEventLog(dataSource, FAST.withBackstopInterval(BACKSTOP)
                                        .withProbeBackoff(BACKSTOP, BACKSTOP)
                                        .withTailPageSize(50)
                                        .withSubscriptionBufferSize(50));
    }

    @Test
    void a_blocked_subscription_never_delays_the_others() throws Exception {
        var baseline = run("baseline", false);
        assertThat(baseline.othersReceivedEverything).isTrue();
        var run = run("run", true);
        assertThat(run.othersReceivedEverything).as("(a) the others receive every event while the slow one is blocked").isTrue();
        assertThat(run.slowOverflowed).as("(a) after its inbox overflowed").isTrue();
        assertThat(run.othersViolations).isEmpty();
        assertThat(run.p99Nanos).as("(b) p99 %d ms against the baseline's %d ms", run.p99Nanos / 1_000_000, baseline.p99Nanos / 1_000_000)
                                .isLessThanOrEqualTo(baseline.p99Nanos + BACKSTOP.toNanos());
        assertThat(run.slowDuplicates).as("(c) exactly once").isZero();
        assertThat(run.slowMissing).as("(c) every event").isEmpty();
        assertThat(run.slowViolations).as("(c) in per-aggregate order").isEmpty();
    }

    @Test
    void must_fail_a_loop_that_waits_on_a_full_inbox_stops_the_others() throws Exception {
        var run = run("run", true, WatermarkMutant.BACK_PRESSURE);
        assertThat(run.othersReceivedEverything && run.slowOverflowed).isFalse();
    }

    private record Result(boolean othersReceivedEverything, boolean slowOverflowed, List<String> othersViolations, long p99Nanos, long slowDuplicates,
                          Set<Long> slowMissing, List<String> slowViolations) {
    }

    /** Records the first handling time of every global order */
    private static final class Timed implements Subscription.Handler {
        final Recorder                     recorder = new Recorder();
        final ConcurrentHashMap<Long, Long> handledAt = new ConcurrentHashMap<>();

        @Override
        public void handle(StoredEvent event) throws Exception {
            handledAt.putIfAbsent(event.globalOrder(), System.nanoTime());
            recorder.handle(event);
        }
    }

    /** Blocks on {@link #release} from its second event on */
    private static final class Blocking implements Subscription.Handler {
        final Recorder       recorder = new Recorder();
        final AtomicInteger  calls    = new AtomicInteger();
        final CountDownLatch blocked  = new CountDownLatch(1);
        final CountDownLatch release  = new CountDownLatch(1);

        @Override
        public void handle(StoredEvent event) throws Exception {
            if (calls.incrementAndGet() >= 2) {
                blocked.countDown();
                release.await();
            }
            recorder.handle(event);
        }
    }

    /**
     * @param mutants on the type's first subscription, whose seams the shared reader uses
     */
    private Result run(String name, boolean withBlocked, WatermarkMutant... mutants) throws Exception {
        var startFrom = sequenceLastValue(tables) + 1;
        var fast1     = new Timed();
        var fast2     = new Timed();
        var slow      = new Blocking();
        var subs      = new ArrayList<Subscription>();
        subs.add(subscribe(name + "-fast1", fast1, new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null), startFrom));
        subs.add(subscribe(name + "-fast2", fast2, TestSeams.NONE, startFrom));
        Subscription slowSubscription = null;
        if (withBlocked) {
            slowSubscription = subscribe(name + "-slow", slow, TestSeams.NONE, startFrom);
            subs.add(slowSubscription);
        }
        awaitTrue(() -> subs.stream().allMatch(Subscription::isIdle));

        var committedAt = write();
        var committed   = committedAt.keySet();
        var others      = List.of(fast1, fast2);
        var settled     = System.nanoTime() + SETTLE.toNanos();
        var received    = false;
        while (System.nanoTime() < settled && !received) {
            received = others.stream().allMatch(t -> t.recorder.deliveredOrders().containsAll(committed));
            Thread.sleep(10);
        }
        var stillBlocked = !withBlocked || (slow.release.getCount() == 1 && slow.calls.get() == 2);
        var current      = slowSubscription == null ? null : slowSubscription.inbox();
        var overflowed   = !withBlocked || current != null && current.closeReason() == Inbox.CloseReason.OVERFLOW;
        var violations   = new ArrayList<String>();
        others.forEach(t -> violations.addAll(perAggregateOrderViolations(t.recorder.delivered)));

        var latencies = new ArrayList<Long>();
        for (var t : others) {
            committedAt.forEach((g, at) -> {
                var handledAt = t.handledAt.get(g);
                if (handledAt != null) {
                    latencies.add(handledAt - at);
                }
            });
        }
        Collections.sort(latencies);
        var p99 = latencies.isEmpty() ? Long.MAX_VALUE : latencies.get((int) Math.ceil(latencies.size() * 0.99) - 1);

        var duplicates = 0L;
        var missing    = new TreeSet<Long>();
        var slowOrder  = List.<String>of();
        slow.release.countDown();
        if (withBlocked && received && stillBlocked) {
            var tail = commitAppend("tail", 1);
            awaitDelivered(slow.recorder, tail);
            missing.addAll(committed);
            missing.removeAll(slow.recorder.deliveredOrders());
            duplicates = slow.recorder.delivered.size() - slow.recorder.deliveredOrders().size();
            slowOrder = perAggregateOrderViolations(slow.recorder.delivered);
        }
        subs.forEach(Subscription::stop);
        return new Result(received && stillBlocked, overflowed, violations, p99, duplicates, missing, slowOrder);
    }

    /**
     * Commits one event every 20 ms to one of five aggregates
     *
     * @return each committed global order with its commit time
     */
    private Map<Long, Long> write() throws Exception {
        var committedAt = new TreeMap<Long, Long>();
        var interval    = Duration.ofMillis(20).toNanos();
        var start       = System.nanoTime();
        var end         = start + WRITE.toNanos();
        var next        = start;
        var i           = 0;
        while (next < end) {
            var g = commitAppend("agg-" + (i++ % 5), 1);
            committedAt.put(g, System.nanoTime());
            next += interval;
            var wait = next - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(Duration.ofNanos(wait));
            }
        }
        return committedAt;
    }
}
