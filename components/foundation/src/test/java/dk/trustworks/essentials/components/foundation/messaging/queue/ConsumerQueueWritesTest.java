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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Both {@link ConsumerQueueWrites} variants clear the interrupt flag for the write and restore it afterwards - also
 * when the write throws - and never set a flag that was not set
 */
class ConsumerQueueWritesTest {
    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void a_write_runs_with_the_interrupt_flag_cleared_and_the_flag_is_restored() {
        var interruptedDuringWrite = new AtomicReference<Boolean>();
        Thread.currentThread().interrupt();

        ConsumerQueueWrites.withInterruptFlagCleared(() -> interruptedDuringWrite.set(Thread.currentThread().isInterrupted()));

        assertThat(interruptedDuringWrite.get()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void a_write_that_returns_a_value_runs_with_the_interrupt_flag_cleared_and_the_flag_is_restored() {
        Thread.currentThread().interrupt();

        var interruptedDuringWrite = ConsumerQueueWrites.callWithInterruptFlagCleared(() -> Thread.currentThread().isInterrupted());

        assertThat(interruptedDuringWrite).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void the_interrupt_flag_is_restored_when_the_write_throws() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> ConsumerQueueWrites.withInterruptFlagCleared(() -> {
            throw new IllegalStateException("Simulated write failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void a_flag_that_was_not_set_is_not_set_by_the_write() {
        ConsumerQueueWrites.withInterruptFlagCleared(() -> {
        });

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
