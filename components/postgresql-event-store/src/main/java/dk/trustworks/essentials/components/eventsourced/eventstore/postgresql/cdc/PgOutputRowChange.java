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

import java.util.List;
import java.util.Map;

/**
 * Canonical named-row representation for decoded {@code pgoutput} DML changes.
 */
public record PgOutputRowChange(
        String kind,
        int relationId,
        String schema,
        String table,
        Integer transactionId,
        Long transactionCommitTimestampMicros,
        Map<String, PgOutputValue> values,
        Map<String, Integer> columnTypeOids,
        List<String> keyColumns
) {
    public record PgOutputValue(Kind kind, String textValue, byte[] binaryValue) {
        public static PgOutputValue nullValue() {
            return new PgOutputValue(Kind.NULL, null, null);
        }

        public static PgOutputValue unchangedToast() {
            return new PgOutputValue(Kind.UNCHANGED_TOAST, null, null);
        }

        public static PgOutputValue text(String textValue) {
            return new PgOutputValue(Kind.TEXT, textValue, null);
        }

        public static PgOutputValue binary(byte[] binaryValue) {
            return new PgOutputValue(Kind.BINARY, null, binaryValue);
        }
    }

    public enum Kind {
        NULL,
        UNCHANGED_TOAST,
        TEXT,
        BINARY
    }
}
