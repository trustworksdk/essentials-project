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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStorePollingOptimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * NOTIFY-aware {@link EventStorePollingOptimizer} — Phase 1 of S1 (see
 * {@code subscription-improvements.md}).
 * <p>
 * Behaviour:
 * <ul>
 *   <li>After an events-returned poll: reset {@code currentDelayMs} to
 *       {@link NotifyPollingSettings#initialDelay()} and snapshot the table's current
 *       epoch as the new baseline.</li>
 *   <li>After a no-events poll: ramp {@code currentDelayMs *= backoffMultiplier},
 *       capped at {@link NotifyPollingSettings#maxDelay()}.</li>
 *   <li>On {@link #currentDelayMs()}: if the table's epoch advanced since our baseline,
 *       the optimizer resets to {@code initialDelay} and returns {@code 0} so the loop
 *       polls immediately. Otherwise it returns the current ramped delay.</li>
 * </ul>
 * Net effect on a steady-state quiet system: the optimizer's delay grows to
 * {@code maxDelay} and stays there → DB query rate drops to ~{@code 1 / maxDelay} per
 * subscriber instead of the unconditional fixed-interval rate. The first NOTIFY for the
 * table resets the delay on the very next iteration, so a quiet system that suddenly
 * becomes active doesn't pay an extra full cycle of backoff before delivering.
 * <p>
 * Phase 1 limitation (documented in subscription-improvements.md): a NOTIFY arriving
 * mid-sleep cannot interrupt the in-flight sleep — only shorten the next one. Worst-
 * case live latency on a quiet system equals {@code maxDelay}. S2 below removes it.
 * <p>
 * <b>S2, interruptible wait</b> ({@link NotifyPollingSettings#interruptibleWait()}, off by default): the wait between
 * two polls ends as soon as the table's epoch moves, instead of only the next wait being shortened. So
 * {@code maxDelay} bounds the query rate of a quiet subscription without also being its wake-up latency.
 * <ul>
 *   <li>{@link #eventStorePollingStarting()} reads the epoch before every poll; {@link #awaitNextPoll(long)} waits
 *       for the epoch to move away from that value. A NOTIFY for an append the poll did not see therefore always
 *       ends the wait - also one that arrived while the poll ran (no lost wake-up between "the poll came back empty"
 *       and "start waiting").</li>
 *   <li>{@link #currentDelayMs()} is then the plain backoff ramp: the Phase 1 epoch peek would be a second wake-up
 *       mechanism doing the same thing.</li>
 *   <li>Coalescing: any number of NOTIFYs collapse into one epoch change, and an early wake-up comes at most once per
 *       {@code initialDelay} per subscription - a wake-up sooner than that after the previous one waits out the rest
 *       of {@code initialDelay} (never past the delay itself). A burst cannot turn into a poll storm.</li>
 *   <li>The wait runs on the polling worker's own thread (no thread is added) and ends on an interrupt, which is how a
 *       cancelled or stopped subscription stops its worker.</li>
 * </ul>
 * The wake-up latency is then the time for the NOTIFY to reach the {@link NotifyEpochSource}: the
 * {@code MultiTableChangeListener}'s polling interval (0 to 50 ms with the Spring default) plus the poll.
 * <p>
 * Thread-safety: each subscription has its own optimizer instance; the optimizer's state
 * is mutated only by the per-subscription poll loop, which is single-threaded. The
 * {@link NotifyEpochSource} read is itself thread-safe so no synchronization is needed
 * here.
 */
public final class NotifyAwareEventStorePollingOptimizer implements EventStorePollingOptimizer {
    private static final Logger log = LoggerFactory.getLogger(NotifyAwareEventStorePollingOptimizer.class);

    private final NotifyEpochSource     epochSource;
    private final String                tableName;
    private final NotifyPollingSettings settings;
    private final long                  initialDelayMs;
    private final long                  maxDelayMs;

    private final boolean               interruptibleWait;

    private long currentDelayMs;
    private long lastSeenEpoch;
    /** S2: the epoch read before the latest poll */
    private long epochAtPollStart;
    /** S2: when the latest wait ended early on an epoch change ({@link System#nanoTime()}); none yet when {@link #hasWokenEarly} is false */
    private long    lastEarlyWakeUpNanos;
    private boolean hasWokenEarly;

    /**
     * @param epochSource per-table epoch source fed by {@code MultiTableChangeListener}'s
     *                    notifications via the framework {@code EventBus}.
     * @param tableName   the event-stream table this subscription polls. Must match the
     *                    {@code tableName} on the {@code TableChangeNotification}s the
     *                    listener publishes — typically the
     *                    {@code SeparateTablePerAggregateEventStreamConfiguration.eventStreamTableName}.
     * @param settings    backoff config (initial, max, multiplier).
     */
    public NotifyAwareEventStorePollingOptimizer(NotifyEpochSource epochSource,
                                                 String tableName,
                                                 NotifyPollingSettings settings) {
        this.epochSource = requireNonNull(epochSource, "epochSource cannot be null");
        this.tableName = requireNonBlank(tableName, "tableName cannot be blank");
        this.settings = requireNonNull(settings, "settings cannot be null");
        this.initialDelayMs = settings.initialDelay().toMillis();
        this.maxDelayMs = settings.maxDelay().toMillis();
        this.interruptibleWait = settings.interruptibleWait();
        this.currentDelayMs = initialDelayMs;
        this.lastSeenEpoch = epochSource.currentEpoch(tableName);
        this.epochAtPollStart = lastSeenEpoch;
        if (log.isDebugEnabled()) {
            log.debug("Created NotifyAwareEventStorePollingOptimizer for table='{}' "
                              + "initialDelay={} ms maxDelay={} ms multiplier={} interruptibleWait={}",
                      tableName, initialDelayMs, maxDelayMs, settings.backoffMultiplier(), interruptibleWait);
        }
    }

    @Override
    public boolean mayRepollImmediatelyAfterAnEmptyPoll() {
        return true;
    }

    @Override
    public void eventStorePollingReturnedNoEvents() {
        // Ramp toward the cap. Multiply-then-floor avoids ever ramping below initialDelay
        // (e.g. after a wake-up that sent us to 0 — the next no-events poll should still
        // start from a sensible floor, not produce a 0-delay busy-spin).
        long next = Math.max(initialDelayMs,
                             (long) Math.ceil(currentDelayMs * settings.backoffMultiplier()));
        currentDelayMs = Math.min(maxDelayMs, next);
        if (log.isTraceEnabled()) {
            log.trace("Table='{}' no-events poll → delay ramped to '{}' ms (cap '{}' ms)",
                      tableName, currentDelayMs, maxDelayMs);
        }
    }

    @Override
    public void eventStorePollingReturnedEvents() {
        currentDelayMs = initialDelayMs;
        // Snapshot the epoch *after* the poll so any NOTIFY that arrived during the poll
        // itself is treated as "already accounted for by this poll" rather than triggering
        // an immediate redundant re-poll. The next genuine NOTIFY then advances past this
        // baseline and wakes us up.
        lastSeenEpoch = epochSource.currentEpoch(tableName);
        if (log.isTraceEnabled()) {
            log.trace("Table='{}' events-returned poll → delay reset to '{}' ms; epoch baseline now '{}'",
                      tableName, currentDelayMs, lastSeenEpoch);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldSkipPolling() {
        // Deprecated in the interface; matches the no-op behaviour of the existing
        // optimizers. The poll loop ignores this in favour of currentDelayMs().
        return false;
    }

    @Override
    public void eventStorePollingStarting() {
        if (interruptibleWait) {
            // Read before the poll: an append the poll does not see commits after this, so its NOTIFY moves the epoch past this value
            epochAtPollStart = epochSource.currentEpoch(tableName);
        }
    }

    @Override
    public void awaitNextPoll(long delayMs) throws InterruptedException {
        if (!interruptibleWait) {
            Thread.sleep(delayMs);
            return;
        }
        long waitStartedNanos = System.nanoTime();
        if (!epochSource.awaitEpochChange(tableName, epochAtPollStart, delayMs)) {
            return;
        }
        long now = System.nanoTime();
        if (hasWokenEarly) {
            // Coalesce: at most one early wake-up per initialDelay, and never later than the delay itself
            long earliestWakeUpNanos = lastEarlyWakeUpNanos + TimeUnit.MILLISECONDS.toNanos(initialDelayMs);
            long deadlineNanos       = waitStartedNanos + TimeUnit.MILLISECONDS.toNanos(delayMs);
            long holdNanos           = Math.min(earliestWakeUpNanos, deadlineNanos) - now;
            if (holdNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(holdNanos);
                now = System.nanoTime();
            }
        }
        hasWokenEarly = true;
        lastEarlyWakeUpNanos = now;
        currentDelayMs = initialDelayMs;
        if (log.isTraceEnabled()) {
            log.trace("Table='{}' NOTIFY ended the wait after '{}' ms (delay '{}' ms)",
                      tableName, TimeUnit.NANOSECONDS.toMillis(now - waitStartedNanos), delayMs);
        }
    }

    @Override
    public long currentDelayMs() {
        if (interruptibleWait) {
            // awaitNextPoll owns the wake-up
            return currentDelayMs;
        }
        long epoch = epochSource.currentEpoch(tableName);
        if (epoch != lastSeenEpoch) {
            // A NOTIFY has landed since our last poll. Force an immediate re-poll and
            // reset the backoff baseline — the wake-up has done its job; further polls
            // will ramp again only if the system is genuinely quiet from here.
            if (log.isTraceEnabled()) {
                log.trace("Table='{}' NOTIFY observed (epoch {} → {}); waking up — return 0 ms",
                          tableName, lastSeenEpoch, epoch);
            }
            lastSeenEpoch = epoch;
            currentDelayMs = initialDelayMs;
            return 0L;
        }
        return currentDelayMs;
    }

    @Override
    public String toString() {
        return "NotifyAwareEventStorePollingOptimizer{"
                + "table='" + tableName + '\''
                + ", currentDelayMs=" + currentDelayMs
                + ", lastSeenEpoch=" + lastSeenEpoch
                + ", initialDelayMs=" + initialDelayMs
                + ", maxDelayMs=" + maxDelayMs
                + ", interruptibleWait=" + interruptibleWait
                + '}';
    }
}
