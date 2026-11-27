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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import dk.trustworks.essentials.reactive.LocalEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify.NotifyEpochTest.awaitParked;
import static dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation.INSERT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2: {@link NotifyAwareEventStorePollingOptimizer} with {@link NotifyPollingSettings#interruptibleWait()} - the wait
 * between polls ends on a NOTIFY. The {@link NotifyEpochSource} subscribes synchronously, so a publish has advanced the
 * epoch when it returns. Waits use delays far longer than a test may run, so a missed wake-up fails instead of passing slowly.
 */
class NotifyAwareInterruptibleWaitTest {
    private static final String TABLE    = "orders_events";
    private static final long   LONG_MS  = 60_000;

    private final LocalEventBus     bus         = LocalEventBus.builder().busName("NotifyAwareInterruptibleWaitTest").parallelThreads(1).build();
    private final NotifyEpochSource epochSource = new NotifyEpochSource(bus);

    @AfterEach
    void tearDown() {
        epochSource.close();
    }

    @Test
    void a_notify_while_waiting_ends_the_wait() throws Exception {
        var optimizer = optimizer(50, true);
        optimizer.eventStorePollingStarting();
        optimizer.eventStorePollingReturnedNoEvents();
        var waiter = startWait(optimizer, LONG_MS);
        awaitParked(waiter.thread());

        notifyTable(TABLE);

        assertThat(waiter.result()).succeedsWithin(10, TimeUnit.SECONDS);
    }

    @Test
    void a_notify_that_lands_while_the_poll_runs_ends_the_next_wait_at_once() throws Exception {
        var optimizer = optimizer(50, true);
        optimizer.eventStorePollingStarting();
        notifyTable(TABLE); // after the epoch was read, before the empty poll returned
        optimizer.eventStorePollingReturnedNoEvents();

        assertThat(startWait(optimizer, LONG_MS).result()).succeedsWithin(10, TimeUnit.SECONDS);
    }

    @Test
    void a_notify_before_the_poll_started_was_covered_by_that_poll_and_does_not_end_the_wait() throws Exception {
        var optimizer = optimizer(50, true);
        notifyTable(TABLE);
        optimizer.eventStorePollingStarting();
        optimizer.eventStorePollingReturnedNoEvents();

        long start = System.nanoTime();
        optimizer.awaitNextPoll(100);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(100);
    }

    @Test
    void a_notify_for_another_table_does_not_end_the_wait() throws Exception {
        var optimizer = optimizer(50, true);
        optimizer.eventStorePollingStarting();
        notifyTable("products_events");

        long start = System.nanoTime();
        optimizer.awaitNextPoll(100);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(100);
    }

    @Test
    void early_wake_ups_are_coalesced_to_one_per_initial_delay() throws Exception {
        long initialDelayMs = 300;
        var  optimizer      = optimizer(initialDelayMs, true);
        optimizer.eventStorePollingStarting();
        notifyTable(TABLE);
        optimizer.awaitNextPoll(LONG_MS); // first early wake-up: at once
        long firstWakeUp = System.nanoTime();

        // The poll it led to was empty, and another NOTIFY arrives straight away (a burst)
        optimizer.eventStorePollingStarting();
        notifyTable(TABLE);
        assertThat(startWait(optimizer, LONG_MS).result()).succeedsWithin(10, TimeUnit.SECONDS);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstWakeUp)).isGreaterThanOrEqualTo(initialDelayMs);
    }

    @Test
    void coalescing_never_holds_a_wait_past_its_delay() throws Exception {
        var optimizer = optimizer(1_000_000, true);
        optimizer.eventStorePollingStarting();
        notifyTable(TABLE);
        optimizer.awaitNextPoll(LONG_MS);

        optimizer.eventStorePollingStarting();
        notifyTable(TABLE);
        // Without the cap the wait would be held for the rest of the 1 000 s initial delay
        assertThat(startWait(optimizer, 50).result()).succeedsWithin(10, TimeUnit.SECONDS);
    }

    @Test
    void the_delay_is_a_plain_backoff_ramp_reset_by_an_early_wake_up() throws Exception {
        var optimizer = optimizer(50, true);
        optimizer.eventStorePollingStarting();
        optimizer.eventStorePollingReturnedNoEvents();
        optimizer.eventStorePollingReturnedNoEvents();
        notifyTable(TABLE);
        assertThat(optimizer.currentDelayMs()).as("no Phase 1 epoch peek").isEqualTo(200);

        optimizer.awaitNextPoll(LONG_MS);
        assertThat(optimizer.currentDelayMs()).isEqualTo(50);
    }

    @Test
    void an_interrupt_ends_the_wait() throws Exception {
        var optimizer = optimizer(50, true);
        optimizer.eventStorePollingStarting();
        var waiter = startWait(optimizer, LONG_MS);
        awaitParked(waiter.thread());

        waiter.thread().interrupt();

        assertThat(waiter.result()).failsWithin(10, TimeUnit.SECONDS)
                                   .withThrowableOfType(ExecutionException.class)
                                   .withCauseInstanceOf(InterruptedException.class);
    }

    @Test
    void without_the_interruptible_wait_a_notify_does_not_end_the_wait() throws Exception {
        var optimizer = optimizer(50, false);
        optimizer.eventStorePollingStarting();
        optimizer.eventStorePollingReturnedNoEvents();
        var  waiter = startWait(optimizer, 200);
        long start  = System.nanoTime();
        awaitParked(waiter.thread());

        notifyTable(TABLE);

        assertThat(waiter.result()).succeedsWithin(10, TimeUnit.SECONDS);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(150);
        assertThat(optimizer.currentDelayMs()).as("Phase 1: the next wait is shortened instead").isZero();
    }

    @Test
    void the_four_argument_settings_constructor_keeps_the_phase_1_behaviour() {
        assertThat(new NotifyPollingSettings(true, Duration.ofMillis(50), Duration.ofSeconds(1), 2.0).interruptibleWait()).isFalse();
        assertThat(NotifyPollingSettings.defaults().interruptibleWait()).isFalse();
        assertThat(NotifyPollingSettings.defaults().withInterruptibleWait(true).interruptibleWait()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private NotifyAwareEventStorePollingOptimizer optimizer(long initialDelayMs, boolean interruptibleWait) {
        return new NotifyAwareEventStorePollingOptimizer(epochSource,
                                                         TABLE,
                                                         new NotifyPollingSettings(true, Duration.ofMillis(initialDelayMs), Duration.ofMillis(Math.max(initialDelayMs, LONG_MS)), 2.0, interruptibleWait));
    }

    private void notifyTable(String table) {
        bus.publish(new EventStreamTableChangeNotification(table, INSERT));
    }

    private record Wait(Thread thread, CompletableFuture<Void> result) {
    }

    private static Wait startWait(NotifyAwareEventStorePollingOptimizer optimizer, long delayMs) {
        var result = new CompletableFuture<Void>();
        var thread = Thread.ofPlatform().name("poll-waiter").start(() -> {
            try {
                optimizer.awaitNextPoll(delayMs);
                result.complete(null);
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return new Wait(thread, result);
    }
}
