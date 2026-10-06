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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;

/**
 * The retry loop of the retrying {@link SubscriptionErrorPolicy.Mode}s ({@code RETRY_N_THEN_SKIP}, {@code RETRY_N_THEN_STOP}) shared by {@link PersistedEventSubscriber} and
 * {@link BatchedPersistedEventSubscriber}.
 * <p>
 * The retries are deliberately <b>synchronous</b> - a plain loop with a sleep on the delivery thread - rather than a
 * second reactive {@code retryWhen}. A reactive retry waits on a timer thread and hands the delivery thread back to the
 * upstream flux, which still has outstanding demand, so the events after the failing one would be handled while it
 * waits: out of order, and for {@link SubscriptionErrorPolicy.Mode#STOP} past the event the subscription is meant to
 * stop at. Handling an event already blocks the delivery thread for as long as the handler takes, so waiting on it for
 * the (bounded) backoff is the same trade-off.
 * <p>
 * That argument needs the delivery thread to belong to the one subscription, and it does on every path: polling
 * delivers on a {@code Publish-<subscriber>-<aggregateType>} thread per subscription, {@code CdcEventStore} hands the
 * CDC bus over to a {@code Cdc-<subscriber>-<aggregateType>} thread per subscription (so a backoff never holds the
 * shared {@code cdc-dispatcher-<slot>} thread), and {@link BatchedPersistedEventSubscriber} handles batches on a
 * {@code BatchedEventSubscriber-<subscriber>-<aggregateType>-Handler} thread of its own.
 * <p>
 * A retry has three outcomes, not two: success, giving up (the failure is rethrown and the policy's give-up path
 * runs), and {@link SubscriptionStoppedDuringRetryException} when the subscriber was stopped mid-retry - see there
 * for why that must not be treated as giving up.
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
     * @param stopRequested    true once the subscriber has been stopped (disposed). Checked before each retry, so a stop that lands between
     *                         two attempts abandons the retries instead of running them. The only thing that ends the retries early: an
     *                         interrupt of the backoff without a stop is waited out (see {@link #awaitBackoff})
     * @param beforeRetry      called before each retry with the 1-based retry number, the wait and the failure being retried
     * @param <T>              the result type
     * @return the result of the first successful attempt
     * @throws SubscriptionStoppedDuringRetryException if the subscriber was stopped while the retries were under way - interrupted in the
     *                                                 backoff and found stopped, or found stopped before the next attempt
     */
    static <T> T callRetryingPerPolicy(Supplier<T> attempt,
                                       SubscriptionErrorPolicy policy,
                                       RetryBackoffSpec retrySpec,
                                       AtomicInteger retriesPerformed,
                                       BooleanSupplier stopRequested,
                                       RetryListener beforeRetry) {
        var interrupted = false;
        try {
            while (true) {
                try {
                    return attempt.get();
                } catch (RuntimeException e) {
                    if (retrySpec.errorFilter.test(e) || retriesPerformed.get() >= policy.retriesBeforeGivingUp()) {
                        throw e;
                    }
                    if (stopRequested.getAsBoolean()) {
                        throw new SubscriptionStoppedDuringRetryException(e);
                    }
                    var retryNumber = retriesPerformed.incrementAndGet();
                    var backoff     = policy.backoffBeforeRetry(retryNumber);
                    beforeRetry.beforeRetry(retryNumber, backoff, e);
                    interrupted |= awaitBackoff(backoff, stopRequested, e);
                    if (stopRequested.getAsBoolean()) {
                        throw new SubscriptionStoppedDuringRetryException(e);
                    }
                }
            }
        } finally {
            if (interrupted) {
                // Re-asserted only now, so the retried attempts above didn't run with the interrupt flag set
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Wait out the full backoff, unless the subscriber is stopped meanwhile.
     * <p>
     * An interrupt alone is <b>not</b> a stop. Stopping the subscriber disposes its delivery thread, which interrupts
     * the wait - but so does {@code CdcEventStore}'s adaptive live source when it switches a running subscription
     * between polling and the CDC bus (at boot, and whenever replication drops or recovers): cancelling the old source
     * disposes the thread this handler runs on while the subscriber stays live. The event has already passed the
     * source's {@code lastSeen} filter, so the new source will not deliver it again - this invocation has to finish it.
     * Treating that interrupt as a stop would hold the resume point of a subscriber nobody stopped, which then
     * silently ignores every later event.
     *
     * @return true if the wait was interrupted. The caller re-asserts the interrupt once it is done with the event
     * @throws SubscriptionStoppedDuringRetryException if the wait was interrupted and the subscriber has been stopped
     */
    private static boolean awaitBackoff(Duration backoff, BooleanSupplier stopRequested, RuntimeException failureBeingRetried) {
        var interrupted = false;
        var deadline    = System.nanoTime() + backoff.toNanos();
        while (true) {
            var remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return interrupted;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(remainingNanos);
            } catch (InterruptedException e) {
                interrupted = true;
                if (stopRequested.getAsBoolean()) {
                    Thread.currentThread().interrupt();
                    throw new SubscriptionStoppedDuringRetryException(failureBeingRetried);
                }
            }
        }
    }

    /**
     * Wrap the subscriber's reactive {@link RetryBackoffSpec} so it never retries a
     * {@link SubscriptionStoppedDuringRetryException}, whatever error filter the spec was configured with. The guard
     * that throws it would otherwise be retried for as long as the spec allows - with the usual
     * {@code Long.MAX_VALUE} attempts, a timer loop on a disposed subscriber that never ends.
     *
     * @param retrySpec the spec the subscriber was configured with
     * @return the spec to use in {@code retryWhen}
     */
    static RetryBackoffSpec neverRetryingAStop(RetryBackoffSpec retrySpec) {
        return retrySpec.modifyErrorFilter(errorFilter -> errorFilter.and(error -> !(error instanceof SubscriptionStoppedDuringRetryException)));
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
     * Called before each retry of a retrying {@link SubscriptionErrorPolicy.Mode}
     */
    @FunctionalInterface
    interface RetryListener {
        void beforeRetry(int retryNumber, Duration backoff, Throwable failure);
    }
}
