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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import org.slf4j.*;
import reactor.core.Disposable;
import reactor.core.scheduler.*;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Resumes an asynchronous subscription that its {@link SubscriptionErrorPolicy} stopped, as the policy's
 * {@link SubscriptionErrorPolicy#autoResume()} prescribes - one per subscription, shared by the subscribers it creates
 * over time, so the count of resumes at an event survives the resume itself.
 * <p>
 * The subscriber reports a stop with {@link #stoppedAt(GlobalEventOrder, SubscriptionErrorPolicy)}, which schedules
 * {@link EventStoreSubscription#resumeIfStoppedByErrorPolicy()} after {@link SubscriptionErrorPolicy.AutoResume#delayBeforeAttempt(int)}.
 * Before it stops at an event it asks {@link #skipInsteadOfStopping(GlobalEventOrder, SubscriptionErrorPolicy)} whether the
 * event has used up its {@link SubscriptionErrorPolicy.AutoResume#maxAttempts()}. The count is kept per event: a stop at
 * another event starts it over.
 * <p>
 * The subscription cancels a pending resume ({@link #subscriptionStopped()}) whenever it stops for a reason of its own - stop,
 * unsubscribe, fenced-lock release - and the resume never runs once the application is shutting down. A resume that
 * finds the subscription no longer stopped (resumed by hand meanwhile, stopped, lock lost) is a no-op of
 * {@code resumeIfStoppedByErrorPolicy()} itself, so a late one is harmless. The resume runs on {@link Schedulers#boundedElastic()},
 * never on the delivery thread that stopped: resuming disposes that thread's subscriber.
 * <p>
 * It also tracks whether the subscription has got past the event it stopped at ({@link #isAwaitingRecovery()}): from the
 * stop until the subscriber reports an event at or after it as done with ({@link #movedPast(GlobalEventOrder)}) - handled,
 * handed off or skipped - or the subscription stops or is reset. A resumed subscription retrying the failed event is not
 * recovered yet, so {@link EventStoreSubscription#isRecoveringFromErrorPolicyStop()} keeps the stopped gauge at {@code 1}
 * through the resumes of an event that keeps failing.
 */
final class SubscriptionAutoResumer {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionAutoResumer.class);

    private final EventStoreSubscription subscription;
    private final BooleanSupplier        shuttingDown;
    private final Scheduler              scheduler;
    private final Object                 lock = new Object();

    /**
     * The event the subscription last stopped at - guarded by {@link #lock}
     */
    private GlobalEventOrder stoppedAt;
    /**
     * How many times the subscription has been resumed by this resumer at {@link #stoppedAt} - guarded by {@link #lock}.
     * Only a resume that went through counts: one that threw is no attempt at the event, so it never uses up
     * {@link SubscriptionErrorPolicy.AutoResume#maxAttempts()}
     */
    private int              resumesAtStoppedAt;
    /**
     * How many resumes in a row threw since the last one that went through - guarded by {@link #lock}. Lengthens the wait
     * before the next try, as a resume at the event would, without counting as one
     */
    private int              failedResumesInARow;
    /**
     * The scheduled resume, or null - guarded by {@link #lock}
     */
    private Disposable       pendingResume;
    /**
     * The event the subscription stopped at and has not got past yet, or null - written under {@link #lock}, read without it
     * on every event the subscriber is done with
     */
    private volatile GlobalEventOrder awaitingRecoveryAt;

    /**
     * @param subscription the subscription to resume
     * @param shuttingDown true once the application is shutting down - no resume is scheduled or performed from then on
     */
    SubscriptionAutoResumer(EventStoreSubscription subscription, BooleanSupplier shuttingDown) {
        this(subscription, shuttingDown, Schedulers.boundedElastic());
    }

    SubscriptionAutoResumer(EventStoreSubscription subscription, BooleanSupplier shuttingDown, Scheduler scheduler) {
        this.subscription = requireNonNull(subscription, "No subscription provided");
        this.shuttingDown = requireNonNull(shuttingDown, "No shuttingDown provided");
        this.scheduler = requireNonNull(scheduler, "No scheduler provided");
    }

    /**
     * Asked by the subscriber when its stopping policy gives up on an event, before it stops
     *
     * @param failedAt the {@link GlobalEventOrder} the subscriber is about to stop at - for a batched subscriber the first event of the batch
     * @param policy   the subscriber's policy
     * @return true if the subscription has already been resumed {@link SubscriptionErrorPolicy.AutoResume#maxAttempts()}
     * times at <code>failedAt</code>, so the event is to be skipped instead
     */
    boolean skipInsteadOfStopping(GlobalEventOrder failedAt, SubscriptionErrorPolicy policy) {
        requireNonNull(failedAt, "No failedAt provided");
        requireNonNull(policy, "No policy provided");
        if (!policy.resumesAutomatically() || !policy.autoResume().skipsAfterMaxAttempts()) {
            return false;
        }
        synchronized (lock) {
            return failedAt.equals(stoppedAt) && resumesAtStoppedAt >= policy.autoResume().maxAttempts();
        }
    }

    /**
     * @param at the event the subscription stopped at
     * @return how many times this resumer has resumed the subscription at <code>at</code>
     */
    int resumesAt(GlobalEventOrder at) {
        synchronized (lock) {
            return at.equals(stoppedAt) ? resumesAtStoppedAt : 0;
        }
    }

    /**
     * Reported by the subscriber once it has stopped at an event: schedule the resume, if the policy resumes automatically
     *
     * @param failedAt the {@link GlobalEventOrder} the subscriber stopped at - for a batched subscriber the first event of the batch
     * @param policy   the subscriber's policy
     */
    void stoppedAt(GlobalEventOrder failedAt, SubscriptionErrorPolicy policy) {
        requireNonNull(failedAt, "No failedAt provided");
        requireNonNull(policy, "No policy provided");
        synchronized (lock) {
            // Also without auto-resume: a resume by hand recovers only once the failed event is handled
            awaitingRecoveryAt = failedAt;
        }
        if (!policy.resumesAutomatically()) {
            log.info("[{}-{}] The subscription stays stopped at #{} - auto-resume is disabled in its SubscriptionErrorPolicy. " +
                             "Resume it with EventStoreSubscription#resumeIfStoppedByErrorPolicy or the admin API",
                     subscription.subscriberId(),
                     subscription.aggregateType(),
                     failedAt);
            return;
        }
        synchronized (lock) {
            if (!failedAt.equals(stoppedAt)) {
                stoppedAt = failedAt;
                resumesAtStoppedAt = 0;
                failedResumesInARow = 0;
            }
            schedule(failedAt, policy);
        }
    }

    /**
     * Reported by the subscriber for every event it is done with - handled, handed off to its event handler, or skipped.
     * An event at or after the one the subscription stopped at means it has recovered
     *
     * @param doneWith the {@link GlobalEventOrder} of the event - for a batched subscriber the last event of the batch
     */
    void movedPast(GlobalEventOrder doneWith) {
        var awaiting = awaitingRecoveryAt;
        if (awaiting == null || doneWith.longValue() < awaiting.longValue()) {
            // An older gap fill handled out of order says nothing about the failed event
            return;
        }
        synchronized (lock) {
            if (awaitingRecoveryAt != null && doneWith.longValue() >= awaitingRecoveryAt.longValue()) {
                log.info("[{}-{}] The subscription got past #{}, which it had stopped at",
                         subscription.subscriberId(),
                         subscription.aggregateType(),
                         awaitingRecoveryAt);
                awaitingRecoveryAt = null;
            }
        }
    }

    /**
     * @return true from a stop until the subscription has got past the event it stopped at (see {@link #movedPast(GlobalEventOrder)}),
     * or is stopped or reset
     */
    boolean isAwaitingRecovery() {
        return awaitingRecoveryAt != null;
    }

    /**
     * Cancel a pending resume - the subscription is being resumed by hand. Still awaiting recovery: the resumed
     * subscription has yet to get past the failed event
     */
    void cancel() {
        synchronized (lock) {
            cancelPendingResume();
        }
    }

    /**
     * Cancel a pending resume - the subscription stopped, was unsubscribed, lost its fenced lock, or the application is
     * shutting down. The count of resumes at the stopped event is kept: a restarted subscription that stops at the same
     * event again continues it. No longer awaiting recovery: the subscription is not running here
     */
    void subscriptionStopped() {
        synchronized (lock) {
            cancelPendingResume();
            awaitingRecoveryAt = null;
        }
    }

    /**
     * Cancel a pending resume and forget the count - the subscription's resume point was reset
     */
    void reset() {
        synchronized (lock) {
            cancelPendingResume();
            stoppedAt = null;
            resumesAtStoppedAt = 0;
            failedResumesInARow = 0;
            awaitingRecoveryAt = null;
        }
    }

    /**
     * @return true while a resume is scheduled
     */
    boolean isResumePending() {
        synchronized (lock) {
            return pendingResume != null;
        }
    }

    /**
     * Called with {@link #lock} held
     */
    private void schedule(GlobalEventOrder at, SubscriptionErrorPolicy policy) {
        cancelPendingResume();
        if (shuttingDown.getAsBoolean()) {
            log.debug("[{}-{}] Not scheduling a resume of the subscription stopped at #{} - the application is shutting down",
                      subscription.subscriberId(),
                      subscription.aggregateType(),
                      at);
            return;
        }
        var autoResume    = policy.autoResume();
        var attemptNumber = resumesAtStoppedAt + 1;
        // A resume that threw backs off like one more resume at the event, without counting as one
        var delay         = autoResume.delayBeforeAttempt(attemptNumber + failedResumesInARow);
        log.warn("[{}-{}] Resuming the subscription stopped at #{} automatically in {} ms - resume {} of {} at this event",
                 subscription.subscriberId(),
                 subscription.aggregateType(),
                 at,
                 delay.toMillis(),
                 attemptNumber,
                 autoResume.skipsAfterMaxAttempts() ? autoResume.maxAttempts() + " (then the event is skipped)" : "unlimited");
        // Read by the task only under the lock, which is held here until the field is assigned - so a task that runs at once still finds itself
        var task = new Disposable[1];
        task[0] = scheduler.schedule(() -> resume(at, attemptNumber, policy, task), delay.toMillis(), TimeUnit.MILLISECONDS);
        pendingResume = task[0];
    }

    private void resume(GlobalEventOrder at, int attemptNumber, SubscriptionErrorPolicy policy, Disposable[] self) {
        synchronized (lock) {
            if (pendingResume == null || pendingResume != self[0]) {
                // Cancelled or superseded after the task had already started
                return;
            }
            pendingResume = null;
            if (at.equals(stoppedAt)) {
                // Counted before resuming: the resumed subscriber may fail at the event again before resumeIfStoppedByErrorPolicy returns
                resumesAtStoppedAt = attemptNumber;
            }
        }
        if (shuttingDown.getAsBoolean()) {
            return;
        }
        try {
            var resumed = subscription.resumeIfStoppedByErrorPolicy();
            synchronized (lock) {
                failedResumesInARow = 0;
            }
            if (resumed) {
                log.info("[{}-{}] Resumed the subscription stopped at #{} automatically (resume {} at this event)",
                         subscription.subscriberId(),
                         subscription.aggregateType(),
                         at,
                         attemptNumber);
            } else {
                log.debug("[{}-{}] Automatic resume of the subscription stopped at #{} found nothing to resume - it was resumed, stopped or moved meanwhile",
                          subscription.subscriberId(),
                          subscription.aggregateType(),
                          at);
            }
        } catch (RuntimeException e) {
            // E.g. the database is unreachable while the resume point is saved: the subscription is still stopped, so try again later
            log.warn(msg("[{}-{}] Automatic resume of the subscription stopped at #{} failed - trying again later",
                         subscription.subscriberId(),
                         subscription.aggregateType(),
                         at), e);
            synchronized (lock) {
                // Unless the resumed subscriber stopped again meanwhile (a pending resume) - then the resume went through
                if (pendingResume == null && at.equals(stoppedAt)) {
                    // Not an attempt at the event: take back the count made before resuming, so an outage does not use up maxAttempts
                    if (resumesAtStoppedAt == attemptNumber) {
                        resumesAtStoppedAt = attemptNumber - 1;
                    }
                    failedResumesInARow++;
                    schedule(at, policy);
                }
            }
        }
    }

    /**
     * Called with {@link #lock} held
     */
    private void cancelPendingResume() {
        if (pendingResume != null) {
            pendingResume.dispose();
            pendingResume = null;
        }
    }
}
