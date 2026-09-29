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

import reactor.core.Exceptions;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;

/**
 * The {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP} retry loop shared by {@link PersistedEventSubscriber} and
 * {@link BatchedPersistedEventSubscriber}.
 * <p>
 * The retries are deliberately <b>synchronous</b> - a plain loop with a sleep on the delivery thread - rather than a
 * second reactive {@code retryWhen}. A reactive retry waits on a timer thread and hands the delivery thread back to the
 * upstream flux, which still has outstanding demand, so the events after the failing one would be handled while it
 * waits: out of order, and for {@link SubscriptionErrorPolicy.Mode#STOP} past the event the subscription is meant to
 * stop at. Handling an event already blocks the delivery thread for as long as the handler takes, so waiting on it for
 * the (bounded) backoff is the same trade-off.
 */
final class SubscriptionErrorPolicyRetries {
    private SubscriptionErrorPolicyRetries() {
    }

    /**
     * Call {@code attempt}, and while it fails with an error that the subscriber's own {@link RetryBackoffSpec} does not
     * retry (i.e. not an I/O error), call it again up to {@link SubscriptionErrorPolicy#retriesBeforeGivingUp()} times.
     *
     * @param attempt          one attempt at handling the event(s), each in its own {@code UnitOfWork}
     * @param policy           the policy deciding how many retries and how long to wait
     * @param retrySpec        the subscriber's reactive retry spec - an error its filter accepts is rethrown at once so the spec handles it as before
     * @param retriesPerformed the retries already spent on this event. Kept by the caller across reactive resubscriptions, so an I/O error in
     *                         the middle of the retries does not reset the budget
     * @param beforeRetry      called before each retry with the 1-based retry number, the wait and the failure being retried
     * @param <T>              the result type
     * @return the result of the first successful attempt
     */
    static <T> T callRetryingPerPolicy(Supplier<T> attempt,
                                       SubscriptionErrorPolicy policy,
                                       RetryBackoffSpec retrySpec,
                                       AtomicInteger retriesPerformed,
                                       RetryListener beforeRetry) {
        while (true) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                if (retrySpec.errorFilter.test(e) || retriesPerformed.get() >= policy.retriesBeforeGivingUp()) {
                    throw e;
                }
                var retryNumber = retriesPerformed.incrementAndGet();
                var backoff     = policy.backoffBeforeRetry(retryNumber);
                beforeRetry.beforeRetry(retryNumber, backoff, e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    // Being interrupted means the subscription is being disposed - give up with the original failure
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Unwrap the {@code RetryExhaustedException} Reactor raises when a {@link RetryBackoffSpec} with a finite number of
     * attempts gives up, so the error handler sees the same failure shape as for an error that was never retried.
     *
     * @param error the error signalled by the handling {@code Mono}
     * @return the failure itself
     */
    static Throwable unwrapRetryExhausted(Throwable error) {
        rethrowIfCriticalError(error);
        if (Exceptions.isRetryExhausted(error) && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    /**
     * Called before each {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP} retry
     */
    @FunctionalInterface
    interface RetryListener {
        void beforeRetry(int retryNumber, Duration backoff, Throwable failure);
    }
}
