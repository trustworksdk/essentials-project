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

package dk.trustworks.essentials.examples.trading.brokerage.types;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;

import java.time.ZoneId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The complete closing-books configuration of the trading-account books policy, as one immutable value.
 *
 * <p>This used to be five separate {@code volatile} fields on {@code TradingAccountClosingBooksPolicy}, each with its
 * own setter. Five independent writes cannot be observed as one configuration: a reader could see a new {@code mode}
 * against the old {@code timeBoundary}, and two writers could interleave into a combination neither of them asked
 * for. Holding all five in one record makes a configuration change a single reference swap, and lets the policy
 * serialise the swaps on one lock.
 *
 * <p>{@code intervalDays} is nullable -- it only means anything to the interval-based policies, and the resolved
 * framework configuration leaves it unset for the others.
 *
 * @param mode           which built-in policy decides when the books roll
 * @param eventThreshold how many events in the current generation trigger a rollover, for the event-count policies
 * @param timeBoundary   which calendar boundary triggers a rollover, for the time-boundary policies
 * @param zoneId         the zone the time boundary is evaluated in
 * @param intervalDays   the rollover interval in days, for the interval policy; may be {@code null}
 */
public record ClosingBooksSettings(ClosingBooksDefaultPolicyType mode,
                                   long eventThreshold,
                                   ClosingBooksTimeBoundary timeBoundary,
                                   ZoneId zoneId,
                                   Integer intervalDays) {
    public ClosingBooksSettings {
        requireNonNull(mode, "No mode provided");
        requireNonNull(timeBoundary, "No timeBoundary provided");
        requireNonNull(zoneId, "No zoneId provided");
    }

    public ClosingBooksSettings withMode(ClosingBooksDefaultPolicyType mode) {
        requireNonNull(mode, "No mode provided");
        return new ClosingBooksSettings(mode, eventThreshold, timeBoundary, zoneId, intervalDays);
    }

    public ClosingBooksSettings withEventThreshold(long eventThreshold) {
        if (eventThreshold <= 0) {
            throw new IllegalArgumentException("eventThreshold must be > 0");
        }
        return new ClosingBooksSettings(mode, eventThreshold, timeBoundary, zoneId, intervalDays);
    }

    public ClosingBooksSettings withTimeBoundary(ClosingBooksTimeBoundary timeBoundary) {
        requireNonNull(timeBoundary, "No timeBoundary provided");
        return new ClosingBooksSettings(mode, eventThreshold, timeBoundary, zoneId, intervalDays);
    }

    public ClosingBooksSettings withZoneId(ZoneId zoneId) {
        requireNonNull(zoneId, "No zoneId provided");
        return new ClosingBooksSettings(mode, eventThreshold, timeBoundary, zoneId, intervalDays);
    }

    public ClosingBooksSettings withIntervalDays(int intervalDays) {
        if (intervalDays <= 0) {
            throw new IllegalArgumentException("intervalDays must be > 0");
        }
        return new ClosingBooksSettings(mode, eventThreshold, timeBoundary, zoneId, intervalDays);
    }
}
