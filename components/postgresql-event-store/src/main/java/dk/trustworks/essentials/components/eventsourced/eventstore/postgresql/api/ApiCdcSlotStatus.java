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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.*;

public record ApiCdcSlotStatus(
        String slotName,
        boolean exists,
        String configuredMode,
        String expectedPlugin,
        boolean expectedPluginMatches,
        boolean active,
        Integer activePid,
        String slotType,
        String plugin,
        String database,
        boolean temporary,
        String restartLsn,
        String confirmedFlushLsn,
        String walStatus,
        Long safeWalSize,
        String inactiveSince,
        String conflicting,
        String invalidationReason,
        Boolean failover,
        Boolean synced
) {
    public static ApiCdcSlotStatus missing(String slotName,
                                           PgSlotMode configuredMode,
                                           String expectedPlugin) {
        return new ApiCdcSlotStatus(
                slotName,
                false,
                configuredMode.name(),
                expectedPlugin,
                false,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static ApiCdcSlotStatus from(PgReplicationSlots.SlotInfo slotInfo,
                                        PgSlotMode configuredMode,
                                        String expectedPlugin) {
        return new ApiCdcSlotStatus(
                slotInfo.slotName,
                true,
                configuredMode.name(),
                expectedPlugin,
                slotInfo.plugin != null && expectedPlugin.equalsIgnoreCase(slotInfo.plugin),
                slotInfo.isActive(),
                slotInfo.activePid,
                slotInfo.slotType,
                slotInfo.plugin,
                slotInfo.database,
                slotInfo.temporary,
                slotInfo.restartLsn,
                slotInfo.confirmedFlushLsn,
                slotInfo.walStatus,
                slotInfo.safeWalSize,
                slotInfo.inactiveSince,
                slotInfo.conflicting,
                slotInfo.invalidationReason,
                slotInfo.failover,
                slotInfo.synced
        );
    }
}
