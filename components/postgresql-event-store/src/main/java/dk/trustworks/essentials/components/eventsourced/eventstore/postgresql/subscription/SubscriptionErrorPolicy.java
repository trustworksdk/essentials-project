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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inbox;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * What an <b>asynchronous</b> {@link EventStoreSubscription} (direct {@link PersistedEventHandler} or
 * {@link BatchedPersistedEventHandler}) does when its handler throws an exception that the subscriber does not already
 * retry by itself.
 * <p>
 * I/O and connection errors ({@link IOExceptionUtil#isIOException(Throwable)}) are outside this policy: they are
 * retried indefinitely with backoff whatever the policy says. The policy decides the fate of every other error:
 * <ul>
 *     <li>{@link Mode#SKIP} (the default, and the only behaviour before this policy existed) - the event is logged at
 *     ERROR as "Skipping ... event because of error", the resume point advances past it and the subscription continues
 *     with the next event. The event is <b>not</b> redelivered, not even after a restart.</li>
 *     <li>{@link Mode#RETRY_N_THEN_SKIP} - the handler is called again up to {@link #maxRetries()} times, waiting
 *     {@link #backoffBeforeRetry(int)} between attempts, each attempt in a new {@code UnitOfWork}. If every retry fails
 *     the event is skipped exactly as with {@link Mode#SKIP}. The retries run on the subscription's delivery thread,
 *     so later events wait for them - that is what keeps the events in order. That thread belongs to the one
 *     subscription whether it is served by polling or by CDC, so a subscription in its backoff does not hold up the
 *     others. If the subscription is stopped during the retries (restart, fenced-lock hand-over, {@code resetFrom},
 *     unsubscribe) the retries are abandoned, not used up: the event is not skipped, and the restarted subscription
 *     handles it again.</li>
 *     <li>{@link Mode#STOP} - the subscription stops handling events at the failed event: its resume point is not
 *     advanced past it, the failure is logged at ERROR, and no further events are handled until the subscription is
 *     resumed ({@link EventStoreSubscription#resumeIfStoppedByErrorPolicy()}, also offered by the subscription manager
 *     and the admin API) or started again (application restart, fenced-lock hand-over, {@code resetFrom}, or unsubscribe
 *     + subscribe). A resumed or restarted subscription continues <i>at</i> the failed event, so nothing is skipped. If
 *     the failure is permanent the subscription stops at the same event again. The subscription keeps any fenced lock it
 *     holds while stopped, so an exclusive subscription does not flap to another node that would fail the same way. A
 *     stopped subscription reports {@link EventStoreSubscription#isStoppedByErrorPolicy()} (its
 *     {@link EventStoreSubscription#isActive()} is unchanged) and is reported to
 *     {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver#subscriptionStoppedByErrorPolicy}.
 *     <br><b>{@code STOP} gives up on the first failure</b>, and plenty of failures are transient without being I/O
 *     errors: a serialization failure or deadlock (PostgreSQL SQLState {@code 40001} / {@code 40P01}), a lock that is not
 *     available ({@code 55P03}), an optimistic-concurrency conflict on an append. Each of them would halt the subscription
 *     until someone resumes it, so a projection that must not skip an event normally wants {@link Mode#RETRY_N_THEN_STOP}.</li>
 *     <li>{@link Mode#RETRY_N_THEN_STOP} - retries exactly as {@link Mode#RETRY_N_THEN_SKIP}; if every retry fails, stops
 *     exactly as {@link Mode#STOP}. A transient failure is retried away, a lasting one still never skips the event.</li>
 * </ul>
 * {@code RETRY_N_THEN_STOP} is a mode of its own, not {@code STOP} with {@code maxRetries > 0}: {@code STOP} has always
 * ignored {@link #maxRetries()} - and the Spring starter's {@code max-retries} property defaults to 3 whatever the mode -
 * so honouring it for {@code STOP} would silently start retrying in every application already configured for
 * {@code STOP}. {@link #stop()} keeps giving up on the first failure.
 * <p>
 * <b>Which setting wins:</b> the event handler's own policy, then the manager's.
 * <ol>
 *     <li>A {@link PersistedEventHandler#subscriptionErrorPolicy()} or {@link BatchedPersistedEventHandler#subscriptionErrorPolicy()}
 *     that returns a policy decides for the subscription of that handler - and only for it. A {@code ViewEventProcessor} or
 *     {@code EventProcessor} sets it by overriding {@code AbstractEventProcessor#getSubscriptionErrorPolicy()}. This is what
 *     lets one manager serve a projection that must stop at a failed event and a side-effect subscriber that skips it.</li>
 *     <li>Otherwise the policy of the {@link EventStoreSubscriptionManager} that created the subscription applies:
 *     {@link EventStoreSubscriptionManagerBuilder#setSubscriptionErrorPolicy(SubscriptionErrorPolicy)} (Spring Boot:
 *     {@code essentials.eventstore.subscription-manager.error-policy.*}), carried through
 *     {@link EventStoreSubscriptionManagerSettings#subscriptionErrorPolicy()}, and {@link #skip()} when it is not set.</li>
 * </ol>
 * {@link EventStoreSubscriptionManagerSettings#subscriptionErrorPolicyFor(PersistedEventHandler)} resolves it; a subscriber
 * built directly with {@link PersistedEventSubscriberBuilder} or {@link BatchedPersistedEventSubscriberBuilder} applies
 * exactly the policy given to its builder.
 * <p>
 * The policy does not apply to in-transaction subscriptions, where a handler exception rolls back the caller's
 * {@code UnitOfWork}, nor to subscriptions that forward to an {@link Inbox}, which has its own redelivery policy.
 *
 * @param mode           what to do with an event whose handler failed
 * @param maxRetries     how many times {@link Mode#RETRY_N_THEN_SKIP} and {@link Mode#RETRY_N_THEN_STOP} call the handler again after the first
 *                       failure. Must be {@code >= 1} for those two modes; ignored by the other modes
 * @param initialBackoff the wait before the first retry; each later retry doubles it, capped at {@code maxBackoff}. Ignored unless the mode
 *                       retries (see {@link Mode#retries()})
 * @param maxBackoff     the longest wait between two retries. Must be {@code >= initialBackoff}
 */
public record SubscriptionErrorPolicy(Mode mode,
                                      int maxRetries,
                                      Duration initialBackoff,
                                      Duration maxBackoff) {
    /**
     * Default wait before the first retry of a retrying mode
     */
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(100);
    /**
     * Default cap on the wait between two retries of a retrying mode
     */
    public static final Duration DEFAULT_MAX_BACKOFF     = Duration.ofSeconds(1);

    private static final SubscriptionErrorPolicy SKIP = new SubscriptionErrorPolicy(Mode.SKIP, 0, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF);
    private static final SubscriptionErrorPolicy STOP = new SubscriptionErrorPolicy(Mode.STOP, 0, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF);

    /**
     * What an asynchronous subscription does with an event whose handler failed with a non-I/O error
     */
    public enum Mode {
        /**
         * Log at ERROR, advance the resume point past the event and continue with the next event. The default
         */
        SKIP,
        /**
         * Call the handler again up to {@link SubscriptionErrorPolicy#maxRetries()} times with backoff, then skip as {@link #SKIP}
         */
        RETRY_N_THEN_SKIP,
        /**
         * Log at ERROR and stop handling events at the failed event, without advancing the resume point past it. Gives up on
         * the first failure, transient or not - see {@link #RETRY_N_THEN_STOP}
         */
        STOP,
        /**
         * Call the handler again up to {@link SubscriptionErrorPolicy#maxRetries()} times with backoff, then stop as {@link #STOP}
         */
        RETRY_N_THEN_STOP;

        /**
         * @return true if this mode calls the handler again after a failure: {@link #RETRY_N_THEN_SKIP} and {@link #RETRY_N_THEN_STOP}
         */
        public boolean retries() {
            return this == RETRY_N_THEN_SKIP || this == RETRY_N_THEN_STOP;
        }

        /**
         * @return true if this mode stops the subscription once it gives up on an event: {@link #STOP} and {@link #RETRY_N_THEN_STOP}
         */
        public boolean stops() {
            return this == STOP || this == RETRY_N_THEN_STOP;
        }
    }

    public SubscriptionErrorPolicy {
        requireNonNull(mode, "No mode provided");
        requireNonNull(initialBackoff, "No initialBackoff provided");
        requireNonNull(maxBackoff, "No maxBackoff provided");
        requireTrue(maxRetries >= 0, "maxRetries must be >= 0");
        requireTrue(!mode.retries() || maxRetries >= 1, "maxRetries must be >= 1 when the mode is " + mode);
        requireFalse(initialBackoff.isNegative(), "initialBackoff must not be negative");
        requireTrue(maxBackoff.compareTo(initialBackoff) >= 0, "maxBackoff must be >= initialBackoff");
    }

    /**
     * @return the {@link Mode#SKIP} policy - the default
     */
    public static SubscriptionErrorPolicy skip() {
        return SKIP;
    }

    /**
     * @return the {@link Mode#STOP} policy, which stops at the first failure without retrying - see {@link #retryThenStop(int)}
     */
    public static SubscriptionErrorPolicy stop() {
        return STOP;
    }

    /**
     * {@link Mode#RETRY_N_THEN_SKIP} with {@link #DEFAULT_INITIAL_BACKOFF} and {@link #DEFAULT_MAX_BACKOFF}
     *
     * @param maxRetries how many times the handler is called again after the first failure before the event is skipped. Must be {@code >= 1}
     * @return the policy
     */
    public static SubscriptionErrorPolicy retryThenSkip(int maxRetries) {
        return retryThenSkip(maxRetries, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF);
    }

    /**
     * {@link Mode#RETRY_N_THEN_SKIP}
     *
     * @param maxRetries     how many times the handler is called again after the first failure before the event is skipped. Must be {@code >= 1}
     * @param initialBackoff the wait before the first retry; each later retry doubles it
     * @param maxBackoff     the longest wait between two retries
     * @return the policy
     */
    public static SubscriptionErrorPolicy retryThenSkip(int maxRetries, Duration initialBackoff, Duration maxBackoff) {
        return new SubscriptionErrorPolicy(Mode.RETRY_N_THEN_SKIP, maxRetries, initialBackoff, maxBackoff);
    }

    /**
     * {@link Mode#RETRY_N_THEN_STOP} with {@link #DEFAULT_INITIAL_BACKOFF} and {@link #DEFAULT_MAX_BACKOFF}
     *
     * @param maxRetries how many times the handler is called again after the first failure before the subscription stops at the event.
     *                   Must be {@code >= 1}
     * @return the policy
     */
    public static SubscriptionErrorPolicy retryThenStop(int maxRetries) {
        return retryThenStop(maxRetries, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF);
    }

    /**
     * {@link Mode#RETRY_N_THEN_STOP}: retry a failed event, and stop at it only once every retry failed - the policy for a
     * subscription (typically a projection) that must not skip an event, without halting it on a transient failure.
     *
     * @param maxRetries     how many times the handler is called again after the first failure before the subscription stops at the event.
     *                       Must be {@code >= 1}
     * @param initialBackoff the wait before the first retry; each later retry doubles it. Must not be negative
     * @param maxBackoff     the longest wait between two retries. Must be {@code >= initialBackoff}
     * @return the policy
     */
    public static SubscriptionErrorPolicy retryThenStop(int maxRetries, Duration initialBackoff, Duration maxBackoff) {
        return new SubscriptionErrorPolicy(Mode.RETRY_N_THEN_STOP, maxRetries, initialBackoff, maxBackoff);
    }

    /**
     * @return how many times the handler is called again after its first failure: {@link #maxRetries()} for
     * {@link Mode#RETRY_N_THEN_SKIP} and {@link Mode#RETRY_N_THEN_STOP}, otherwise {@code 0}
     */
    public int retriesBeforeGivingUp() {
        return mode.retries() ? maxRetries : 0;
    }

    /**
     * @return true if the policy stops the subscription once it gives up on an event: {@link Mode#STOP} and
     * {@link Mode#RETRY_N_THEN_STOP}
     */
    public boolean stopsOnError() {
        return mode.stops();
    }

    /**
     * The wait before a given retry: {@link #initialBackoff()} doubled for every earlier retry, capped at {@link #maxBackoff()}
     *
     * @param retryNumber the 1-based number of the retry about to be performed
     * @return the wait before that retry
     */
    public Duration backoffBeforeRetry(int retryNumber) {
        requireTrue(retryNumber >= 1, "retryNumber must be >= 1");
        var backoff = initialBackoff;
        for (int i = 1; i < retryNumber && backoff.compareTo(maxBackoff) < 0; i++) {
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }
}
