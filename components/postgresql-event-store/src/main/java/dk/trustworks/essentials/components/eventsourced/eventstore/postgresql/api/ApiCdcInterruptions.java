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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcAvailability;

import java.time.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Times Change Data Capture stopped being active in the queried instance other than by a requested stop - a dropped
 * replication connection, a stream error, the replication slot taken over by another instance.
 * <p>
 * Subscriptions fall back to polling for the duration and return to CDC afterwards, so an interruption costs latency,
 * not events. It is kept after CDC recovers, which {@link ApiCdcAvailability#reason()} is not: that field describes
 * the current state only, so a recovered interruption leaves no other trace. A count that keeps rising points at the
 * connection - a proxy or load balancer idle timeout, {@code wal_sender_timeout}, or a host being suspended.
 *
 * @param count           how many interruptions there have been
 * @param ongoing         whether the most recent one is still ongoing
 * @param lastInterruptedAt when the most recent one began. Null if there has been none
 * @param lastReason      why the most recent one began. Null if there has been none
 * @param lastRecoveredAt when CDC was last active again after an interruption. Null if it has not recovered from one
 */
public record ApiCdcInterruptions(
        long count,
        boolean ongoing,
        OffsetDateTime lastInterruptedAt,
        String lastReason,
        OffsetDateTime lastRecoveredAt
) {
    public static ApiCdcInterruptions from(CdcAvailability.Interruptions interruptions) {
        requireNonNull(interruptions, "No interruptions provided");
        return new ApiCdcInterruptions(
                interruptions.count(),
                interruptions.ongoing(),
                toOffsetDateTime(interruptions.lastInterruptedAtEpochMs()),
                interruptions.lastReason(),
                toOffsetDateTime(interruptions.lastRecoveredAtEpochMs()));
    }

    /**
     * No interruption recorded.
     */
    public static ApiCdcInterruptions none() {
        return new ApiCdcInterruptions(0, false, null, null, null);
    }

    private static OffsetDateTime toOffsetDateTime(long epochMs) {
        return epochMs > 0 ? Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC) : null;
    }
}
