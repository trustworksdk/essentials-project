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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Maintains a {@code pgoutput} relation cache and converts decoded protocol messages into
 * canonical named-row changes.
 */
public final class PgOutputRowChangeDecoder {
    private final Map<Integer, PgOutputMessageDecoder.PgOutputMessage.RelationMessage> relationCache = new ConcurrentHashMap<>();

    private Integer currentTransactionId;
    private Long currentTransactionCommitTimestampMicros;

    public List<PgOutputRowChange> accept(PgOutputMessageDecoder.PgOutputMessage message) {
        requireNonNull(message, "message cannot be null");

        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.BeginMessage begin) {
            currentTransactionId = begin.transactionId();
            currentTransactionCommitTimestampMicros = begin.commitTimestampMicros();
            return List.of();
        }
        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.CommitMessage) {
            currentTransactionId = null;
            currentTransactionCommitTimestampMicros = null;
            return List.of();
        }
        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.RelationMessage relation) {
            relationCache.put(relation.relationId(), relation);
            return List.of();
        }
        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.InsertMessage insert) {
            return List.of(toRowChange(insert));
        }
        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.IgnoredMessage) {
            return List.of();
        }
        throw new IllegalArgumentException("Unsupported pgoutput message type: " + message.getClass().getName());
    }

    private PgOutputRowChange toRowChange(PgOutputMessageDecoder.PgOutputMessage.InsertMessage insert) {
        var relation = relationCache.get(insert.relationId());
        if (relation == null) {
            throw new IllegalStateException("Missing cached pgoutput relation metadata for relationId=" + insert.relationId());
        }

        if (relation.columns().size() != insert.tupleData().values().size()) {
            throw new IllegalStateException("Tuple column count did not match relation metadata for relationId=" + insert.relationId());
        }

        Map<String, PgOutputRowChange.PgOutputValue> values = new LinkedHashMap<>(relation.columns().size());
        Map<String, Integer> columnTypeOids = new LinkedHashMap<>(relation.columns().size());
        List<String> keyColumns = new ArrayList<>();

        for (int i = 0; i < relation.columns().size(); i++) {
            var column = relation.columns().get(i);
            var tupleValue = insert.tupleData().values().get(i);
            values.put(column.name(), mapValue(tupleValue));
            columnTypeOids.put(column.name(), column.dataTypeOid());
            if (column.key()) keyColumns.add(column.name());
        }

        return new PgOutputRowChange(
                "insert",
                insert.relationId(),
                relation.namespace(),
                relation.relationName(),
                currentTransactionId,
                currentTransactionCommitTimestampMicros,
                values,
                columnTypeOids,
                keyColumns
        );
    }

    private PgOutputRowChange.PgOutputValue mapValue(PgOutputMessageDecoder.PgOutputMessage.TupleValue value) {
        return switch (value.kind()) {
            case NULL -> PgOutputRowChange.PgOutputValue.nullValue();
            case UNCHANGED_TOAST -> PgOutputRowChange.PgOutputValue.unchangedToast();
            case TEXT -> PgOutputRowChange.PgOutputValue.text(value.textValue());
            case BINARY -> PgOutputRowChange.PgOutputValue.binary(value.binaryValue());
        };
    }
}
