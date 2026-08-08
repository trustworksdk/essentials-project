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

/**
 * @param fallbackCount   subscriptions that fell back to polling <em>after</em> CDC had been active - a real
 *                        CDC regression, and the number worth alerting on
 * @param warmupPollCount subscriptions that started on polling because CDC had not become active yet. Expected
 *                        on every startup, since the lifecycle starts subscriptions before the WAL tailer has
 *                        connected. Not an error
 * @param everActive      whether CDC has been active at least once in this JVM. A non-zero
 *                        {@code warmupPollCount} with {@code everActive=false} means CDC never came up
 */
public record ApiCdcAvailability(
        String state,
        String slotName,
        String reason,
        long lastChangedEpochMs,
        long fallbackCount,
        long warmupPollCount,
        boolean everActive
) {
    public static ApiCdcAvailability from(CdcAvailability.Snapshot snapshot) {
        return new ApiCdcAvailability(
                snapshot.state().name(),
                snapshot.slotName(),
                snapshot.reason(),
                snapshot.lastChangedEpochMs(),
                snapshot.fallbackCount(),
                snapshot.warmupPollCount(),
                snapshot.everActive()
        );
    }
}
