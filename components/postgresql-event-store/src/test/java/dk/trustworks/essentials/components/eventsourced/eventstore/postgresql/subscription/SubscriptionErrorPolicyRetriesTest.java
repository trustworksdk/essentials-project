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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.util.retry.*;

import java.time.Duration;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Container-free tests of {@link SubscriptionErrorPolicyRetries}: what ends the synchronous retries early, and what
 * does not.
 */
class SubscriptionErrorPolicyRetriesTest {
    private static final RetryBackoffSpec IO_RETRY_SPEC = Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(10))
                                                               .filter(IOExceptionUtil::isIOException);

    private Thread retryingThread;

    @AfterEach
    void cleanup() throws InterruptedException {
        if (retryingThread != null) {
            retryingThread.interrupt();
            retryingThread.join(5_000);
        }
    }

    /**
     * CdcEventStore's adaptive live source interrupts the delivery thread when it switches a live subscription between
     * polling and the CDC bus - the subscriber is not stopped, so the event must still be retried and handled
     */
    @Test
    void an_interrupted_backoff_of_a_subscriber_that_was_not_stopped_is_waited_out_and_the_retry_runs() {
        var attempts                  = new AtomicInteger();
        var result                    = new AtomicReference<Object>();
        var interruptFlagAfterwards   = new AtomicBoolean();
        retryingThread = Thread.ofPlatform().start(() -> {
            try {
                result.set(SubscriptionErrorPolicyRetries.callRetryingPerPolicy(() -> {
                                                                                    if (attempts.incrementAndGet() == 1) {
                                                                                        throw new IllegalStateException("Intentional failure");
                                                                                    }
                                                                                    // The retried attempt must not run with the interrupt flag set
                                                                                    return Thread.currentThread().isInterrupted() ? "interrupted" : "handled";
                                                                                },
                                                                                SubscriptionErrorPolicy.retryThenSkip(3, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                                                                                IO_RETRY_SPEC,
                                                                                new AtomicInteger(),
                                                                                () -> false,
                                                                                (retryNumber, backoff, failure) -> {
                                                                                }));
            } catch (Throwable e) {
                result.set(e);
            }
            interruptFlagAfterwards.set(Thread.currentThread().isInterrupted());
        });
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> attempts.get() == 1);

        retryingThread.interrupt();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> !retryingThread.isAlive());
        assertThat(result.get()).isEqualTo("handled");
        assertThat(attempts.get()).isEqualTo(2);
        // Re-asserted for the caller once the event is done
        assertThat(interruptFlagAfterwards.get()).isTrue();
    }

    @Test
    void an_interrupted_backoff_of_a_stopped_subscriber_abandons_the_retries() {
        var attempts      = new AtomicInteger();
        var stopRequested = new AtomicBoolean();
        var result        = new AtomicReference<Object>();
        retryingThread = Thread.ofPlatform().start(() -> {
            try {
                result.set(SubscriptionErrorPolicyRetries.callRetryingPerPolicy(() -> {
                                                                                    attempts.incrementAndGet();
                                                                                    throw new IllegalStateException("Intentional failure");
                                                                                },
                                                                                SubscriptionErrorPolicy.retryThenSkip(3, Duration.ofSeconds(30), Duration.ofSeconds(30)),
                                                                                IO_RETRY_SPEC,
                                                                                new AtomicInteger(),
                                                                                stopRequested::get,
                                                                                (retryNumber, backoff, failure) -> {
                                                                                }));
            } catch (Throwable e) {
                result.set(e);
            }
        });
        Awaitility.waitAtMost(Duration.ofSeconds(5)).until(() -> attempts.get() == 1);

        // What stopping the subscriber does: mark it stopped, then dispose the delivery thread
        stopRequested.set(true);
        retryingThread.interrupt();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> !retryingThread.isAlive());
        assertThat(result.get()).isInstanceOf(SubscriptionStoppedDuringRetryException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void the_reactive_retry_spec_never_retries_the_stop_guard_even_when_its_filter_accepts_every_error() {
        var subscriptions = new AtomicInteger();
        var retryEverything = Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(1))
                                   .filter(error -> true);

        var stopGuardFailingRepeatedly = Mono.defer(() -> {
            subscriptions.incrementAndGet();
            return Mono.error(new SubscriptionStoppedDuringRetryException(null));
        });

        assertThatThrownBy(() -> stopGuardFailingRepeatedly.retryWhen(SubscriptionErrorPolicyRetries.neverRetryingAStop(retryEverything))
                                                           .block(Duration.ofSeconds(5)))
                .isInstanceOf(SubscriptionStoppedDuringRetryException.class);
        assertThat(subscriptions.get()).isEqualTo(1);
    }

    @Test
    void the_reactive_retry_spec_still_retries_what_its_own_filter_accepts() {
        var subscriptions = new AtomicInteger();
        var retryEverything = Retry.backoff(3, Duration.ofMillis(1))
                                   .filter(error -> true);

        var failingTwice = Mono.defer(() -> subscriptions.incrementAndGet() <= 2
                                            ? Mono.error(new IllegalStateException("Intentional failure"))
                                            : Mono.just("handled"));

        assertThat(failingTwice.retryWhen(SubscriptionErrorPolicyRetries.neverRetryingAStop(retryEverything))
                               .block(Duration.ofSeconds(5)))
                .isEqualTo("handled");
        assertThat(subscriptions.get()).isEqualTo(3);
    }
}
