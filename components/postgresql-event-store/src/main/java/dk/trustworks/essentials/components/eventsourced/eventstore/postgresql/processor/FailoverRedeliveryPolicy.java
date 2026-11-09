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

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;

import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * A {@link RedeliveryPolicy} that keeps retrying a failed message for longer than a failover takes, so a handler that waits
 * for another processor's work - and throws until it is there - does not dead-letter while that work moves to another
 * instance.
 * <p>
 * The failover window is the lock timeout (how long a dead instance's processors stay locked) plus the queue's window
 * (how long its messages stay unserved: {@code DurableQueues#maxUnservedAfterInstanceLoss()}), each defaulting to
 * {@value #DEFAULT_LOCK_TIME_OUT_SECONDS} s and {@value #DEFAULT_QUEUE_WINDOW_SECONDS} s when unknown. The policy retries for
 * {@value #SAFETY_FACTOR} times that window - the survivors also have to catch up once they took over - starting at
 * 200 ms and doubling to a 10 s cap, so the usual retry is still quick. Measured in the trading demo: a fixed ~10 s budget
 * dead-lettered every "not yet" message during a 30 s failover.
 * <p>
 * The flip side: a message that can never succeed dead-letters after minutes, not seconds, and the messages behind it on
 * the same key wait meanwhile.
 * <p>
 * Built only by {@link #outlasting(Optional, Optional)}, so {@link #retriesFor()} is always what {@link #policy()} waits
 * in total.
 */
public final class FailoverRedeliveryPolicy {
    static final int      DEFAULT_LOCK_TIME_OUT_SECONDS = 15;
    static final int      DEFAULT_QUEUE_WINDOW_SECONDS  = 30;
    static final int      SAFETY_FACTOR                 = 4;
    static final Duration FIRST_DELAY                   = Duration.ofMillis(200);
    static final Duration MAX_DELAY                     = Duration.ofSeconds(10);

    private final RedeliveryPolicy policy;
    private final Duration         retriesFor;
    private final Duration         lockTimeOut;
    private final Duration         queueWindow;

    private FailoverRedeliveryPolicy(RedeliveryPolicy policy, Duration retriesFor, Duration lockTimeOut, Duration queueWindow) {
        this.policy = requireNonNull(policy, "No policy provided");
        this.retriesFor = requireNonNull(retriesFor, "No retriesFor provided");
        this.lockTimeOut = requireNonNull(lockTimeOut, "No lockTimeOut provided");
        this.queueWindow = requireNonNull(queueWindow, "No queueWindow provided");
    }

    /**
     * @param lockTimeOut the lock timeout, e.g. {@code FencedLockManager#getLockTimeOut()}; the default when empty
     * @param queueWindow the queue's window, e.g. {@code DurableQueues#maxUnservedAfterInstanceLoss()}; the default when empty
     * @return a policy that outlasts {@link #SAFETY_FACTOR} times their sum
     */
    public static FailoverRedeliveryPolicy outlasting(Optional<Duration> lockTimeOut, Optional<Duration> queueWindow) {
        requireNonNull(lockTimeOut, "No lockTimeOut provided");
        requireNonNull(queueWindow, "No queueWindow provided");
        var lock   = lockTimeOut.orElse(Duration.ofSeconds(DEFAULT_LOCK_TIME_OUT_SECONDS));
        var queue  = queueWindow.orElse(Duration.ofSeconds(DEFAULT_QUEUE_WINDOW_SECONDS));
        var budget = lock.plus(queue).multipliedBy(SAFETY_FACTOR);
        // Ask the backoff policy itself for each delay, so the total cannot drift from what it waits;
        // the delays do not depend on the redelivery limit, so the probe's limit is irrelevant
        var probe        = backoff(Integer.MAX_VALUE);
        var redeliveries = 0;
        var total        = Duration.ZERO;
        while (total.compareTo(budget) < 0) {
            total = total.plus(probe.calculateNextRedeliveryDelay(redeliveries));
            redeliveries++;
        }
        return new FailoverRedeliveryPolicy(backoff(redeliveries), total, lock, queue);
    }

    private static RedeliveryPolicy backoff(int maximumNumberOfRedeliveries) {
        return RedeliveryPolicy.exponentialBackoff()
                               .setInitialRedeliveryDelay(FIRST_DELAY)
                               .setFollowupRedeliveryDelay(FIRST_DELAY)
                               .setFollowupRedeliveryDelayMultiplier(2.0d)
                               .setMaximumFollowupRedeliveryDelayThreshold(MAX_DELAY)
                               .setMaximumNumberOfRedeliveries(maximumNumberOfRedeliveries)
                               .build();
    }

    /**
     * @return the policy
     */
    public RedeliveryPolicy policy() {
        return policy;
    }

    /**
     * @return how long {@link #policy()} keeps retrying, in total
     */
    public Duration retriesFor() {
        return retriesFor;
    }

    /**
     * @return the lock timeout it was derived from
     */
    public Duration lockTimeOut() {
        return lockTimeOut;
    }

    /**
     * @return the queue window it was derived from
     */
    public Duration queueWindow() {
        return queueWindow;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FailoverRedeliveryPolicy that)) {
            return false;
        }
        return policy.equals(that.policy) && retriesFor.equals(that.retriesFor) && lockTimeOut.equals(that.lockTimeOut) && queueWindow.equals(that.queueWindow);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policy, retriesFor, lockTimeOut, queueWindow);
    }

    @Override
    public String toString() {
        return "FailoverRedeliveryPolicy[policy=" + policy + ", retriesFor=" + retriesFor + ", lockTimeOut=" + lockTimeOut + ", queueWindow=" + queueWindow + "]";
    }

    /**
     * One line for a start-up log: how long it retries, and from what.
     */
    public String describe() {
        return msg("retries a failed message for up to {} ({} redeliveries) - {} x (lock timeout {} + queue window {}), to outlast a failover",
                   retriesFor, policy.getMaximumNumberOfRedeliveries(), SAFETY_FACTOR, lockTimeOut, queueWindow);
    }
}
