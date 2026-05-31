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
 * case live latency on a quiet system equals {@code maxDelay}. Phase 2 (interruptible
 * delays via reactive merge) is gated on a concrete sub-100ms requirement.
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

    private long currentDelayMs;
    private long lastSeenEpoch;

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
        this.currentDelayMs = initialDelayMs;
        this.lastSeenEpoch = epochSource.currentEpoch(tableName);
        if (log.isDebugEnabled()) {
            log.debug("Created NotifyAwareEventStorePollingOptimizer for table='{}' "
                              + "initialDelay={} ms maxDelay={} ms multiplier={}",
                      tableName, initialDelayMs, maxDelayMs, settings.backoffMultiplier());
        }
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
            log.trace("Table='{}' no-events poll → delay ramped to {} ms (cap {} ms)",
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
            log.trace("Table='{}' events-returned poll → delay reset to {} ms; epoch baseline now {}",
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
    public long currentDelayMs() {
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
                + '}';
    }
}
