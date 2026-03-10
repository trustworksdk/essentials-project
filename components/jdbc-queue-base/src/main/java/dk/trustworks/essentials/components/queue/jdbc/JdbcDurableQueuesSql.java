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

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;
import static java.util.Objects.requireNonNull;

public abstract class JdbcDurableQueuesSql {
    public enum IncludeMessages {
        ALL,
        DEAD_LETTER_MESSAGES,
        QUEUED_MESSAGES
    }

    protected final String sharedQueueTableName;

    protected JdbcDurableQueuesSql(String sharedQueueTableName) {
        PostgresqlUtil.checkIsValidTableOrColumnName(sharedQueueTableName);
        this.sharedQueueTableName = sharedQueueTableName;
    }

    public final String buildGetQueuedMessagesSql(IncludeMessages includeMessages) {
        requireNonNull(includeMessages, "No includeMessages provided");
        return bind("""
                    SELECT *
                    FROM {:tableName}
                    WHERE queue_name = :queueName
                    {:includeMessages}
                    {:pagination}
                    """,
                    arg("tableName", sharedQueueTableName),
                    arg("includeMessages", resolveIncludeMessagesSql(includeMessages)),
                    arg("pagination", getGetQueuedMessagesPaginationSql()));
    }

    public final String getGetQueuedMessageSql() {
        return bind("""
                    SELECT *
                    FROM {:tableName}
                    WHERE id = :id
                      AND is_dead_letter_message = :isDeadLetterMessage
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    protected abstract String getGetQueuedMessagesPaginationSql();

    protected abstract String getDeadLetterTrueSqlValue();

    protected abstract String getDeadLetterFalseSqlValue();

    private String resolveIncludeMessagesSql(IncludeMessages includeMessages) {
        return switch (includeMessages) {
            case ALL -> "";
            case DEAD_LETTER_MESSAGES -> "AND is_dead_letter_message = " + getDeadLetterTrueSqlValue() + "\n";
            case QUEUED_MESSAGES -> "AND is_dead_letter_message = " + getDeadLetterFalseSqlValue() + "\n";
        };
    }
}
