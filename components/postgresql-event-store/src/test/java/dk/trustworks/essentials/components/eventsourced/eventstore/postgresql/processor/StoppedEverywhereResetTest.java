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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueConsumers;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link StoppedEverywhereReset}: every refusal leaves nothing held and runs no reset; the checks run in order (reset
 * lock, reader locks, consumer count) before the reset; and a start's consumer registration and a reset exclude each
 * other, within one JVM too, where a fenced lock alone would not (it is owned per lock manager, not per caller).
 * <p>
 * Each test uses its own processor name: the JVM-local monitor is per lock name.
 */
class StoppedEverywhereResetTest {
    private static final AtomicInteger NAMES = new AtomicInteger();

    private final String            processorName = "Processor" + NAMES.incrementAndGet();
    private final LockName          readerLock    = LockName.of(processorName + ":lock");
    private final LockName          resetLock     = StoppedEverywhereReset.resetLockName(processorName);
    private final FencedLockManager lockManager   = mock(FencedLockManager.class);
    private final Map<LockName, FencedLock> granted = new ConcurrentHashMap<>();

    private void grant(LockName lockName) {
        var lock = mock(FencedLock.class);
        when(lock.getName()).thenReturn(lockName);
        granted.put(lockName, lock);
        when(lockManager.tryAcquireLock(lockName)).thenReturn(Optional.of(lock));
        when(lockManager.tryAcquireLock(eq(lockName), any(Duration.class))).thenReturn(Optional.of(lock));
    }

    private void refuse(LockName lockName) {
        when(lockManager.tryAcquireLock(lockName)).thenReturn(Optional.empty());
        when(lockManager.tryAcquireLock(eq(lockName), any(Duration.class))).thenReturn(Optional.empty());
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private void reset(boolean startedHere, OptionalInt consuming, Runnable work) {
        reset(startedHere, consuming.isPresent() ? Optional.of(new QueueConsumers(consuming.getAsInt(), 0)) : Optional.empty(), false, work);
    }

    private void reset(boolean startedHere, Optional<QueueConsumers> consumers, boolean ignoreDepartedInstances, Runnable work) {
        StoppedEverywhereReset.reset(processorName, startedHere, lockManager, List.of(readerLock), "queue 'q'", () -> consumers, ignoreDepartedInstances, work);
    }

    @Test
    void a_reset_runs_once_no_reader_and_no_consumer_is_left_and_then_releases_every_lock() {
        grant(resetLock);
        grant(readerLock);
        var ran = new AtomicBoolean();

        reset(false, OptionalInt.of(0), () -> {
            // Both locks are still held while the reset runs
            verify(granted.get(resetLock), never()).release();
            verify(granted.get(readerLock), never()).release();
            ran.set(true);
        });

        assertThat(ran).isTrue();
        var order = inOrder(lockManager, granted.get(readerLock), granted.get(resetLock));
        order.verify(lockManager).tryAcquireLock(resetLock);
        order.verify(lockManager).tryAcquireLock(readerLock);
        // Released in reverse
        order.verify(granted.get(readerLock)).release();
        order.verify(granted.get(resetLock)).release();
    }

    @Test
    void a_reset_is_refused_while_the_processor_is_started_on_this_instance() {
        assertThatThrownBy(() -> reset(true, OptionalInt.of(0), () -> fail("reset ran")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("started on this instance");
        verifyNoInteractions(lockManager);
    }

    @Test
    void a_reset_is_refused_while_a_start_or_another_reset_holds_the_reset_lock() {
        refuse(resetLock);
        grant(readerLock);

        assertThatThrownBy(() -> reset(false, OptionalInt.of(0), () -> fail("reset ran")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(resetLock.toString());
        verify(lockManager, never()).tryAcquireLock(readerLock);
    }

    @Test
    void a_reset_is_refused_while_an_instance_still_reads_and_releases_the_reset_lock() {
        grant(resetLock);
        refuse(readerLock);

        assertThatThrownBy(() -> reset(false, OptionalInt.of(0), () -> fail("reset ran")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still reads");
        verify(granted.get(resetLock)).release();
    }

    @Test
    void a_reset_is_refused_while_any_instance_consumes_the_queue_and_releases_every_lock() {
        grant(resetLock);
        grant(readerLock);

        assertThatThrownBy(() -> reset(false, OptionalInt.of(2), () -> fail("reset ran")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 instance(s) still consume queue 'q'");
        verify(granted.get(resetLock)).release();
        verify(granted.get(readerLock)).release();
    }

    @Test
    void a_reset_is_refused_while_a_departed_instance_still_holds_part_of_the_queue() {
        grant(resetLock);
        grant(readerLock);

        assertThatThrownBy(() -> reset(false, Optional.of(new QueueConsumers(0, 1)), false, () -> fail("reset ran")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 departed instance(s) still hold part of queue 'q'");
        verify(granted.get(resetLock)).release();
        verify(granted.get(readerLock)).release();
    }

    @Test
    void a_reset_asked_to_ignore_departed_instances_runs_although_one_still_holds_part_of_the_queue() {
        grant(resetLock);
        grant(readerLock);
        var ran = new AtomicBoolean();

        reset(false, Optional.of(new QueueConsumers(0, 1)), true, () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void a_reset_asked_to_ignore_departed_instances_is_still_refused_while_an_instance_consumes() {
        grant(resetLock);
        grant(readerLock);

        assertThatThrownBy(() -> reset(false, Optional.of(new QueueConsumers(1, 1)), true, () -> fail("reset ran")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 instance(s) still consume");
    }

    @Test
    void a_reset_is_refused_where_the_queue_implementation_cannot_tell_who_consumes_it() {
        grant(resetLock);
        grant(readerLock);

        assertThatThrownBy(() -> reset(false, OptionalInt.empty(), () -> fail("reset ran")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot tell who consumes");
        verify(granted.get(resetLock)).release();
        verify(granted.get(readerLock)).release();
    }

    @Test
    void a_failing_reset_still_releases_every_lock_and_lets_a_start_through_afterwards() {
        grant(resetLock);
        grant(readerLock);

        assertThatThrownBy(() -> reset(false, OptionalInt.of(0), () -> {
            throw new IllegalStateException("truncate failed");
        })).hasMessage("truncate failed");
        verify(granted.get(resetLock)).release();
        verify(granted.get(readerLock)).release();

        var started = new AtomicBoolean();
        StoppedEverywhereReset.startConsumingExclusiveOfAReset(processorName, lockManager, Duration.ofMillis(100), () -> started.set(true));
        assertThat(started).isTrue();
    }

    @Test
    void a_start_registers_its_consumer_while_holding_the_reset_lock_and_releases_it_afterwards() {
        grant(resetLock);
        var registeredUnderLock = new AtomicBoolean();

        StoppedEverywhereReset.startConsumingExclusiveOfAReset(processorName, lockManager, Duration.ofSeconds(1), () -> {
            verify(granted.get(resetLock), never()).release();
            registeredUnderLock.set(true);
        });

        assertThat(registeredUnderLock).isTrue();
        verify(granted.get(resetLock)).release();
    }

    @Test
    void a_start_gives_up_without_registering_while_a_reset_on_another_instance_holds_the_reset_lock() {
        refuse(resetLock);

        assertThatThrownBy(() -> StoppedEverywhereReset.startConsumingExclusiveOfAReset(processorName, lockManager, Duration.ofMillis(50),
                                                                                         () -> fail("consumer registered during a reset")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a reset of the processor is in progress");
    }

    @Test
    void a_start_in_the_same_jvm_waits_for_a_reset_in_progress_and_registers_only_after_it() throws Exception {
        // One lock manager hands the same lock to every caller in a JVM, so the fenced lock alone would let the start through
        grant(resetLock);
        grant(readerLock);
        var resetRunning = new CountDownLatch(1);
        var finishReset  = new CountDownLatch(1);
        var resetDone    = new AtomicBoolean();
        var executor     = Executors.newSingleThreadExecutor();
        try {
            var reset = executor.submit(() -> reset(false, OptionalInt.of(0), () -> {
                resetRunning.countDown();
                await(finishReset);
                resetDone.set(true);
            }));
            assertThat(resetRunning.await(5, TimeUnit.SECONDS)).isTrue();

            var registeredAfterReset = new AtomicReference<Boolean>();
            var start = new Thread(() -> StoppedEverywhereReset.startConsumingExclusiveOfAReset(processorName, lockManager, Duration.ofSeconds(10),
                                                                                                () -> registeredAfterReset.set(resetDone.get())));
            start.start();
            Thread.sleep(200);
            assertThat(registeredAfterReset.get()).as("registered while the reset ran").isNull();

            finishReset.countDown();
            reset.get(5, TimeUnit.SECONDS);
            start.join(5_000);
            assertThat(registeredAfterReset.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void a_reset_in_the_same_jvm_is_refused_while_a_start_registers_its_consumer() throws Exception {
        grant(resetLock);
        grant(readerLock);
        var registering    = new CountDownLatch(1);
        var finishRegister = new CountDownLatch(1);
        var start = new Thread(() -> StoppedEverywhereReset.startConsumingExclusiveOfAReset(processorName, lockManager, Duration.ofSeconds(10), () -> {
            registering.countDown();
            await(finishRegister);
        }));
        start.start();
        try {
            assertThat(registering.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> reset(false, OptionalInt.of(0), () -> fail("reset ran during a start")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("a start or another reset of the processor is in progress");
        } finally {
            finishRegister.countDown();
            start.join(5_000);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
