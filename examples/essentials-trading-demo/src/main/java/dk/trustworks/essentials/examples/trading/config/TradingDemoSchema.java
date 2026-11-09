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

package dk.trustworks.essentials.examples.trading.config;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.jdbi.v3.core.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Runs the demo's own start-up DDL - the projections' and helper tables' {@code CREATE ... IF NOT EXISTS} - behind the
 * framework's bootstrap advisory lock ({@link PostgresqlUtil#acquireBootstrapLock(Handle)}).
 * <p>
 * {@code IF NOT EXISTS} is not safe against itself: two instances starting together on an empty database both find the
 * table missing, and the second {@code CREATE TABLE} fails on {@code pg_type_typname_nsp_index} - which ended the second
 * instance's start. The lock is held for the statement's transaction, so each statement runs with the lock taken first,
 * in the same transaction.
 */
public final class TradingDemoSchema {
    private TradingDemoSchema() {
    }

    /**
     * Run a DDL statement in the given handle's transaction, after taking the bootstrap lock in it.
     *
     * @param handle the handle of the unit of work the statement runs in - must be in a transaction, or the lock is
     *               released as soon as it is taken
     * @param sql    the DDL statement
     * @return the update count
     */
    public static int ddl(Handle handle, String sql) {
        requireNonNull(handle, "No handle provided");
        requireNonNull(sql, "No sql provided");
        PostgresqlUtil.acquireBootstrapLock(handle);
        return handle.execute(sql);
    }

    /**
     * Run a DDL statement in a transaction of its own on the {@link JdbcTemplate}'s data source, after taking the
     * bootstrap lock in it. A plain {@code JdbcTemplate.execute} runs in autocommit, where a transaction-scoped lock would
     * be released before the statement.
     *
     * @param jdbcTemplate supplies the data source
     * @param sql          the DDL statement
     */
    public static void ddl(JdbcTemplate jdbcTemplate, String sql) {
        requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        requireNonNull(sql, "No sql provided");
        Jdbi.create(requireNonNull(jdbcTemplate.getDataSource(), "jdbcTemplate has no DataSource"))
            .useTransaction(handle -> ddl(handle, sql));
    }
}
