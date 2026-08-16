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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalReplicationTailerProperties;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The configuration a {@link WalReplicationTailer} runs under, as opposed to the collaborators it runs with — the
 * replication slot it owns, the timing knobs, and the two mode flags.
 * <p>
 * The validation that used to sit in the middle of the tailer's constructor lives here, so an unusable configuration
 * is rejected where it is written rather than where it is consumed.
 *
 * @param slotName            the replication slot this tailer owns. Validated as a legal PostgreSQL identifier
 * @param tailerProperties    poll intervals, backoff and jitter settings
 * @param pgSlotMode          how the slot is created/managed
 * @param cdcMode             {@code AUTO} falls back to polling when logical replication is unavailable; {@code REQUIRE} fails startup
 * @param recreateSlotOnStart force-drop and recreate the replication slot on first connection. Loses all unread WAL — recovery only
 */
public record CdcTailerSettings(String slotName,
                                WalReplicationTailerProperties tailerProperties,
                                PgSlotMode pgSlotMode,
                                CdcMode cdcMode,
                                boolean recreateSlotOnStart) {

    public CdcTailerSettings {
        requireNonNull(slotName, "slotName cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(slotName);
        requireNonNull(tailerProperties, "tailerProperties cannot be null");
        requireNonNull(pgSlotMode, "pgSlotMode cannot be null");
        requireNonNull(cdcMode, "cdcMode cannot be null");
        requireNonNull(tailerProperties.getPollInterval(), "pollInterval cannot be null");
        requireNonNull(tailerProperties.getPollBackoffInterval(), "pollBackoffInterval cannot be null");
        requireNonNull(tailerProperties.getMaxPollBackoffInterval(), "maxPollBackInterval cannot be null");
        requireNonNull(tailerProperties.getReplicationStatusInterval(), "replicationStatusInterval cannot be null");
        requireTrue(tailerProperties.getJitterRatio() >= 0.0 && tailerProperties.getJitterRatio() <= 0.5, "jitterRatio must be in [0.0..0.5]");
        requireTrue(tailerProperties.getBackOffFactor() > 1, "backOffFactor must be > 1");
    }

    /**
     * Settings that do not recreate the slot on start — the normal case.
     *
     * @param slotName         see {@link #slotName()}
     * @param tailerProperties see {@link #tailerProperties()}
     * @param pgSlotMode       see {@link #pgSlotMode()}
     * @param cdcMode          see {@link #cdcMode()}
     * @return the settings
     */
    public static CdcTailerSettings of(String slotName,
                                       WalReplicationTailerProperties tailerProperties,
                                       PgSlotMode pgSlotMode,
                                       CdcMode cdcMode) {
        return new CdcTailerSettings(slotName, tailerProperties, pgSlotMode, cdcMode, false);
    }
}
