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

package dk.trustworks.essentials.components.queue.jdbc;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.jdbi.v3.core.Handle;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

public final class JdbcSqlExecutionSupport {
    private JdbcSqlExecutionSupport() {
    }

    public static void executeTableNameSql(String sharedQueueTableName, String sql, Handle handle) {
        PostgresqlUtil.checkIsValidTableOrColumnName(sharedQueueTableName);
        handle.execute(bind(sql, arg("tableName", sharedQueueTableName)));
    }
}
