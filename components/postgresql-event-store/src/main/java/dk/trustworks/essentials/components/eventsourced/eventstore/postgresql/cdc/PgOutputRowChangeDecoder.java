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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Maintains a {@code pgoutput} relation cache and converts decoded protocol messages into
 * canonical named-row changes.
 */
public final class PgOutputRowChangeDecoder {
    private final Map<Integer, PgOutputMessageDecoder.PgOutputMessage.RelationMessage> relationCache = new ConcurrentHashMap<>();

    /**
     * Running tally of how many messages of each pgoutput type we've seen. Key is the byte code
     * ({@code B} for Begin, {@code C} for Commit, {@code R} for Relation, {@code I} for Insert,
     * and whatever raw byte an {@code IgnoredMessage} was constructed with — e.g. {@code U} for
     * Update, {@code D} for Delete, {@code T} for Truncate, {@code Y} for Type, {@code O} for
     * Origin, {@code M} for logical messages, stream/two-phase markers, etc.). Surfaced via the
     * plugin's {@code DiagnosticSummary} so the effectiveness-monitor failure log can show what
     * pgoutput is actually emitting and pinpoint why zero INSERTs are seen when a publication
     * should be forwarding them.
     */
    private final Map<Character, AtomicLong> messageTypeCounts = new ConcurrentHashMap<>();

    private Integer currentTransactionId;
    private Long currentTransactionCommitTimestampMicros;

    public List<PgOutputRowChange> accept(PgOutputMessageDecoder.PgOutputMessage message) {
        requireNonNull(message, "message cannot be null");

        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.BeginMessage begin) {
            bumpCount('B');
            currentTransactionId = begin.transactionId();
            currentTransactionCommitTimestampMicros = begin.commitTimestampMicros();
            return List.of();
        }
        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.CommitMessage) {
            bumpCount('C');
            currentTransactionId = null;
            currentTransactionCommitTimestampMicros = null;
            return List.of();
        }
        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.RelationMessage relation) {
            bumpCount('R');
            relationCache.put(relation.relationId(), relation);
            return List.of();
        }
        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.InsertMessage insert) {
            bumpCount('I');
            return List.of(toRowChange(insert));
        }
        if (message instanceof PgOutputMessageDecoder.PgOutputMessage.IgnoredMessage ignored) {
            bumpCount(ignored.type());
            return List.of();
        }
        throw new IllegalArgumentException("Unsupported pgoutput message type: " + message.getClass().getName());
    }

    private void bumpCount(char type) {
        messageTypeCounts.computeIfAbsent(type, ignored -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Immutable snapshot of current pgoutput-message-type counts, keyed by the type byte-code.
     * Ordering is stable (natural char order) so the rendered string reads consistently.
     */
    public Map<Character, Long> messageTypeCountsSnapshot() {
        return messageTypeCounts.entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        e -> e.getValue().get(),
                                        (a, b) -> a,
                                        TreeMap::new));
    }

    private PgOutputRowChange toRowChange(PgOutputMessageDecoder.PgOutputMessage.InsertMessage insert) {
        var relation = relationCache.get(insert.relationId());
        if (relation == null) {
            // Recoverable: the schema arrives in a separate 'R' message which the inbox retains,
            // so the dispatcher can re-prime this cache and retry before poisoning the row.
            throw new MissingRelationMetadataException(
                    "Missing cached pgoutput relation metadata for relationId=" + insert.relationId(),
                    insert.relationId());
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
