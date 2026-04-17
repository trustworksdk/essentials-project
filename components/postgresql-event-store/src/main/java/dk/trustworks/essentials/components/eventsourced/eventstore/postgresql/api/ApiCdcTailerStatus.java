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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.WalReplicationTailer;

public record ApiCdcTailerStatus(
        String slotName,
        boolean slotLockAcquired,
        boolean started,
        String lastReceiveLsn,
        String lastAckedLsn,
        long lastMessageEpochMs,
        long messagesReceived,
        long inboxWrites,
        long inboxDuplicateWrites,
        long inboxWriteFailures,
        long handlerFailures
) {
    public static ApiCdcTailerStatus from(WalReplicationTailer.WalReplicationTailerStatus status) {
        return new ApiCdcTailerStatus(
                status.slotName(),
                status.slotLockAcquired(),
                status.started(),
                status.lastReceiveLsn(),
                status.lastAckedLsn(),
                status.lastMessageEpochMs(),
                status.messagesReceived(),
                status.inboxWrites(),
                status.inboxDuplicateWrites(),
                status.inboxWriteFailures(),
                status.handlerFailures()
        );
    }
}
