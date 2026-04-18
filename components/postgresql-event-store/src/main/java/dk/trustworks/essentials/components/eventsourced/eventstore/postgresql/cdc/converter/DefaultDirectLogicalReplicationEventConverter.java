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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalParserMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.PgOutputRowChange;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;

import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public final class DefaultDirectLogicalReplicationEventConverter implements DirectLogicalReplicationEventConverter {
    private final LogicalReplicationToPersistedEventConverter wal2JsonConverter;
    private final PgOutputToPersistedEventConverter pgOutputConverter;

    public DefaultDirectLogicalReplicationEventConverter(LogicalReplicationToPersistedEventConverter wal2JsonConverter,
                                                         PgOutputToPersistedEventConverter pgOutputConverter) {
        this.wal2JsonConverter = requireNonNull(wal2JsonConverter, "wal2JsonConverter cannot be null");
        this.pgOutputConverter = requireNonNull(pgOutputConverter, "pgOutputConverter cannot be null");
    }

    @Override
    public List<PersistedEvent> convertWal2Json(byte[] payloadBytes, String payloadText, WalParserMode walParserMode) {
        return walParserMode == WalParserMode.BYTES
               ? wal2JsonConverter.convert(payloadBytes)
               : wal2JsonConverter.convert(requireNonNull(payloadText, "payloadText cannot be null in STRING mode"));
    }

    @Override
    public Optional<PersistedEvent> convertPgOutputIfRelevant(PgOutputRowChange rowChange) {
        return pgOutputConverter.convertIfRelevant(rowChange);
    }
}
