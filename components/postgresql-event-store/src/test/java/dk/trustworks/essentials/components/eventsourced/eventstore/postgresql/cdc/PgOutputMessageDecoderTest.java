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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class PgOutputMessageDecoderTest {
    private final PgOutputMessageDecoder decoder = new PgOutputMessageDecoder(1);

    @Test
    void decodes_begin_message() {
        byte[] message = bytes(out -> {
            out.write('B');
            writeLong(out, 123L);
            writeLong(out, 456L);
            writeInt(out, 789);
        });

        var decoded = decoder.decode(message);
        assertThat(decoded).isInstanceOf(PgOutputMessageDecoder.PgOutputMessage.BeginMessage.class);
        var begin = (PgOutputMessageDecoder.PgOutputMessage.BeginMessage) decoded;
        assertThat(begin.finalLsn()).isEqualTo(123L);
        assertThat(begin.commitTimestampMicros()).isEqualTo(456L);
        assertThat(begin.transactionId()).isEqualTo(789);
    }

    @Test
    void decodes_relation_message() {
        byte[] message = bytes(out -> {
            out.write('R');
            writeInt(out, 42);
            writeCString(out, "public");
            writeCString(out, "orders_events");
            out.write('d');
            writeShort(out, 2);

            out.write(1);
            writeCString(out, "event_id");
            writeInt(out, 2950);
            writeInt(out, -1);

            out.write(0);
            writeCString(out, "event_payload");
            writeInt(out, 3802);
            writeInt(out, -1);
        });

        var decoded = decoder.decode(message);
        assertThat(decoded).isInstanceOf(PgOutputMessageDecoder.PgOutputMessage.RelationMessage.class);
        var relation = (PgOutputMessageDecoder.PgOutputMessage.RelationMessage) decoded;
        assertThat(relation.relationId()).isEqualTo(42);
        assertThat(relation.namespace()).isEqualTo("public");
        assertThat(relation.relationName()).isEqualTo("orders_events");
        assertThat(relation.replicaIdentity()).isEqualTo('d');
        assertThat(relation.columns()).hasSize(2);
        assertThat(relation.columns().get(0).key()).isTrue();
        assertThat(relation.columns().get(0).name()).isEqualTo("event_id");
        assertThat(relation.columns().get(1).key()).isFalse();
        assertThat(relation.columns().get(1).name()).isEqualTo("event_payload");
    }

    @Test
    void decodes_insert_message_with_text_null_and_binary_tuple_values() {
        byte[] message = bytes(out -> {
            out.write('I');
            writeInt(out, 42);
            out.write('N');
            writeShort(out, 4);

            out.write('t');
            writeBytes(out, "evt-1".getBytes(StandardCharsets.UTF_8));

            out.write('n');

            out.write('u');

            out.write('b');
            writeBytes(out, new byte[]{1, 2, 3});
        });

        var decoded = decoder.decode(message);
        assertThat(decoded).isInstanceOf(PgOutputMessageDecoder.PgOutputMessage.InsertMessage.class);
        var insert = (PgOutputMessageDecoder.PgOutputMessage.InsertMessage) decoded;
        assertThat(insert.relationId()).isEqualTo(42);
        assertThat(insert.tupleData().values()).hasSize(4);
        assertThat(insert.tupleData().values().get(0).kind()).isEqualTo(PgOutputMessageDecoder.PgOutputMessage.Kind.TEXT);
        assertThat(insert.tupleData().values().get(0).textValue()).isEqualTo("evt-1");
        assertThat(insert.tupleData().values().get(1).kind()).isEqualTo(PgOutputMessageDecoder.PgOutputMessage.Kind.NULL);
        assertThat(insert.tupleData().values().get(2).kind()).isEqualTo(PgOutputMessageDecoder.PgOutputMessage.Kind.UNCHANGED_TOAST);
        assertThat(insert.tupleData().values().get(3).kind()).isEqualTo(PgOutputMessageDecoder.PgOutputMessage.Kind.BINARY);
        assertThat(insert.tupleData().values().get(3).binaryValue()).containsExactly(1, 2, 3);
    }

    @Test
    void surfaces_non_insert_messages_as_ignored() {
        byte[] message = bytes(out -> {
            out.write('U');
            writeInt(out, 42);
        });

        var decoded = decoder.decode(message);
        assertThat(decoded).isInstanceOf(PgOutputMessageDecoder.PgOutputMessage.IgnoredMessage.class);
        var ignored = (PgOutputMessageDecoder.PgOutputMessage.IgnoredMessage) decoded;
        assertThat(ignored.type()).isEqualTo('U');
    }

    @Test
    void decodes_commit_message() {
        byte[] message = bytes(out -> {
            out.write('C');
            out.write(0);
            writeLong(out, 111L);
            writeLong(out, 222L);
            writeLong(out, 333L);
        });

        var decoded = decoder.decode(message);
        assertThat(decoded).isInstanceOf(PgOutputMessageDecoder.PgOutputMessage.CommitMessage.class);
        var commit = (PgOutputMessageDecoder.PgOutputMessage.CommitMessage) decoded;
        assertThat(commit.flags()).isZero();
        assertThat(commit.commitLsn()).isEqualTo(111L);
        assertThat(commit.endLsn()).isEqualTo(222L);
        assertThat(commit.commitTimestampMicros()).isEqualTo(333L);
    }

    @Test
    void rejects_unsupported_protocol_version() {
        assertThatThrownBy(() -> new PgOutputMessageDecoder(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protocol version 1");
    }

    @Test
    void unknown_message_type_is_ignored() {
        var decoded = decoder.decode(new byte[]{'Y'});
        assertThat(decoded).isInstanceOf(PgOutputMessageDecoder.PgOutputMessage.IgnoredMessage.class);
        assertThat(((PgOutputMessageDecoder.PgOutputMessage.IgnoredMessage) decoded).type()).isEqualTo('Y');
    }

    private static byte[] bytes(ThrowingConsumer<ByteArrayOutputStream> writer) {
        try {
            var out = new ByteArrayOutputStream();
            writer.accept(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4).putInt(value).array());
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(2).putShort((short) value).array());
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(8).putLong(value).array());
    }

    private static void writeCString(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] value) {
        writeInt(out, value.length);
        out.writeBytes(value);
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
