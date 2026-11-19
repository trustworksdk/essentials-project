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

import java.sql.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

/**
 * What a subscription retries indefinitely as the engine's I/O failure, and what it retries
 * {@code retryAttempts} times and then parks. Drives {@link Subscription#isEngineFailure} and
 * {@link Subscription#retriesUsedUp} the way the delivery loop does: an engine failure counts no attempt, a handler
 * failure counts one, and the event is parked once its retries are used up. The end-to-end proofs are
 * {@code EventLogPageFailureIT} (page path) and the processor module's {@code EventReactorIT}/{@code ViewProjectionIT}.
 */
class SubscriptionFailureClassificationTest {
    private static final ErrorSettings   ERRORS         = new ErrorSettings(3, Duration.ofMillis(1), Duration.ofMillis(10), 100);
    private static final BooleanSupplier CONNECTION_OK  = () -> false;
    private static final BooleanSupplier CONNECTION_LOST = () -> true;
    /** Far more failures than any retry budget: an event not parked by then is retried forever */
    private static final int             FAILURES       = 1_000;

    /**
     * The delivery loop's decisions for an event that fails {@link #FAILURES} times in a row
     *
     * @return the failure on which the event was parked (its attempts), or -1 if it never was
     */
    private static int parkedAfter(boolean thrownByHandler, Throwable error, BooleanSupplier pageConnectionLost, TestSeams seams) {
        var attempts = 0;
        for (var failure = 1; failure <= FAILURES; failure++) {
            if (Subscription.isEngineFailure(thrownByHandler, error, pageConnectionLost, seams)) {
                continue;
            }
            attempts++;
            if (Subscription.retriesUsedUp(attempts, ERRORS)) {
                return attempts;
            }
        }
        return -1;
    }

    private static TestSeams mutant(WatermarkMutant mutant) {
        return new TestSeams(Set.of(mutant), TestSeams.Hooks.NONE, null);
    }

    // ---- the handler's own I/O-classified failure: retried, then parked ----

    @Test
    void a_handler_throwing_SQLTransientConnectionException_is_parked_after_its_retries() {
        var error = new SQLTransientConnectionException("the handler's own connection", "08001");
        assertThat(Subscription.isIoFailure(error)).as("I/O-classified").isTrue();
        assertThat(parkedAfter(true, error, CONNECTION_OK, TestSeams.NONE)).isEqualTo(ERRORS.retryAttempts() + 1);
    }

    @Test
    void a_handler_throwing_a_wrapped_SQLState_08_failure_is_parked_after_its_retries() {
        var error = new IllegalStateException("calling the pricing service failed", new SQLException("connection refused", "08006"));
        assertThat(Subscription.isIoFailure(error)).as("I/O-classified").isTrue();
        assertThat(parkedAfter(true, error, CONNECTION_OK, TestSeams.NONE)).isEqualTo(ERRORS.retryAttempts() + 1);
    }

    @Test
    void must_fail_HANDLER_IO_RETRIED_FOREVER_never_parks_the_handlers_connection_error() {
        var error = new SQLTransientConnectionException("the handler's own connection", "08001");
        assertThat(parkedAfter(true, error, CONNECTION_OK, mutant(WatermarkMutant.HANDLER_IO_RETRIED_FOREVER))).isEqualTo(-1);
        assertThatThrownBy(() -> assertThat(parkedAfter(true, error, CONNECTION_OK, mutant(WatermarkMutant.HANDLER_IO_RETRIED_FOREVER)))
                .isEqualTo(ERRORS.retryAttempts() + 1)).isInstanceOf(AssertionError.class);
    }

    @Test
    void any_other_handler_failure_is_parked_after_its_retries_too() {
        assertThat(parkedAfter(true, new IllegalArgumentException("bad payload"), CONNECTION_OK, TestSeams.NONE)).isEqualTo(ERRORS.retryAttempts() + 1);
    }

    @Test
    void no_retries_parks_on_the_first_failure() {
        assertThat(Subscription.retriesUsedUp(1, new ErrorSettings(0, Duration.ofMillis(1), Duration.ofMillis(1), 1))).isTrue();
        assertThat(Subscription.retriesUsedUp(3, ERRORS)).isFalse();
        assertThat(Subscription.retriesUsedUp(4, ERRORS)).isTrue();
    }

    // ---- the engine's own I/O failure: retried indefinitely, never parked ----

    @Test
    void a_lost_page_connection_during_the_handler_is_retried_indefinitely() {
        var error = new SQLException("An I/O error occurred while sending to the backend", "08006");
        assertThat(parkedAfter(true, error, CONNECTION_LOST, TestSeams.NONE)).isEqualTo(-1);
    }

    @Test
    void a_lost_page_connection_is_the_engines_failure_whatever_the_handler_made_of_it() {
        var error = new IllegalStateException("the handler wrapped it as something else");
        assertThat(parkedAfter(true, error, CONNECTION_LOST, TestSeams.NONE)).isEqualTo(-1);
    }

    @Test
    void a_failure_outside_the_handler_is_retried_indefinitely_and_does_not_probe_the_connection() {
        var probed = new AtomicBoolean();
        var error  = new SQLTransientConnectionException("Getting a page connection failed", "08001");
        assertThat(parkedAfter(false, error, () -> {
            probed.set(true);
            return false;
        }, TestSeams.NONE)).isEqualTo(-1);
        assertThat(probed).as("the page connection probed").isFalse();
        assertThat(parkedAfter(false, new IllegalStateException("not even I/O-classified"), CONNECTION_OK, TestSeams.NONE)).isEqualTo(-1);
    }
}
