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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * Decoder for PostgreSQL {@code pgoutput} logical replication messages.
 * <p>
 * Initial implementation supports protocol version 1 and the message types
 * needed to build the first event-store path: {@code Begin}, {@code Relation},
 * {@code Insert}, and {@code Commit}. Other message types are surfaced as
 * ignored messages so the CDC pipeline can safely skip non-insert traffic.
 */
public final class PgOutputMessageDecoder {
    private final int protocolVersion;

    public PgOutputMessageDecoder(int protocolVersion) {
        requireTrue(protocolVersion == 1, "Only pgoutput protocol version 1 is currently supported");
        this.protocolVersion = protocolVersion;
    }

    public PgOutputMessage decode(byte[] messageBytes) {
        return decode(ByteBuffer.wrap(messageBytes));
    }

    public PgOutputMessage decode(ByteBuffer buffer) {
        requireTrue(buffer.remaining() > 0, "pgoutput message cannot be empty");

        char type = (char) buffer.get();
        return switch (type) {
            case 'B' -> decodeBegin(buffer);
            case 'C' -> decodeCommit(buffer);
            case 'R' -> decodeRelation(buffer);
            case 'I' -> decodeInsert(buffer);
            default -> new PgOutputMessage.IgnoredMessage(type);
        };
    }

    private PgOutputMessage.BeginMessage decodeBegin(ByteBuffer buffer) {
        return new PgOutputMessage.BeginMessage(
                buffer.getLong(),
                buffer.getLong(),
                buffer.getInt()
        );
    }

    private PgOutputMessage.CommitMessage decodeCommit(ByteBuffer buffer) {
        byte flags = buffer.get();
        return new PgOutputMessage.CommitMessage(
                flags,
                buffer.getLong(),
                buffer.getLong(),
                buffer.getLong()
        );
    }

    private PgOutputMessage.RelationMessage decodeRelation(ByteBuffer buffer) {
        int relationId = buffer.getInt();
        String namespace = readCString(buffer);
        String relationName = readCString(buffer);
        char replicaIdentity = (char) buffer.get();
        int columns = Short.toUnsignedInt(buffer.getShort());
        var relationColumns = new ArrayList<PgOutputMessage.RelationColumn>(columns);
        for (int i = 0; i < columns; i++) {
            byte flags = buffer.get();
            String name = readCString(buffer);
            int dataTypeOid = buffer.getInt();
            int typeModifier = buffer.getInt();
            relationColumns.add(new PgOutputMessage.RelationColumn(
                    (flags & 0x01) == 0x01,
                    name,
                    dataTypeOid,
                    typeModifier
            ));
        }
        return new PgOutputMessage.RelationMessage(relationId, namespace, relationName, replicaIdentity, relationColumns);
    }

    private PgOutputMessage.InsertMessage decodeInsert(ByteBuffer buffer) {
        int relationId = buffer.getInt();
        char tupleKind = (char) buffer.get();
        requireTrue(tupleKind == 'N', "pgoutput insert tuple kind must be 'N'");
        return new PgOutputMessage.InsertMessage(relationId, decodeTuple(buffer));
    }

    private PgOutputMessage.TupleData decodeTuple(ByteBuffer buffer) {
        int columns = Short.toUnsignedInt(buffer.getShort());
        var values = new ArrayList<PgOutputMessage.TupleValue>(columns);
        for (int i = 0; i < columns; i++) {
            char valueKind = (char) buffer.get();
            switch (valueKind) {
                case 'n' -> values.add(PgOutputMessage.TupleValue.nullValue());
                case 'u' -> values.add(PgOutputMessage.TupleValue.unchangedToast());
                case 't' -> {
                    int length = buffer.getInt();
                    byte[] valueBytes = new byte[length];
                    buffer.get(valueBytes);
                    values.add(PgOutputMessage.TupleValue.text(new String(valueBytes, StandardCharsets.UTF_8)));
                }
                case 'b' -> {
                    int length = buffer.getInt();
                    byte[] valueBytes = new byte[length];
                    buffer.get(valueBytes);
                    values.add(PgOutputMessage.TupleValue.binary(valueBytes));
                }
                default -> throw new IllegalArgumentException("Unsupported pgoutput tuple value kind '" + valueKind + "'");
            }
        }
        return new PgOutputMessage.TupleData(values);
    }

    private String readCString(ByteBuffer buffer) {
        byte[] bytes = readCStringBytes(buffer);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] readCStringBytes(ByteBuffer buffer) {
        int start = buffer.position();
        while (buffer.hasRemaining()) {
            if (buffer.get() == 0) {
                int end = buffer.position() - 1;
                int length = end - start;
                byte[] bytes = new byte[length];
                buffer.position(start);
                buffer.get(bytes);
                buffer.get(); // trailing NUL
                return bytes;
            }
        }
        throw new IllegalArgumentException("Expected null-terminated string in pgoutput message");
    }

    public sealed interface PgOutputMessage permits PgOutputMessage.BeginMessage,
                                                   PgOutputMessage.CommitMessage,
                                                   PgOutputMessage.RelationMessage,
                                                   PgOutputMessage.InsertMessage,
                                                   PgOutputMessage.IgnoredMessage {
        record BeginMessage(long finalLsn, long commitTimestampMicros, int transactionId) implements PgOutputMessage {
        }

        record CommitMessage(byte flags, long commitLsn, long endLsn, long commitTimestampMicros) implements PgOutputMessage {
        }

        record RelationMessage(int relationId,
                               String namespace,
                               String relationName,
                               char replicaIdentity,
                               List<RelationColumn> columns) implements PgOutputMessage {
        }

        record InsertMessage(int relationId, TupleData tupleData) implements PgOutputMessage {
        }

        record IgnoredMessage(char type) implements PgOutputMessage {
        }

        record RelationColumn(boolean key, String name, int dataTypeOid, int typeModifier) {
        }

        record TupleData(List<TupleValue> values) {
        }

        record TupleValue(Kind kind, String textValue, byte[] binaryValue) {
            public static TupleValue nullValue() {
                return new TupleValue(Kind.NULL, null, null);
            }

            public static TupleValue unchangedToast() {
                return new TupleValue(Kind.UNCHANGED_TOAST, null, null);
            }

            public static TupleValue text(String textValue) {
                return new TupleValue(Kind.TEXT, textValue, null);
            }

            public static TupleValue binary(byte[] binaryValue) {
                return new TupleValue(Kind.BINARY, null, binaryValue);
            }
        }

        enum Kind {
            NULL,
            UNCHANGED_TOAST,
            TEXT,
            BINARY
        }
    }
}
