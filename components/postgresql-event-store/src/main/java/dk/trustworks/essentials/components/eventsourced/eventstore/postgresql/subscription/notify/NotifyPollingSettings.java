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

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * Backoff configuration for the {@link NotifyAwareEventStorePollingOptimizer}. Kept as a
 * standalone record so the existing {@code EventStoreSubscriptionManagerSettings} record
 * stays unchanged and back-compat for callers that construct it positionally.
 * <p>
 * Semantics on a per-subscription optimizer:
 * <ul>
 *   <li>After an events-returned poll: current delay reset to {@link #initialDelay()}; the
 *       optimizer is "fresh" again.</li>
 *   <li>After a no-events poll: current delay ramps by {@link #backoffMultiplier()},
 *       capped at {@link #maxDelay()}. The cap is the worst-case live latency on a
 *       quiet system — a NOTIFY arriving mid-sleep cannot interrupt the in-flight
 *       sleep (this is Phase 1; Phase 2 in subscription-improvements.md addresses
 *       interruptible delays).</li>
 *   <li>On {@code currentDelayMs()}: if a NOTIFY has landed since the last poll, the
 *       optimizer returns {@code 0} to force an immediate re-poll and resets the
 *       backoff to {@link #initialDelay()}.</li>
 * </ul>
 *
 * @param enabled            master switch. {@code false} = framework behaves exactly as
 *                           before — no triggers, no listener registrations, no
 *                           optimizer factory installed.
 * @param initialDelay       backoff floor after an events-returned poll, also the value
 *                           the backoff resets to on a NOTIFY-driven wake-up. Default 50 ms.
 * @param maxDelay           backoff ceiling = worst-case live latency on a quiet system.
 *                           Default 1 s — balances DB-load savings (longer = better)
 *                           against worst-case latency (shorter = better).
 * @param backoffMultiplier  exponential ramp factor between consecutive no-events polls.
 *                           Default 2.0. Must be {@code > 1.0}.
 */
public record NotifyPollingSettings(boolean enabled,
                                    Duration initialDelay,
                                    Duration maxDelay,
                                    double backoffMultiplier) {

    public static final Duration DEFAULT_INITIAL_DELAY      = Duration.ofMillis(50);
    public static final Duration DEFAULT_MAX_DELAY          = Duration.ofSeconds(1);
    public static final double   DEFAULT_BACKOFF_MULTIPLIER = 2.0d;

    public NotifyPollingSettings {
        requireNonNull(initialDelay, "initialDelay cannot be null");
        requireNonNull(maxDelay, "maxDelay cannot be null");
        requireTrue(!initialDelay.isNegative() && !initialDelay.isZero(),
                    "initialDelay must be > 0");
        requireTrue(!maxDelay.isNegative(),
                    "maxDelay must be >= 0");
        requireTrue(maxDelay.compareTo(initialDelay) >= 0,
                    "maxDelay must be >= initialDelay");
        requireTrue(backoffMultiplier > 1.0d,
                    "backoffMultiplier must be > 1.0");
    }

    /** Disabled-state instance — feature off, behaviour unchanged from pre-S1. */
    public static NotifyPollingSettings disabled() {
        return new NotifyPollingSettings(false,
                                         DEFAULT_INITIAL_DELAY,
                                         DEFAULT_MAX_DELAY,
                                         DEFAULT_BACKOFF_MULTIPLIER);
    }

    /** Enabled-state instance with framework defaults — the recommended starting point. */
    public static NotifyPollingSettings defaults() {
        return new NotifyPollingSettings(true,
                                         DEFAULT_INITIAL_DELAY,
                                         DEFAULT_MAX_DELAY,
                                         DEFAULT_BACKOFF_MULTIPLIER);
    }
}
