/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.eventlog.migration;

import java.sql.*;
import java.util.*;

/**
 * What the catalog says about one old table (design phase 9 §3.1): whether it exists, its columns' types, which overrule
 * the plan, and its {@code global_order} identity sequence.
 *
 * @param exists            whether the table exists
 * @param columnTypes       {@code format_type} of each column, by column name
 * @param sequence          the identity sequence's qualified name, or {@code null} if there is none
 * @param sequenceCache     its cache size, or 0 if there is no sequence
 * @param sequenceLastValue its {@code last_value}, or 0 if nothing was ever drawn
 */
record SourceCatalog(boolean exists, Map<String, String> columnTypes, String sequence, long sequenceCache, long sequenceLastValue) {
    static SourceCatalog read(Connection connection, SourceTable table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, table.tableName());
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                if (!resultSet.getBoolean(1)) {
                    return new SourceCatalog(false, Map.of(), null, 0, 0);
                }
            }
        }
        var types = new LinkedHashMap<String, String>();
        try (var statement = connection.prepareStatement("SELECT attname, format_type(atttypid, atttypmod) FROM pg_attribute " +
                                                                 "WHERE attrelid = to_regclass(?) AND attnum > 0 AND NOT attisdropped ORDER BY attnum")) {
            statement.setString(1, table.tableName());
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    types.put(resultSet.getString(1), resultSet.getString(2));
                }
            }
        }
        String sequence = null;
        if (types.containsKey(table.columns().globalOrder())) {
            try (var statement = connection.prepareStatement("SELECT pg_get_serial_sequence(?, ?)")) {
                statement.setString(1, table.tableName());
                statement.setString(2, table.columns().globalOrder());
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    sequence = resultSet.getString(1);
                }
            }
        }
        long cache = 0, lastValue = 0;
        if (sequence != null) {
            try (var statement = connection.prepareStatement("SELECT s.cache_size, coalesce(s.last_value, 0) FROM pg_sequences s " +
                                                                     "WHERE quote_ident(s.schemaname) || '.' || quote_ident(s.sequencename) = ?")) {
                statement.setString(1, sequence);
                try (var resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        cache = resultSet.getLong(1);
                        lastValue = resultSet.getLong(2);
                    }
                }
            }
        }
        return new SourceCatalog(true, types, sequence, cache, lastValue);
    }

    boolean isUuid(String column) {
        return "uuid".equals(columnTypes.get(column));
    }

    static boolean isIdType(String type) {
        return type != null && (type.equals("uuid") || type.equals("text") || type.startsWith("character varying"));
    }

    static boolean isJsonType(String type) {
        return "json".equals(type) || "jsonb".equals(type);
    }
}
