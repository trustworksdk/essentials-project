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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgOutputRowChangeDecoderTest {
    private final PgOutputRowChangeDecoder rowChangeDecoder = new PgOutputRowChangeDecoder();

    @Test
    void maps_relation_and_insert_into_named_row_change() {
        rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.BeginMessage(100L, 200L, 42));
        rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.RelationMessage(
                7,
                "public",
                "orders_events",
                'd',
                List.of(
                        new PgOutputMessageDecoder.PgOutputMessage.RelationColumn(true, "event_id", 2950, -1),
                        new PgOutputMessageDecoder.PgOutputMessage.RelationColumn(false, "event_payload", 3802, -1),
                        new PgOutputMessageDecoder.PgOutputMessage.RelationColumn(false, "global_order", 20, -1)
                )
        ));

        var changes = rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.InsertMessage(
                7,
                new PgOutputMessageDecoder.PgOutputMessage.TupleData(List.of(
                        PgOutputMessageDecoder.PgOutputMessage.TupleValue.text("evt-1"),
                        PgOutputMessageDecoder.PgOutputMessage.TupleValue.text("{\"type\":\"OrderCreated\"}"),
                        PgOutputMessageDecoder.PgOutputMessage.TupleValue.text("123")
                ))
        ));

        assertThat(changes).hasSize(1);
        var change = changes.getFirst();
        assertThat(change.kind()).isEqualTo("insert");
        assertThat(change.relationId()).isEqualTo(7);
        assertThat(change.schema()).isEqualTo("public");
        assertThat(change.table()).isEqualTo("orders_events");
        assertThat(change.transactionId()).isEqualTo(42);
        assertThat(change.transactionCommitTimestampMicros()).isEqualTo(200L);
        assertThat(change.keyColumns()).containsExactly("event_id");
        assertThat(change.columnTypeOids()).containsEntry("event_id", 2950);
        assertThat(change.values().get("event_id").kind()).isEqualTo(PgOutputRowChange.Kind.TEXT);
        assertThat(change.values().get("event_id").textValue()).isEqualTo("evt-1");
        assertThat(change.values().get("global_order").textValue()).isEqualTo("123");
    }

    @Test
    void commit_clears_transaction_context_for_following_changes() {
        rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.BeginMessage(100L, 200L, 42));
        rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.CommitMessage((byte) 0, 101L, 102L, 201L));
        rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.RelationMessage(
                8,
                "public",
                "orders_events",
                'd',
                List.of(new PgOutputMessageDecoder.PgOutputMessage.RelationColumn(true, "event_id", 2950, -1))
        ));

        var changes = rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.InsertMessage(
                8,
                new PgOutputMessageDecoder.PgOutputMessage.TupleData(List.of(
                        PgOutputMessageDecoder.PgOutputMessage.TupleValue.text("evt-2")
                ))
        ));

        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().transactionId()).isNull();
        assertThat(changes.getFirst().transactionCommitTimestampMicros()).isNull();
    }

    @Test
    void ignores_non_insert_messages() {
        rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.BeginMessage(100L, 200L, 42));
        rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.RelationMessage(
                8,
                "public",
                "orders_events",
                'd',
                List.of(new PgOutputMessageDecoder.PgOutputMessage.RelationColumn(true, "event_id", 2950, -1))
        ));

        var changes = rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.IgnoredMessage('U'));

        assertThat(changes).isEmpty();
    }

    @Test
    void fails_if_insert_arrives_before_relation_metadata() {
        assertThatThrownBy(() -> rowChangeDecoder.accept(new PgOutputMessageDecoder.PgOutputMessage.InsertMessage(
                9,
                new PgOutputMessageDecoder.PgOutputMessage.TupleData(List.of(
                        PgOutputMessageDecoder.PgOutputMessage.TupleValue.text("evt-3")
                ))
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing cached pgoutput relation metadata");
    }
}
