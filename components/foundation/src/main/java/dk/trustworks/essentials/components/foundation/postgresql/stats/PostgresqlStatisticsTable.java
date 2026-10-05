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


package dk.trustworks.essentials.components.foundation.postgresql.stats;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * A table whose PostgreSQL statistics are reported by
 * {@link dk.trustworks.essentials.components.foundation.postgresql.api.PostgresqlTableStatisticsApi}, and the
 * section it is reported under.
 * <p>
 * The section is a stable identifier, such as {@link #SECTION_DURABLE_QUEUES}, that a client groups and titles
 * tables by. The constants cover the Essentials components; any other non-blank value is accepted, so an
 * application can report its own tables under a section of its own.
 *
 * @param section   the section the table is reported under
 * @param tableName the table name, as configured for the component that owns it
 * @see PostgresqlStatisticsTableProvider
 */
public record PostgresqlStatisticsTable(String section, String tableName) {

    /** Event-stream tables, one per aggregate type */
    public static final String SECTION_EVENT_STORE        = "event-store";
    /** Durable subscription resume points and the subscription gap tables */
    public static final String SECTION_SUBSCRIPTIONS      = "subscriptions";
    /** The CDC inbox */
    public static final String SECTION_CDC                = "cdc";
    /** The shared table of the default durable queues engine */
    public static final String SECTION_DURABLE_QUEUES     = "durable-queues";
    /** The tables of the shard-owned queue engine */
    public static final String SECTION_SHARD_OWNED_QUEUES = "shard-owned-queues";
    /** The fenced lock table */
    public static final String SECTION_FENCED_LOCKS       = "fenced-locks";
    /** Aggregate snapshots, snapshot jobs, closing-books generations and archives */
    public static final String SECTION_AGGREGATES         = "aggregates";
    /** Essentials' own bookkeeping, such as the schema history and the scheduler's executor jobs */
    public static final String SECTION_INFRASTRUCTURE     = "infrastructure";

    public PostgresqlStatisticsTable {
        requireNonNull(section, "No section provided");
        requireTrue(!section.isBlank(), "section must not be blank");
        requireNonNull(tableName, "No tableName provided");
        PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
    }
}
