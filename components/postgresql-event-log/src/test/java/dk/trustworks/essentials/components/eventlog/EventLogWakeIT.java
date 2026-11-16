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

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Wake discipline, design phase 6 §4.1, §5, §6.4: the loop takes a type's wake flag before it reads, so a wake during a
 * read causes another read; and wakes at the commit rate do not read the running set at the commit rate - the probe
 * clock allows one per woken probe wait. Backstop 30 s, so only a wake can be fast. Must-fail variants:
 * {@link WatermarkMutant#WAKE_CLEARED_AFTER_READ}, {@link WatermarkMutant#PROBE_ON_EVERY_WAKE}.
 */
class EventLogWakeIT extends AbstractSubscriptionIT {
    static final Duration BACKSTOP  = Duration.ofSeconds(30);
    static final Duration WOKEN     = Duration.ofMillis(200);
    static final Duration PROBE_MIN = Duration.ofMillis(50);
    static final Duration WRITING   = Duration.ofSeconds(2);

    @Test
    void a_wake_during_a_read_causes_another_read() throws Exception {
        assertThat(wakeDuringRead(null)).isTrue();
    }

    @Test
    void must_fail_the_wake_flag_is_cleared_after_the_read() throws Exception {
        assertThat(wakeDuringRead(WatermarkMutant.WAKE_CLEARED_AFTER_READ)).isFalse();
    }

    /**
     * The {@code afterFlushBeforeApply} hook holds the loop inside a woken read whose statement has run; a commit and its
     * {@code afterCommit} arrive meanwhile.
     *
     * @return whether the commit was delivered within {@link #WOKEN} of the hook's release
     */
    private boolean wakeDuringRead(WatermarkMutant mutant) throws Exception {
        restartEventLog(dataSource, FAST.withBackstopInterval(BACKSTOP));
        var armed   = new AtomicBoolean();
        var held    = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void afterFlushBeforeApply(List<String> aggregateTypes) throws Exception {
                if (armed.compareAndSet(true, false)) {
                    held.countDown();
                    release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                }
            }
        };
        var recorder = new Recorder();
        subscribe("sub", recorder, new TestSeams(mutant == null ? Set.of() : Set.of(mutant), hooks, null));
        awaitTrue(() -> reads.attaches.get() >= 1);
        var first = commitAppend("first", 1);
        eventLog.afterCommit(List.of(TYPE));
        awaitDelivered(recorder, first);

        armed.set(true);
        eventLog.afterCommit(List.of(TYPE));
        assertThat(held.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        var during = commitAppend("during", 1);
        eventLog.afterCommit(List.of(TYPE));
        var released = System.nanoTime();
        release.countDown();
        try {
            await().atMost(WOKEN).until(() -> recorder.deliveredOrder(during));
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            return false;
        }
        return System.nanoTime() - released <= WOKEN.toNanos();
    }

    @Test
    void wakes_at_the_commit_rate_do_not_probe_at_the_commit_rate() throws Exception {
        var bound = WRITING.toMillis() / PROBE_MIN.toMillis() + 5;
        assertThat(runningSetReadsUnderWakes(null)).isLessThanOrEqualTo(bound);
    }

    @Test
    void must_fail_every_woken_read_probes() throws Exception {
        var bound = WRITING.toMillis() / PROBE_MIN.toMillis() + 5;
        assertThat(runningSetReadsUnderWakes(WatermarkMutant.PROBE_ON_EVERY_WAKE)).isGreaterThan(bound);
    }

    /**
     * A writer holds a value for the whole run, so the reader always has a candidate and wants a running set; 8 writers
     * commit with {@code afterCommit} for {@link #WRITING}
     *
     * @return the running-set reads during the run
     */
    private long runningSetReadsUnderWakes(WatermarkMutant mutant) throws Exception {
        restartEventLog(dataSource, FAST.withBackstopInterval(BACKSTOP).withWokenProbeMin(PROBE_MIN));
        var recorder = new Recorder();
        subscribe("sub", recorder, new TestSeams(mutant == null ? Set.of() : Set.of(mutant), TestSeams.Hooks.NONE, null));
        awaitTrue(() -> reads.attaches.get() >= 1);
        var holder = openAppend("holder", 1);
        var above  = commitAppend("above", 1);
        eventLog.afterCommit(List.of(TYPE));
        awaitDelivered(recorder, above);
        awaitTrue(() -> !eventLog.reader(TYPE).state().candidates().isEmpty());

        var before   = reads.combinedReads.get();
        var deadline = System.nanoTime() + WRITING.toNanos();
        var commits  = new AtomicLong();
        try (var writers = Executors.newFixedThreadPool(8)) {
            for (var w = 0; w < 8; w++) {
                var id = "w" + w;
                writers.submit(() -> {
                    while (System.nanoTime() < deadline) {
                        commitAppend(id, 1);
                        eventLog.afterCommit(List.of(TYPE));
                        commits.incrementAndGet();
                        Thread.sleep(5);
                    }
                    return null;
                });
            }
        }
        var probes = reads.combinedReads.get() - before;
        holder.rollback();
        assertThat(commits.get()).as("commits").isGreaterThan(WRITING.toMillis() / PROBE_MIN.toMillis() * 4);
        return probes;
    }
}
