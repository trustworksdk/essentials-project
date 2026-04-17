/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalReplicationTailerProperties;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.jdbi.v3.core.Handle;

import java.util.Map;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@link LogicalDecodingPlugin} adapter for {@code wal2json}.
 */
public final class Wal2JsonLogicalDecodingPlugin implements LogicalDecodingPlugin {
    public static final String PLUGIN_NAME = "wal2json";

    private final WalReplicationTailerProperties properties;

    public Wal2JsonLogicalDecodingPlugin(WalReplicationTailerProperties properties) {
        this.properties = requireNonNull(properties, "properties cannot be null");
    }

    @Override
    public String pluginName() {
        return PLUGIN_NAME;
    }

    @Override
    public Optional<String> unusableReason(Handle handle) {
        return PostgresqlUtil.isOutputPluginUsable(handle, pluginName())
               ? Optional.empty()
               : Optional.of("wal2json plugin not usable");
    }

    @Override
    public Map<String, Object> slotOptions() {
        return Map.of(
                "include-xids", properties.isIncludeXids(),
                "include-timestamp", properties.isIncludeTimestamp(),
                "include-lsn", properties.isIncludeLsn(),
                "pretty-print", properties.isPrettyPrint()
        );
    }

    @Override
    public boolean supportsCurrentPayloadPipeline() {
        return true;
    }
}
