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

package dk.trustworks.essentials.components.foundation.lifecycle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ShutdownContextTest {

    @Test
    void a_cleanup_step_that_completes_reports_success_and_leaves_the_database_reachable() {
        var shutdown = ShutdownContext.startingNow(Duration.ofSeconds(10));

        assertThat(shutdown.attemptCleanup("release lock", () -> {})).isTrue();
        assertThat(shutdown.isDatabaseUnreachable()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void a_cleanup_step_blocked_past_its_timeout_is_interrupted_and_marks_the_database_unreachable() {
        var shutdown = ShutdownContext.startingNow(Duration.ofMillis(300));
        var started  = System.nanoTime();

        // Stands in for a pooled connection checkout against a database that is gone: blocks interruptibly
        var completed = shutdown.attemptCleanup("release lock", () -> Thread.sleep(30_000));

        assertThat(completed).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(shutdown.isDatabaseUnreachable()).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).as("the watchdog's interrupt must not leak into the caller").isFalse();
    }

    @Test
    void once_the_database_is_unreachable_later_cleanup_steps_are_skipped_without_running() {
        var shutdown = ShutdownContext.startingNow(Duration.ofSeconds(10));
        shutdown.attemptCleanup("release lock", () -> {
            throw new SQLTransientConnectionException("Connection is not available, request timed out after 30000ms");
        });
        var ran = new AtomicBoolean();

        assertThat(shutdown.attemptCleanup("release lease", () -> ran.set(true))).isFalse();
        assertThat(ran).isFalse();
    }

    @Test
    void an_io_failure_marks_the_database_unreachable_but_any_other_failure_does_not() {
        var ioFailure = ShutdownContext.startingNow(Duration.ofSeconds(10));
        ioFailure.attemptCleanup("release lock", () -> {
            throw new IOException("Connection refused");
        });
        assertThat(ioFailure.isDatabaseUnreachable()).isTrue();

        var otherFailure = ShutdownContext.startingNow(Duration.ofSeconds(10));
        assertThat(otherFailure.attemptCleanup("release lock", () -> {
            throw new IllegalStateException("lock row already gone");
        })).isFalse();
        assertThat(otherFailure.isDatabaseUnreachable()).isFalse();
    }

    @Test
    void no_cleanup_is_attempted_once_the_shutdown_timeout_has_passed() {
        var shutdown = ShutdownContext.startingNow(Duration.ZERO);
        var ran      = new AtomicBoolean();

        assertThat(shutdown.isDeadlinePassed()).isTrue();
        assertThat(shutdown.attemptCleanup("release lock", () -> ran.set(true))).isFalse();
        assertThat(ran).isFalse();
    }
}
