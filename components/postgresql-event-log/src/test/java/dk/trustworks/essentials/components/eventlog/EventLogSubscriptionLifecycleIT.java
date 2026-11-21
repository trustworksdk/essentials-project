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

import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * {@link Subscription#stop()} against a handler that ignores interrupts: stop() returns after its join timeout, and
 * {@link Subscription#isRunning()} keeps saying the delivery thread is alive until it ends - so the admin's
 * {@code deleteRetiredInstance} and a re-subscribe on the same key do not act on a subscription that still delivers.
 */
class EventLogSubscriptionLifecycleIT extends AbstractSubscriptionIT {
    @Test
    void is_running_stays_true_while_a_handler_that_ignores_interrupts_outlives_stop() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        PeriodicHandler stubborn = event -> {
            entered.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    // a handler that swallows the interrupt
                }
            }
        };
        var subscription = subscribe("stubborn", stubborn, TestSeams.NONE, 1);
        subscription.stopJoinTimeout = Duration.ofMillis(100);
        commitAppend("a", 1);
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

        var started = System.nanoTime();
        subscription.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(subscription.isRunning()).as("the handler still runs").isTrue();
        release.countDown();
        await().atMost(WAIT).until(() -> !subscription.isRunning());
    }

    @Test
    void a_non_exclusive_re_subscribe_is_refused_while_the_old_delivery_thread_outlives_stop() throws Exception {
        // Only exclusive subscribes used to check isRunning(): a non-exclusive re-subscribe of the same key started a
        // second delivery thread on the same progress row next to the one still running - duplicate deliveries
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        PeriodicHandler stubborn = event -> {
            entered.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    // a handler that swallows the interrupt
                }
            }
        };
        var subscription = subscribe("stubborn", stubborn, TestSeams.NONE, 1);
        subscription.stopJoinTimeout = Duration.ofMillis(100);
        commitAppend("a", 1);
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        subscription.stop();
        assertThat(subscription.isRunning()).isTrue();

        assertThatThrownBy(() -> subscribe("stubborn", event -> { }, TestSeams.NONE, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");

        release.countDown();
        await().atMost(WAIT).until(() -> !subscription.isRunning());
        assertThat(subscribe("stubborn", event -> { }, TestSeams.NONE, 1).isRunning()).as("once the old thread has ended").isTrue();
    }

    @Test
    void event_log_stop_signals_every_stuck_subscription_at_once_not_one_join_timeout_after_another() throws Exception {
        // EventLog.stop() used to stop subscriptions one by one, so each stuck handler added its full join timeout before
        // the next one was even interrupted
        var entered     = new CountDownLatch(3);
        var interrupted = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var release     = new CountDownLatch(1);
        PeriodicHandler stubborn = event -> {
            entered.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    // a handler that swallows the interrupt
                    interrupted.add(System.nanoTime());
                    if (interrupted.size() == 3) {
                        release.countDown();
                    }
                }
            }
        };
        var stuck = java.util.List.of(subscribe("stubborn-1", stubborn, TestSeams.NONE, 1),
                                      subscribe("stubborn-2", stubborn, TestSeams.NONE, 1),
                                      subscribe("stubborn-3", stubborn, TestSeams.NONE, 1));
        stuck.forEach(subscription -> subscription.stopJoinTimeout = Duration.ofSeconds(1));
        commitAppend("a", 1);
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

        eventLog.stop();

        assertThat(interrupted).hasSize(3);
        var spread = Duration.ofNanos(java.util.Collections.max(interrupted) - java.util.Collections.min(interrupted));
        assertThat(spread).as("one after another they were interrupted a join timeout (1 s) apart").isLessThan(Duration.ofMillis(500));
        await().atMost(WAIT).until(() -> stuck.stream().noneMatch(Subscription::isRunning));
    }

    @Test
    void a_parked_request_made_while_the_subscription_stops_completes() throws Exception {
        var drained = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeFinalSave(String subscriber) {
                drained.countDown();
                // stop() interrupts the delivery thread: keep it alive here regardless, as a slow final save would
                var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (release.getCount() > 0 && System.nanoTime() < deadline) {
                    try {
                        release.await(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // keep waiting
                    }
                }
            }
        };
        PeriodicHandler handler = event -> {
        };
        var subscription = subscribe("stopping", handler, new TestSeams(java.util.Set.of(), hooks, null), 1);
        var stopper = Executors.newSingleThreadExecutor();
        try {
            stopper.submit(subscription::stop);
            assertThat(drained.await(10, TimeUnit.SECONDS)).isTrue();

            // The delivery thread is alive (final save pending) but drains no more: before, this request never completed
            var request = subscription.retryParked(1);

            await().atMost(Duration.ofSeconds(5)).until(request::isDone);
            assertThat(request).isCompletedExceptionally();
        } finally {
            release.countDown();
            stopper.shutdown();
        }
    }
}
