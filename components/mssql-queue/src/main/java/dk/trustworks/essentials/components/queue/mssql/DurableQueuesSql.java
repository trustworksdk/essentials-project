/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.queue.mssql;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.shared.MessageFormatter;

import java.util.*;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

/**
 * Helper class for SQL statements used by MsSqlDurableQueues.
 * 1:1 API with your PostgreSQL DurableQueuesSql, but emitting T-SQL.
 * <p>
 * Prereqs for MSSQL schema (types are suggestions):
 * - id:            NVARCHAR(64) PRIMARY KEY  (or UNIQUEIDENTIFIER)
 * - queue_name:    NVARCHAR(200) NOT NULL
 * - message_payload: NVARCHAR(MAX) NOT NULL      -- or VARBINARY(MAX)
 * - message_payload_type: NVARCHAR(100) NOT NULL
 * - added_ts / next_delivery_ts / delivery_ts: DATETIME2(3)
 * - total_attempts / redelivery_attempts: INT
 * - last_delivery_error: NVARCHAR(MAX) NULL
 * - is_being_delivered / is_dead_letter_message: BIT
 * - meta_data: NVARCHAR(MAX) NULL
 * - delivery_mode: NVARCHAR(32) NOT NULL
 * - key: NVARCHAR(255) NULL
 * - key_order: BIGINT
 * <p>
 * Recommended filtered indexes (MSSQL):
 * - CREATE INDEX IX_ready_unordered  ON {table}(queue_name, next_delivery_ts) INCLUDE(id) WHERE key IS NULL AND is_dead_letter_message = 0 AND is_being_delivered = 0;
 * - CREATE INDEX IX_ready_ordered    ON {table}(key, queue_name, key_order, next_delivery_ts) INCLUDE(id) WHERE key IS NOT NULL AND is_dead_letter_message = 0 AND is_being_delivered = 0;
 * - CREATE INDEX IX_ready_head       ON {table}(queue_name, key_order, next_delivery_ts) INCLUDE(id) WHERE key IS NOT NULL AND is_dead_letter_message = 0 AND is_being_delivered = 0;
 * - CREATE INDEX IX_next_msg         ON {table}(queue_name, is_dead_letter_message, is_being_delivered, next_delivery_ts);
 */
public class DurableQueuesSql {
    private final String sharedQueueTableName;

    /**
     * Creates a new DurableQueuesSql instance.
     *
     * @param sharedQueueTableName the name of the table that will contain all messages
     */
    public DurableQueuesSql(String sharedQueueTableName) {
        PostgresqlUtil.checkIsValidTableOrColumnName(sharedQueueTableName);
        this.sharedQueueTableName = sharedQueueTableName;
    }


    // ---------- Core: unordered ----------
    public String buildUnorderedSqlStatement() {
        return bind("""
            ;WITH cte_unordered AS (
              SELECT TOP (@limit) id
              FROM {:tableName} WITH (READPAST, UPDLOCK, ROWLOCK)
              WHERE queue_name = :queueName
                AND is_dead_letter_message = 0
                AND is_being_delivered     = 0
                AND next_delivery_ts <= :now
                AND [key] IS NULL
              ORDER BY next_delivery_ts ASC
            )
            UPDATE q
              SET total_attempts     = q.total_attempts + 1,
                  next_delivery_ts   = NULL,
                  is_being_delivered = 1,
                  delivery_ts        = :now
            OUTPUT inserted.*
            FROM {:tableName} q
            JOIN cte_unordered u ON q.id = u.id
            WHERE q.queue_name = :queueName;
        """, arg("tableName", sharedQueueTableName));
    }

    // ---------- Core: ordered (with optional NOT IN (<excludeKeys>)) ----------
    public String buildOrderedSqlStatement(boolean hasExclusiveKeys) {
        return bind("""
            ;WITH cte_ordered AS (
              SELECT TOP (@limit) id
              FROM {:tableName} q WITH (READPAST, UPDLOCK, ROWLOCK)
              WHERE queue_name = :queueName
                AND is_dead_letter_message = 0
                AND is_being_delivered     = 0
                AND next_delivery_ts      <= :now
                AND [key] IS NOT NULL
                -- full per-key barrier
                AND NOT EXISTS (
                  SELECT 1
                  FROM {:tableName} q2
                  WHERE q2.[key] = q.[key]
                    AND q2.queue_name = q.queue_name
                    AND q2.key_order < q.key_order
                )
                {:excludeKeys}
              ORDER BY key_order ASC, next_delivery_ts ASC
            )
            UPDATE q
              SET total_attempts     = q.total_attempts + 1,
                  next_delivery_ts   = NULL,
                  is_being_delivered = 1,
                  delivery_ts        = :now
            OUTPUT inserted.*
            FROM {:tableName} q
            JOIN cte_ordered o ON q.id = o.id
            WHERE q.queue_name = :queueName;
        """,
                    arg("tableName", sharedQueueTableName),
                    arg("excludeKeys", hasExclusiveKeys ? "AND [key] NOT IN (<excludeKeys>)" : ""));
    }

    // ---------- Core: get next ready (ordered first, then by ts) ----------
    public String buildGetNextMessageReadyForDeliverySqlStatement(java.util.Collection<String> excludeOrderedMessagesWithKey) {
        var excludeKeysLimitSql = "";
        var excluded = excludeOrderedMessagesWithKey != null ? excludeOrderedMessagesWithKey : java.util.List.<String>of();
        if (!excluded.isEmpty()) excludeKeysLimitSql = "AND [key] NOT IN (<excludedKeys>)\n";

        return bind("""
            ;WITH queued_message_ready_for_delivery AS (
              SELECT TOP (@limit) q1.id
              FROM {:tableName} q1 WITH (READPAST, UPDLOCK, ROWLOCK)
              WHERE q1.queue_name = :queueName
                AND q1.is_dead_letter_message = 0
                AND q1.is_being_delivered     = 0
                AND q1.next_delivery_ts <= :now
                AND NOT EXISTS (
                  SELECT 1
                  FROM {:tableName} q2
                  WHERE q2.[key]       = q1.[key]
                    AND q2.queue_name  = q1.queue_name
                    AND q2.key_order  < q1.key_order
                )
                {:excludeKeys}
              ORDER BY q1.key_order ASC, q1.next_delivery_ts ASC
            )
            UPDATE queued_message
              SET total_attempts   = queued_message.total_attempts + 1,
                  next_delivery_ts = NULL,
                  is_being_delivered = 1,
                  delivery_ts      = :now
            OUTPUT
              inserted.id,
              inserted.queue_name,
              inserted.message_payload,
              inserted.message_payload_type,
              inserted.added_ts,
              inserted.next_delivery_ts,
              inserted.delivery_ts,
              inserted.last_delivery_error,
              inserted.total_attempts,
              inserted.redelivery_attempts,
              inserted.is_dead_letter_message,
              inserted.is_being_delivered,
              inserted.meta_data,
              inserted.delivery_mode,
              inserted.[key],
              inserted.key_order
            FROM {:tableName} queued_message
            JOIN queued_message_ready_for_delivery r ON queued_message.id = r.id
            WHERE queued_message.queue_name = :queueName;
        """, arg("tableName", sharedQueueTableName), arg("excludeKeys", excludeKeysLimitSql));
    }

    // ---------- Batched: TVP-based (recommended) ----------
    /**
     * TVP version expects two table-valued parameters:
     *   @ActiveQueues (queue_name NVARCHAR(200), slots INT)
     *   @ExcludeKeys (queue_name NVARCHAR(200), [key] NVARCHAR(255))
     *
     * If you can’t send TVPs from your binder, see the fallback below.
     */
    public DurableQueuesSql.BatchedSqlResult buildBatchedSqlStatement(
           Map<QueueName, Set<String>> excludeKeysPerQueue,
           Map<QueueName, Integer> availableWorkerSlotsPerQueue,
           List<QueueName> activeQueues
                                                                     ) {
        // We’ll return the SQL and let the caller bind TVPs named @ActiveQueues and @ExcludeKeys.
        var sql = bind("""
            ;WITH ordered_rn AS (
              SELECT
                q.id,
                q.queue_name,
                ROW_NUMBER() OVER (
                  PARTITION BY q.queue_name
                  ORDER BY q.key_order, q.next_delivery_ts
                ) AS rn
              FROM {:tableName} q
              JOIN @ActiveQueues cfg ON cfg.queue_name = q.queue_name
              LEFT JOIN @ExcludeKeys ex
                ON ex.queue_name = q.queue_name AND ex.[key] = q.[key]
              WHERE q.is_dead_letter_message = 0
                AND q.is_being_delivered     = 0
                AND q.next_delivery_ts      <= :now
                AND q.[key] IS NOT NULL
                AND ex.[key] IS NULL
                AND NOT EXISTS (
                  SELECT 1
                  FROM {:tableName} q2
                  WHERE q2.queue_name = q.queue_name
                    AND q2.[key]      = q.[key]
                    AND q2.key_order  < q.key_order
                )
            ),
            unordered_rn AS (
              SELECT
                q.id,
                q.queue_name,
                ROW_NUMBER() OVER (
                  PARTITION BY q.queue_name
                  ORDER BY q.next_delivery_ts
                ) AS rn
              FROM {:tableName} q
              JOIN @ActiveQueues cfg ON cfg.queue_name = q.queue_name
              -- exclude applies only to ordered (keys); for unordered keys are NULL
              WHERE q.is_dead_letter_message = 0
                AND q.is_being_delivered     = 0
                AND q.next_delivery_ts      <= :now
                AND q.[key] IS NULL
            ),
            ordered_pick AS (
              SELECT orr.id
              FROM ordered_rn orr JOIN @ActiveQueues cfg ON orr.queue_name = cfg.queue_name
              WHERE orr.rn <= cfg.slots
            ),
            unordered_pick AS (
              SELECT unr.id
              FROM unordered_rn unr JOIN @ActiveQueues cfg ON unr.queue_name = cfg.queue_name
              WHERE unr.rn <= cfg.slots
            ),
            candidates AS (
              SELECT id FROM ordered_pick
              UNION ALL
              SELECT id FROM unordered_pick
            ),
            locked AS (
              SELECT q.id
              FROM {:tableName} q
              JOIN candidates c ON q.id = c.id
              WITH (READPAST, UPDLOCK, ROWLOCK)
            )
            UPDATE dq
              SET total_attempts     = dq.total_attempts + 1,
                  next_delivery_ts   = NULL,
                  is_being_delivered = 1,
                  delivery_ts        = :now
            OUTPUT inserted.*
            FROM {:tableName} dq
            JOIN locked l ON dq.id = l.id;
        """, arg("tableName", sharedQueueTableName));

        // Your original BatchedSqlResult carries “values/list bindings”.
        // For MSSQL + TVPs, just signal the TVP names; actual TVP objects must be bound by your JDBC layer.
        var single = new java.util.HashMap<String,String>();
        var lists  = new java.util.HashMap<String, java.util.Collection<String>>();
        // (left intentionally empty; bind TVPs externally)
        return new DurableQueuesSql.BatchedSqlResult(sql, single, lists);
    }

    // ---------- Batched: simple fallback (no per-queue excludes) ----------
    public String buildBatchedSqlStatementNoExcludes() {
        return bind("""
            ;WITH queue_config(queue_name, slots) AS (
              -- pass as VALUES (:q0, :s0), (:q1, :s1), ...
              {:values}
            ),
            ordered_rn AS (
              SELECT q.id, q.queue_name,
                     ROW_NUMBER() OVER (PARTITION BY q.queue_name ORDER BY q.key_order, q.next_delivery_ts) rn
              FROM {:tableName} q JOIN queue_config cfg ON cfg.queue_name = q.queue_name
              WHERE q.is_dead_letter_message = 0 AND q.is_being_delivered = 0
                AND q.next_delivery_ts <= :now AND q.[key] IS NOT NULL
                AND NOT EXISTS (
                  SELECT 1 FROM {:tableName} q2
                  WHERE q2.queue_name=q.queue_name AND q2.[key]=q.[key] AND q2.key_order<q.key_order
                )
            ),
            unordered_rn AS (
              SELECT q.id, q.queue_name,
                     ROW_NUMBER() OVER (PARTITION BY q.queue_name ORDER BY q.next_delivery_ts) rn
              FROM {:tableName} q JOIN queue_config cfg ON cfg.queue_name = q.queue_name
              WHERE q.is_dead_letter_message = 0 AND q.is_being_delivered = 0
                AND q.next_delivery_ts <= :now AND q.[key] IS NULL
            ),
            ordered_pick AS (SELECT id FROM ordered_rn orr JOIN queue_config cfg ON orr.queue_name=cfg.queue_name WHERE rn <= cfg.slots),
            unordered_pick AS (SELECT id FROM unordered_rn unr JOIN queue_config cfg ON unr.queue_name=cfg.queue_name WHERE rn <= cfg.slots),
            candidates AS (SELECT id FROM ordered_pick UNION ALL SELECT id FROM unordered_pick),
            locked AS (
              SELECT q.id
              FROM {:tableName} q
              JOIN candidates c ON q.id = c.id
              WITH (READPAST, UPDLOCK, ROWLOCK)
            )
            UPDATE dq
              SET total_attempts     = dq.total_attempts + 1,
                  next_delivery_ts   = NULL,
                  is_being_delivered = 1,
                  delivery_ts        = :now
            OUTPUT inserted.*
            FROM {:tableName} dq
            JOIN locked l ON dq.id = l.id;
        """, arg("tableName", sharedQueueTableName));
    }

    // ---------- Maintenance / counts / misc ----------
    public String getResetMessagesStuckBeingDeliveredSql() {
        return bind("""
            UPDATE {:tableName}
            SET is_being_delivered = 0,
                delivery_ts        = NULL,
                redelivery_attempts = redelivery_attempts + 1,
                next_delivery_ts   = :now,
                last_delivery_error = :error
            WHERE is_being_delivered = 1
              AND delivery_ts <= :threshold;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getHasOrderedMessageQueuedForKeySql() {
        return bind("""
            SELECT COUNT(*) FROM {:tableName}
            WHERE queue_name = :queueName
              AND [key] = :key
              AND delivery_mode = 'IN_ORDER';
        """, arg("tableName", sharedQueueTableName));
    }

    public String getGetTotalMessagesQueuedForSql() {
        return bind("""
            SELECT COUNT(*) FROM {:tableName}
            WHERE queue_name = :queueName
              AND is_dead_letter_message = 0;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getQueuedMessageCountsForSql() {
        return bind("""
            SELECT
              SUM(CASE WHEN is_dead_letter_message = 0 THEN 1 ELSE 0 END) AS regular_count,
              SUM(CASE WHEN is_dead_letter_message = 1 THEN 1 ELSE 0 END) AS dead_letter_count
            FROM {:tableName}
            WHERE queue_name = :queueName;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getGetTotalDeadLetterMessagesQueuedForSql() {
        return bind("""
            SELECT COUNT(*) FROM {:tableName}
            WHERE queue_name = :queueName
              AND is_dead_letter_message = 1;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getPurgeQueueSql() {
        return bind("DELETE FROM {:tableName} WHERE queue_name = :queueName;",
                    arg("tableName", sharedQueueTableName));
    }

    public String getQueryForMessagesSoonReadyForDeliverySql() {
        return bind("""
            SELECT TOP (@pageSize) id, added_ts, next_delivery_ts
            FROM {:tableName}
            WHERE queue_name = :queueName
              AND is_dead_letter_message = 0
              AND is_being_delivered     = 0
              AND next_delivery_ts > :now
            ORDER BY next_delivery_ts ASC;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getResetMessagesStuckBeingDeliveredAcrossMultipleQueuesSql() {
        return bind("""
            UPDATE {:tableName}
            SET is_being_delivered = 0,
                delivery_ts        = NULL,
                redelivery_attempts = redelivery_attempts + 1,
                next_delivery_ts   = :now,
                last_delivery_error = :error
            WHERE is_being_delivered = 1
              AND delivery_ts <= :threshold
              AND queue_name IN (<queueNames>);
        """, arg("tableName", sharedQueueTableName));
    }


    public String getCreateQueueTableSql() {
        return bind("""
            IF OBJECT_ID(N'{:tableName}', N'U') IS NULL
            BEGIN
              CREATE TABLE {:tableName} (
                id                     NVARCHAR(64) NOT NULL PRIMARY KEY,
                queue_name             NVARCHAR(255) NOT NULL,
                message_payload        NVARCHAR(MAX) NOT NULL,
                message_payload_type   NVARCHAR(255) NOT NULL,
                added_ts               DATETIMEOFFSET(3) NOT NULL,
                next_delivery_ts       DATETIMEOFFSET(3) NULL,
                delivery_ts            DATETIMEOFFSET(3) NULL,
                total_attempts         INT NOT NULL DEFAULT 0,
                redelivery_attempts    INT NOT NULL DEFAULT 0,
                last_delivery_error    NVARCHAR(MAX) NULL,
                is_being_delivered     BIT NOT NULL DEFAULT 0,
                is_dead_letter_message BIT NOT NULL DEFAULT 0,
                meta_data              NVARCHAR(MAX) NULL,
                delivery_mode          NVARCHAR(32) NOT NULL,
                [key]                  NVARCHAR(255) NULL,
                key_order              BIGINT NOT NULL DEFAULT (-1)
              );
            END
        """, arg("tableName", sharedQueueTableName));
    }

    public String getCreateOrderedMessageIndexSql() {
        return bind("""
            IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_{:tableName}_ordered_msg' AND object_id = OBJECT_ID(N'{:tableName}'))
              CREATE INDEX idx_{:tableName}_ordered_msg ON {:tableName} (queue_name, [key], key_order);
        """, arg("tableName", sharedQueueTableName));
    }

    public String getCreateNextMessageIndexSql() {
        return bind("""
            IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_{:tableName}_next_msg' AND object_id = OBJECT_ID(N'{:tableName}'))
              CREATE INDEX idx_{:tableName}_next_msg ON {:tableName} (queue_name, is_dead_letter_message, is_being_delivered, next_delivery_ts);
        """, arg("tableName", sharedQueueTableName));
    }

    public String getCreateNextReadyMessageIndexSql() {
        return bind("""
            IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_{:tableName}_ready' AND object_id = OBJECT_ID(N'{:tableName}'))
              CREATE INDEX idx_{:tableName}_ready
              ON {:tableName} (queue_name, next_delivery_ts, [key], key_order)
              WHERE is_dead_letter_message = 0 AND is_being_delivered = 0;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getCreateOrderedMessageReadyIndexSql() {
        return bind("""
            IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_{:tableName}_ordered_ready' AND object_id = OBJECT_ID(N'{:tableName}'))
              CREATE INDEX idx_{:tableName}_ordered_ready
                ON {:tableName} ([key], queue_name, key_order, next_delivery_ts)
                INCLUDE (id)
                WHERE [key] IS NOT NULL AND is_dead_letter_message = 0 AND is_being_delivered = 0;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getCreateUnorderedMessageReadyIndexSql() {
        return bind("""
            IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_{:tableName}_unordered_ready' AND object_id = OBJECT_ID(N'{:tableName}'))
              CREATE INDEX idx_{:tableName}_unordered_ready
                ON {:tableName} (queue_name, next_delivery_ts)
                INCLUDE (id)
                WHERE [key] IS NULL AND is_dead_letter_message = 0 AND is_being_delivered = 0;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getCreateOrderedMessageHeadIndexSql() {
        return bind("""
            IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_{:tableName}_ordered_head' AND object_id = OBJECT_ID(N'{:tableName}'))
              CREATE INDEX idx_{:tableName}_ordered_head
                ON {:tableName} (queue_name, key_order, next_delivery_ts)
                INCLUDE (id)
                WHERE [key] IS NOT NULL AND is_dead_letter_message = 0 AND is_being_delivered = 0;
        """, arg("tableName", sharedQueueTableName));
    }

    // ---------- Inserts / updates / deletes ----------
    public String getQueueNamesSql() {
        return bind("SELECT DISTINCT queue_name FROM {:tableName};", arg("tableName", sharedQueueTableName));
    }

    public String getQueueMessageSql() {
        // no ::jsonb casts on MSSQL
        return bind("""
            INSERT INTO {:tableName} (
              id, queue_name, message_payload, message_payload_type, added_ts, next_delivery_ts,
              last_delivery_error, is_dead_letter_message, meta_data, delivery_mode, [key], key_order
            ) VALUES (
              :id, :queueName, :message_payload, :message_payload_type, :addedTimestamp, :nextDeliveryTimestamp,
              :lastDeliveryError, :isDeadLetterMessage, :metaData, :deliveryMode, :key, :order
            );
        """, arg("tableName", sharedQueueTableName));
    }

    public String getQueueMessageSqlOptimized() {
        return bind("""
            INSERT INTO {:tableName} (
              id, queue_name, message_payload, message_payload_type, added_ts, next_delivery_ts,
              last_delivery_error, is_dead_letter_message, is_being_delivered, meta_data, delivery_mode, [key], key_order
            )
            SELECT
              :id, :queueName, :message_payload, :message_payload_type, :addedTimestamp, :nextDeliveryTimestamp,
              :lastDeliveryError,
              CASE
                WHEN :key IS NOT NULL AND EXISTS (
                  SELECT 1 FROM {:tableName} dq
                  WHERE dq.queue_name = :queueName
                    AND dq.[key]      = :key
                    AND dq.key_order  < :order
                    AND dq.is_dead_letter_message = 1
                )
                THEN 1 ELSE :isDeadLetterMessage END,
              0,
              :metaData, :deliveryMode, :key, :order;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getRetryMessageSql() {
        return bind("""
            UPDATE {:tableName}
            SET next_delivery_ts   = :nextDeliveryTimestamp,
                last_delivery_error = :lastDeliveryError,
                redelivery_attempts = redelivery_attempts + 1,
                is_being_delivered  = 0,
                delivery_ts         = NULL
            OUTPUT inserted.*
            WHERE id = :id;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getMarkAsDeadLetterMessageSql() {
        return bind("""
            UPDATE {:tableName}
            SET next_delivery_ts        = NULL,
                last_delivery_error     = :lastDeliveryError,
                is_dead_letter_message  = 1,
                is_being_delivered      = 0,
                delivery_ts             = NULL
            OUTPUT inserted.*
            WHERE id = :id AND is_dead_letter_message = 0;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getResurrectDeadLetterMessageSql() {
        return bind("""
            UPDATE {:tableName}
            SET next_delivery_ts        = :nextDeliveryTimestamp,
                is_dead_letter_message  = 0
            OUTPUT inserted.*
            WHERE id = :id AND is_dead_letter_message = 1;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getAcknowledgeMessageAsHandledSql() {
        // Delete only non-dead-letter; return deleted row if you need it
        return bind("""
            DELETE FROM {:tableName}
            OUTPUT deleted.*
            WHERE id = :id AND is_dead_letter_message = 0;
        """, arg("tableName", sharedQueueTableName));
    }

    public String getDeleteMessageSql() {
        return bind("DELETE FROM {:tableName} WHERE id = :id;", arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting the queue name for a queue entry ID.
     *
     * @return SQL statement for getting the queue name
     */
    public String getQueueNameForQueueEntryIdSql() {
        return bind("SELECT queue_name FROM {:tableName} WHERE id = :queueEntryId",
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting a queued message by ID.
     *
     * @return SQL statement for getting a queued message
     */
    public String getQueuedMessageByIdSql() {
        return bind("""
                    SELECT * FROM {:tableName} WHERE
                    id = :id AND
                    is_dead_letter_message = :isDeadLetterMessage
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * Result class for batched SQL statement containing both the SQL and parameter bindings
     */
    public static class BatchedSqlResult {
        private final String                          sql;
        private final Map<String, String>             singleValueBindings;
        private final Map<String, Collection<String>> listBindings;

        public BatchedSqlResult(String sql, Map<String, String> singleValueBindings, Map<String, Collection<String>> listBindings) {
            this.sql = sql;
            this.singleValueBindings = singleValueBindings;
            this.listBindings = listBindings;
        }

        public String getSql() {
            return sql;
        }

        public Map<String, String> getSingleValueBindings() {
            return singleValueBindings;
        }

        public Map<String, Collection<String>> getListBindings() {
            return listBindings;
        }
    }
}