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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * One aggregate type to move (design phase 9 §2.2): the aggregate type as the application names it (which also names
 * its event log tables, {@code AggregateTypeTables.of(aggregateType)}), the old engine's event table and its column
 * names. The old table and column names are validated with {@link PostgresqlUtil#checkIsValidTableOrColumnName(String)}
 * here; whether the aggregate type fits the event log's derived names is a pre-flight refusal (§3.1), not a constructor
 * failure, so that a plan can be checked whole.
 *
 * @param aggregateType the aggregate type
 * @param tableName     the old engine's event table, e.g. {@code orders_events}
 * @param columns       the old table's column names
 */
public record SourceTable(String aggregateType, String tableName, SourceColumns columns) {
    public SourceTable {
        requireNonBlank(aggregateType, "No aggregateType provided");
        requireNonBlank(tableName, "No tableName provided");
        requireNonNull(columns, "No columns provided");
        PostgresqlUtil.checkIsValidTableOrColumnName(tableName, "old event table of aggregate type '" + aggregateType + "'");
    }

    /**
     * @return a source table with the old engine's default column names
     */
    public static SourceTable of(String aggregateType, String tableName) {
        return new SourceTable(aggregateType, tableName, SourceColumns.defaults());
    }
}
