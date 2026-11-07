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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Thrown by {@link BatchedPersistedEventHandler#handOffFailedBatch(List, Throwable, SubscriptionErrorPolicy)} when it
 * gave up on the failed batch part-way: the events before {@link #redeliverFromAndIncluding()} are done with (taken over,
 * or given up by the handler itself), and the events from it on must be delivered again instead of being skipped with the
 * batch. Recognised by the {@link BatchedPersistedEventSubscriber} anywhere in the cause chain of the hand-off's failure.
 * <p>
 * The subscriber then does not let the {@link SubscriptionErrorPolicy} skip the batch: it acknowledges the events before
 * {@link #redeliverFromAndIncluding()}, holds the resume point at it and stops, and the subscription is resumed there after
 * the delays of the policy's {@link SubscriptionErrorPolicy#autoResume()} - whatever the policy's mode - so the events from
 * it on are delivered again. With that auto-resume disabled, the subscription stays stopped there until it is resumed by
 * hand; no event is skipped. Once the subscription has been resumed {@link SubscriptionErrorPolicy.AutoResume#maxAttempts()}
 * times at the same event (only when that is limited), the next such failure at that same event is skipped as the policy
 * says - a hand-off that moves the redelivery point on with each resume never reaches that cap. When this exception has a
 * cause, the events before {@link #redeliverFromAndIncluding()} - the ones given up for good and also the ones the
 * hand-off forwarded - are reported to the observer's {@code handleEventBatchFailed} and the subscriber's error handler,
 * as a skipped batch's events are. A subscriber
 * with no {@link SubscriptionAutoResumer} (built directly with {@link BatchedPersistedEventSubscriberBuilder}) cannot be
 * resumed and lets the policy skip the batch. While it waits, the subscription reports
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription#isStoppedByErrorPolicy()}.
 * <p>
 * If the subscriber was stopped meanwhile (stop, unsubscribe, fenced-lock release), it only holds its resume point at
 * {@link #redeliverFromAndIncluding()}, and the restarted subscription - here or on the instance that acquires the lock -
 * delivers the events from it on again.
 * <p>
 * Redelivery is at-least-once: an event the hand-off had already completed may be delivered again (for example when the
 * resume point saved before a lock hand-over still lies at the batch's first event).
 */
public class FailedBatchToBeRedeliveredException extends RuntimeException {
    private final GlobalEventOrder redeliverFromAndIncluding;

    /**
     * @param message                   what stopped the hand-off, and where
     * @param redeliverFromAndIncluding the global event order of the first event of the batch to be delivered again
     * @param cause                     the failure of an event the hand-off gave up on before it stopped, or {@code null}
     */
    public FailedBatchToBeRedeliveredException(String message, GlobalEventOrder redeliverFromAndIncluding, Throwable cause) {
        super(message, cause);
        this.redeliverFromAndIncluding = requireNonNull(redeliverFromAndIncluding, "No redeliverFromAndIncluding provided");
    }

    /**
     * @return the global event order of the first event of the batch to be delivered again - the events before it are done with
     */
    public GlobalEventOrder redeliverFromAndIncluding() {
        return redeliverFromAndIncluding;
    }

    /**
     * @param failure the failure of a hand-off
     * @return the first {@link FailedBatchToBeRedeliveredException} in the cause chain of <code>failure</code> (itself
     * included), or {@code null}
     */
    static FailedBatchToBeRedeliveredException findIn(Throwable failure) {
        var seen    = new HashSet<Throwable>();
        var current = failure;
        while (current != null && seen.add(current)) {
            if (current instanceof FailedBatchToBeRedeliveredException redelivery) {
                return redelivery;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * @param batch the failed batch
     * @return the first event of <code>batch</code> at or after {@link #redeliverFromAndIncluding()}, or {@code null}
     */
    PersistedEvent firstEventToRedeliverIn(List<PersistedEvent> batch) {
        for (var event : batch) {
            if (event.globalEventOrder().longValue() >= redeliverFromAndIncluding.longValue()) {
                return event;
            }
        }
        return null;
    }
}
