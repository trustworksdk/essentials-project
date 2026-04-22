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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.PgOutputProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.PgOutputToPersistedEventConverter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.WalGlobalOrdersExtractor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.jdbi.v3.core.Handle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * {@link LogicalDecodingPlugin} adapter for PostgreSQL built-in {@code pgoutput}.
 * <p>
 * Owns the pgoutput binary protocol decode pipeline (message + row-change decoders) and
 * the {@link PgOutputToPersistedEventConverter} that turns decoded row changes into
 * {@link PersistedEvent}s. Tailer and dispatcher delegate decode + gap extraction here —
 * no pgoutput-specific code lives outside this plugin.
 */
public final class PgOutputLogicalDecodingPlugin implements LogicalDecodingPlugin {
    public static final String PLUGIN_NAME = "pgoutput";

    private final PgOutputProperties                properties;
    private final PgOutputToPersistedEventConverter converter;
    private final PgOutputMessageDecoder            messageDecoder;
    private final PgOutputRowChangeDecoder          rowChangeDecoder;

    public PgOutputLogicalDecodingPlugin(PgOutputProperties properties,
                                         PgOutputToPersistedEventConverter converter) {
        this.properties = requireNonNull(properties, "properties cannot be null");
        this.converter = requireNonNull(converter, "converter cannot be null");
        requireNonBlank(properties.getPublicationName(), "publicationName cannot be blank");
        requireTrue(properties.getProtoVersion() > 0, "protoVersion must be > 0");
        this.messageDecoder = new PgOutputMessageDecoder(properties.getProtoVersion());
        this.rowChangeDecoder = new PgOutputRowChangeDecoder();
    }

    @Override
    public String pluginName() {
        return PLUGIN_NAME;
    }

    @Override
    public Optional<String> unusableReason(Handle handle) {
        if (!PostgresqlUtil.isOutputPluginUsable(handle, pluginName())) {
            return Optional.of("pgoutput plugin not usable");
        }
        if (!PostgresqlUtil.isPublicationAvailable(handle, properties.getPublicationName())) {
            return Optional.of("pgoutput publication '" + properties.getPublicationName() + "' does not exist");
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> slotOptions() {
        return Map.of(
                "proto_version", properties.getProtoVersion(),
                "publication_names", properties.getPublicationName(),
                "binary", properties.isBinary(),
                "messages", properties.isMessages()
        );
    }

    @Override
    public List<PersistedEvent> decode(byte[] payloadBytes) {
        var rowChanges = decodeRowChanges(payloadBytes);
        if (rowChanges.isEmpty()) return List.of();
        var events = new ArrayList<PersistedEvent>(rowChanges.size());
        for (var rowChange : rowChanges) {
            converter.convertIfRelevant(rowChange).ifPresent(events::add);
        }
        return events;
    }

    @Override
    public List<WalGlobalOrdersExtractor.Gap> extractGaps(byte[] payloadBytes) {
        var rowChanges = decodeRowChanges(payloadBytes);
        if (rowChanges.isEmpty()) return List.of();
        var gaps = new ArrayList<WalGlobalOrdersExtractor.Gap>(rowChanges.size());
        for (var rowChange : rowChanges) {
            converter.extractGap(rowChange).ifPresent(gaps::add);
        }
        return gaps;
    }

    @Override
    public DiagnosticSummary diagnosticSummary() {
        // Render a compact histogram of pgoutput message types so failures like "zero INSERTs
        // arriving" show up plainly. Format: "types={B=123, C=123, R=5, I=0, Y=42}"
        var counts = rowChangeDecoder.messageTypeCountsSnapshot();
        String extra = counts.isEmpty()
                       ? null
                       : "types=" + counts;
        return new DiagnosticSummary(
                converter.getInsertsSeenCount(),
                converter.getInsertsWithUnknownAggregateCount(),
                extra);
    }

    private List<PgOutputRowChange> decodeRowChanges(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) return List.of();
        var decodedMessage = messageDecoder.decode(payloadBytes);
        return rowChangeDecoder.accept(decodedMessage);
    }

    public int protocolVersion() {
        return properties.getProtoVersion();
    }
}
