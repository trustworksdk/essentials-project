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

/**
 * The third outcome of handling an event (or batch), next to success and giving up: the subscriber was stopped
 * (disposed) while the handling was being retried - by {@code stop()}, a fenced-lock release, {@code resetFrom} or
 * an unsubscribe - so the retries were abandoned rather than used up.
 * <p>
 * It is not a verdict on the event. {@link PersistedEventSubscriber} and {@link BatchedPersistedEventSubscriber}
 * therefore neither call their error handler nor report the failure to the observer, and they leave the resume point
 * <i>at</i> the event, so the restarted subscription handles it again. Treating it as giving up instead would skip
 * the event for good - exactly the loss {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP} exists to prevent.
 * <p>
 * Never carries an I/O failure as its cause, so the subscriber's own I/O {@code RetryBackoffSpec} does not retry it.
 */
final class SubscriptionStoppedDuringRetryException extends RuntimeException {
    /**
     * @param failureBeingRetried the non-I/O failure whose retry was abandoned, or {@code null} if the subscriber was
     *                            found stopped before a new attempt of an I/O retry
     */
    SubscriptionStoppedDuringRetryException(Throwable failureBeingRetried) {
        super("The subscriber was stopped while the handling was being retried", failureBeingRetried);
    }
}
