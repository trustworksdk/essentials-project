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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalParserMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalReplicationTailerProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.JacksonWal2JsonToPersistedEventConverter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.LogicalReplicationToPersistedEventConverter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.WalGlobalOrdersExtractor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.WalMessageFilters;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.DefaultWalMessageFilter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.WalMessageFilter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.jdbi.v3.core.Handle;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@link LogicalDecodingPlugin} adapter for {@code wal2json}.
 * <p>
 * Owns the wal2json payload → {@link PersistedEvent} conversion pipeline, including the
 * {@link WalParserMode} bytes/string switch. Tailer and dispatcher delegate decode + gap
 * extraction here — no wal2json-specific code lives outside this plugin.
 */
public final class Wal2JsonLogicalDecodingPlugin implements LogicalDecodingPlugin {
    public static final String PLUGIN_NAME = "wal2json";

    private final WalReplicationTailerProperties              properties;
    private final LogicalReplicationToPersistedEventConverter converter;
    private final WalGlobalOrdersExtractor                    gapExtractor;
    private final WalParserMode                               walParserMode;

    public Wal2JsonLogicalDecodingPlugin(WalReplicationTailerProperties properties,
                                         LogicalReplicationToPersistedEventConverter converter,
                                         WalGlobalOrdersExtractor gapExtractor,
                                         WalParserMode walParserMode) {
        this.properties = requireNonNull(properties, "properties cannot be null");
        this.converter = requireNonNull(converter, "converter cannot be null");
        this.gapExtractor = requireNonNull(gapExtractor, "gapExtractor cannot be null");
        this.walParserMode = requireNonNull(walParserMode, "walParserMode cannot be null");
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
    public List<PersistedEvent> decode(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) return List.of();
        return walParserMode == WalParserMode.BYTES
               ? converter.convert(payloadBytes)
               : converter.convert(new String(payloadBytes, StandardCharsets.UTF_8));
    }

    @Override
    public List<WalGlobalOrdersExtractor.Gap> extractGaps(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) return List.of();
        return walParserMode == WalParserMode.BYTES
               ? gapExtractor.extract(payloadBytes)
               : gapExtractor.extract(new String(payloadBytes, StandardCharsets.UTF_8));
    }

    @Override
    public boolean preFiltersRawPayloads() {
        return true;
    }

    /**
     * wal2json's raw payloads are JSON. The proper default filter is
     * {@link DefaultWalMessageFilter} — a Jackson-driven, table-name-aware filter that only
     * persists INSERTs targeting registered event-stream tables. We can construct it here as
     * long as the converter exposes its underlying serializer (the standard
     * {@link JacksonWal2JsonToPersistedEventConverter} does); plugins built on a custom
     * converter implementation that doesn't expose a serializer return
     * {@link Optional#empty()} so the tailer falls back to its last-resort filter.
     */
    @Override
    public Optional<WalMessageFilter> defaultRawPayloadFilter(Supplier<Set<String>> eventStreamTableNamesSupplier) {
        requireNonNull(eventStreamTableNamesSupplier, "eventStreamTableNamesSupplier cannot be null");
        if (converter instanceof JacksonWal2JsonToPersistedEventConverter) {
            return Optional.of(WalMessageFilters.createForActiveJacksonFlavor(eventStreamTableNamesSupplier::get));
        }
        return Optional.empty();
    }
}
