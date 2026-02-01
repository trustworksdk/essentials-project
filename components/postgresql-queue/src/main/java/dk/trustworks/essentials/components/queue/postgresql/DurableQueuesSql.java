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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import java.util.*;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

/**
 * Helper class for SQL statements used by PostgresqlDurableQueues.
 * This class contains methods for building SQL statements for various queue operations.
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

    /**
     * Builds an SQL statement for unordered message retrieval.
     *
     * @return A string representing the SQL statement to process queue messages
     * in an unordered manner based on the specified conditions.
     */
    public String buildUnorderedSqlStatement() {
        return bind("""
                      WITH cte_unordered AS (
                        SELECT id
                        FROM {:tableName} q
                        WHERE
                             queue_name = :queueName
                          AND is_dead_letter_message = FALSE
                          AND is_being_delivered     = FALSE
                          AND next_delivery_ts <= :now
                          AND key IS NULL
                        ORDER BY next_delivery_ts
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                      )
                      UPDATE {:tableName} q
                      SET
                        total_attempts       = q.total_attempts + 1,
                        next_delivery_ts     = NULL,
                        is_being_delivered   = TRUE,
                        delivery_ts          = :now
                      FROM cte_unordered u
                      WHERE q.id = u.id
                        AND q.queue_name = :queueName
                      RETURNING q.*;
                    """, arg("tableName", sharedQueueTableName));
    }

    /**
     * Constructs an SQL statement for ordering and updating a message queue.
     * The statement uses common table expressions (CTE) to first select eligible
     * messages based on specific conditions, and then updates and returns those messages.
     *
     * @param hasExclusiveKeys a boolean flag indicating whether exclusive keys
     *                         should be excluded from the query. If true, the SQL
     *                         will include an additional condition to exclude keys
     *                         from the provided exclusion list.
     * @return the constructed SQL statement as a String. The statement includes
     * logic for selecting, ordering, and updating messages in a queue table.
     */
    public String buildOrderedSqlStatement(boolean hasExclusiveKeys) {
        return bind("""
                    WITH cte_ordered AS (
                               SELECT id
                               FROM {:tableName} q
                               WHERE
                                    queue_name             = :queueName
                                AND is_dead_letter_message = FALSE
                                AND is_being_delivered     = FALSE
                                AND next_delivery_ts      <= :now
                    
                                AND key IS NOT NULL
                    
                                -- full per-key barrier:
                                AND NOT EXISTS (
                                  SELECT 1
                                  FROM {:tableName} q2
                                  WHERE q2.key        = q.key
                                    AND q2.queue_name = q.queue_name
                                    AND q2.key_order  < q.key_order
                                )
                                {:excludeKeys} 
                     ORDER BY key_order, next_delivery_ts
                                        LIMIT :limit
                                        FOR UPDATE SKIP LOCKED
                                      )
                                      UPDATE {:tableName} q
                                      SET
                                        total_attempts       = q.total_attempts + 1,
                                        next_delivery_ts     = NULL,
                                        is_being_delivered   = TRUE,
                                        delivery_ts          = :now
                                      FROM cte_ordered o
                                      WHERE q.id = o.id
                                        AND q.queue_name = :queueName
                                      RETURNING q.*;
                    """,
                    arg("tableName", sharedQueueTableName),
                    arg("excludeKeys", hasExclusiveKeys ? "AND key NOT IN (<excludeKeys>)" : ""));
    }

    /**
     * Builds an SQL statement for retrieving the next message ready for delivery.
     *
     * @param excludeOrderedMessagesWithKey Collection of keys to exclude from the query
     * @return SQL statement for retrieving the next message ready for delivery
     */
    public String buildGetNextMessageReadyForDeliverySqlStatement(Collection<String> excludeOrderedMessagesWithKey) {
        var excludeKeysLimitSql = "";
        var excludedKeys        = excludeOrderedMessagesWithKey != null ? excludeOrderedMessagesWithKey : List.of();
        if (!excludedKeys.isEmpty()) {
            excludeKeysLimitSql = "        AND key NOT IN (<excludedKeys>)\n";
        }

        return bind("""
                    WITH queued_message_ready_for_delivery AS (
                        SELECT id FROM {:tableName} q1
                        WHERE
                            queue_name = :queueName AND
                            is_dead_letter_message = FALSE AND
                            is_being_delivered = FALSE AND
                            next_delivery_ts <= :now AND
                            NOT EXISTS (SELECT 1 FROM {:tableName} q2 WHERE q2.key = q1.key AND q2.queue_name = q1.queue_name AND q2.key_order < q1.key_order)
                                {:excludeKeys}
                        ORDER BY key_order ASC, next_delivery_ts ASC
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                    )
                            UPDATE {:tableName} queued_message SET
                                total_attempts = queued_message.total_attempts + 1,
                                next_delivery_ts = NULL,
                                is_being_delivered = TRUE,
                                delivery_ts = :now
                            FROM queued_message_ready_for_delivery
                            WHERE queued_message.id = queued_message_ready_for_delivery.id
                            AND queued_message.queue_name = :queueName
                            RETURNING
                                queued_message.id,
                                queued_message.queue_name,
                                queued_message.message_payload,
                                queued_message.message_payload_type,
                                queued_message.added_ts,
                                queued_message.next_delivery_ts,
                                queued_message.delivery_ts,
                                queued_message.last_delivery_error,
                                queued_message.total_attempts,
                                queued_message.redelivery_attempts,
                                queued_message.is_dead_letter_message,
                                queued_message.is_being_delivered,
                                queued_message.meta_data,
                                queued_message.delivery_mode,
                                queued_message.key,
                                queued_message.key_order
                    """,
                    arg("tableName", sharedQueueTableName),
                    arg("excludeKeys", excludeKeysLimitSql));

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

    /**
     * Builds a batched SQL statement for retrieving messages from multiple queues - work in progress (doesn't handle competing consumers yet due to )
     * <p>
     * This method now uses parameterized queries to avoid SQL injection vulnerabilities
     * that could arise from string concatenation of user-provided keys.
     *
     * @param excludeKeysPerQueue          Map of queue names to sets of keys to exclude
     * @param availableWorkerSlotsPerQueue Map of queue names to available worker slots
     * @param activeQueues                 List of active queue names
     * @return BatchedSqlResult containing SQL statement and parameter bindings for retrieving messages from multiple queues
     */
    public BatchedSqlResult buildBatchedSqlStatement(Map<QueueName, Set<String>> excludeKeysPerQueue,
                                                     Map<QueueName, Integer> availableWorkerSlotsPerQueue,
                                                     List<QueueName> activeQueues) {
        var values              = new StringBuilder();
        var singleValueBindings = new HashMap<String, String>();
        var listBindings        = new HashMap<String, Collection<String>>();

        for (int i = 0; i < activeQueues.size(); i++) {
            var queueName                        = activeQueues.get(i);
            var availableWorkerSlotsForThisQueue = availableWorkerSlotsPerQueue.get(queueName);
            var excludedKeysForThisQueue         = excludeKeysPerQueue.getOrDefault(queueName, Collections.emptySet());

            // Add queue name parameter binding as single value
            singleValueBindings.put("queueName" + i, queueName.toString());

            // Only add parameter binding if there are keys to exclude
            // For empty collections, we'll use a different SQL approach
            if (!excludedKeysForThisQueue.isEmpty()) {
                listBindings.put("excludeKeys" + i, excludedKeysForThisQueue);
            }

            if (i > 0) values.append(",\n    ");
            values.append("(:queueName").append(i).append(", ")
                  .append(availableWorkerSlotsForThisQueue).append(", ")
                  .append(excludedKeysForThisQueue.isEmpty() ? "ARRAY[]::text[]" : ":excludeKeys" + i)
                  .append(")");
        }

        var sql = bind("""
                       WITH queue_config(queue_name, slots, exclude_keys) AS (
                           VALUES {:values}
                       ),
                       -- 2) Numbered ordered candidates
                           ordered_rn AS (
                             SELECT
                               q.id,
                               q.queue_name,
                               ROW_NUMBER() OVER (
                                 PARTITION BY q.queue_name
                                 ORDER BY q.key_order, q.next_delivery_ts
                               ) AS rn
                             FROM {:tableName} q
                             JOIN queue_config cfg USING(queue_name)
                             WHERE
                                  q.is_dead_letter_message = FALSE
                              AND q.is_being_delivered     = FALSE
                              AND q.next_delivery_ts      <= :now
                              AND q.key IS NOT NULL
                              AND NOT (q.key = ANY(cfg.exclude_keys))
                              AND NOT EXISTS (
                                SELECT 1
                                FROM {:tableName} q2
                                WHERE q2.queue_name = q.queue_name
                                  AND q2.key        = q.key
                                  AND q2.key_order  < q.key_order
                              )
                           ),
                       
                           -- 3) Numbered unordered candidates
                           unordered_rn AS (
                             SELECT
                               q.id,
                               q.queue_name,
                               ROW_NUMBER() OVER (
                                 PARTITION BY q.queue_name
                                 ORDER BY q.next_delivery_ts
                               ) AS rn
                             FROM {:tableName} q
                             JOIN queue_config cfg USING(queue_name)
                             WHERE
                                  q.is_dead_letter_message = FALSE
                              AND q.is_being_delivered     = FALSE
                              AND q.next_delivery_ts      <= :now
                              AND q.key IS NULL
                              AND NOT (q.key = ANY(cfg.exclude_keys))
                           ),
                       
                           -- 4) Pick up to cfg.slots from each
                           ordered_pick AS (
                             SELECT orr.id
                             FROM ordered_rn orr
                             JOIN queue_config cfg
                               ON orr.queue_name = cfg.queue_name
                             WHERE orr.rn <= cfg.slots
                           ),
                           unordered_pick AS (
                             SELECT unr.id
                             FROM unordered_rn unr
                             JOIN queue_config cfg
                               ON unr.queue_name = cfg.queue_name
                             WHERE unr.rn <= cfg.slots
                           ),
                       
                           -- 5) Union them *without locking*
                           candidates AS (
                             SELECT id FROM ordered_pick
                             UNION ALL
                             SELECT id FROM unordered_pick
                           ),
                       
                           -- 6) Now lock exactly those durable_queues rows
                           locked AS (
                             SELECT q.id
                             FROM {:tableName} q
                             JOIN candidates c
                               ON q.id = c.id
                             FOR UPDATE SKIP LOCKED
                           )
                       
                         -- 7) Finally, update & return the locked rows
                         UPDATE {:tableName} dq
                         SET
                           total_attempts     = dq.total_attempts + 1,
                           next_delivery_ts   = NULL,
                           is_being_delivered = TRUE,
                           delivery_ts        = :now
                         FROM locked l
                         WHERE dq.id = l.id
                         RETURNING dq.*;
                       """,
                       arg("tableName", sharedQueueTableName),
                       arg("values", values.toString()));

        return new BatchedSqlResult(sql, singleValueBindings, listBindings);
    }

    /**
     * SQL statement for resetting messages that are stuck being delivered.
     *
     * @return SQL statement for resetting stuck messages
     */
    public String getResetMessagesStuckBeingDeliveredSql() {
        return bind("""
                    UPDATE {:tableName} SET
                    is_being_delivered = FALSE,
                    delivery_ts = NULL,
                    redelivery_attempts = redelivery_attempts + 1,
                    next_delivery_ts = :now,
                    last_delivery_error = :error
                    WHERE is_being_delivered = TRUE
                    AND delivery_ts <= :threshold
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for has ordered message queued for key.
     *
     * @return SQL statement for has ordered message queued for key
     */
    public String getHasOrderedMessageQueuedForKeySql() {
        return bind("""
                    SELECT count(*) FROM {:tableName}
                    WHERE
                    queue_name = :queueName AND
                    key = :key AND
                    delivery_mode = 'IN_ORDER'
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for get total messages queued.
     *
     * @return SQL statement for get total messages queued
     */
    public String getGetTotalMessagesQueuedForSql() {
        return bind("""
                    SELECT count(*) FROM {:tableName}
                    WHERE
                    queue_name = :queueName AND
                    is_dead_letter_message = FALSE
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for get queued messages count.
     *
     * @return SQL statement for get queued messages count
     */
    public String getQueuedMessageCountsForSql() {
        return bind("""
                    SELECT
                    COUNT(*) FILTER (WHERE is_dead_letter_message = FALSE) AS regular_count,
                    COUNT(*) FILTER (WHERE is_dead_letter_message = TRUE) AS dead_letter_count
                    FROM {:tableName}
                    WHERE
                    queue_name = :queueName
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for get total dead letter messages queued for
     *
     * @return SQL statement get total dead letter messages queued for
     */
    public String getGetTotalDeadLetterMessagesQueuedForSql() {
        return bind("""
                    SELECT count(*) FROM {:tableName}
                    WHERE
                    queue_name = :queueName AND
                    is_dead_letter_message = TRUE
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for purge queued messages
     *
     * @return SQL statement purge queued messages
     */
    public String getPurgeQueueSql() {
        return bind("DELETE FROM {:tableName} WHERE queue_name = :queueName",
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for query for messages soon ready for delivery
     *
     * @return SQL statement query for messages soon ready for delivery
     */
    public String getQueryForMessagesSoonReadyForDeliverySql() {
        return bind("""
                    SELECT id, added_ts, next_delivery_ts FROM {:tableName}
                    WHERE queue_name = :queueName
                    AND is_dead_letter_message = FALSE
                    AND is_being_delivered = FALSE
                    AND next_delivery_ts > :now
                    ORDER BY next_delivery_ts ASC
                    LIMIT :pageSize
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for resetting messages that are stuck being delivered across multiple queues.
     *
     * @return SQL statement for resetting stuck messages across multiple queues
     */
    public String getResetMessagesStuckBeingDeliveredAcrossMultipleQueuesSql() {
        return bind("""
                    UPDATE {:tableName} SET
                    is_being_delivered = FALSE,
                    delivery_ts = NULL,
                    redelivery_attempts = redelivery_attempts + 1,
                    next_delivery_ts = :now,
                    last_delivery_error = :error
                    WHERE is_being_delivered = TRUE
                    AND delivery_ts <= :threshold
                    AND queue_name IN (<queueNames>)
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for initializing queue tables.
     *
     * @return SQL statement for creating the queue table
     */
    public String getCreateQueueTableSql() {
        return bind("""
                    CREATE TABLE IF NOT EXISTS {:tableName} (
                    id                     TEXT PRIMARY KEY,
                    queue_name             TEXT NOT NULL,
                    message_payload        JSONB NOT NULL,
                    message_payload_type   TEXT NOT NULL,
                    added_ts               TIMESTAMPTZ NOT NULL,
                    next_delivery_ts       TIMESTAMPTZ,
                    delivery_ts            TIMESTAMPTZ DEFAULT NULL,
                    total_attempts         INTEGER DEFAULT 0,
                    redelivery_attempts    INTEGER DEFAULT 0,
                    last_delivery_error    TEXT DEFAULT NULL,
                    is_being_delivered     BOOLEAN DEFAULT FALSE,
                    is_dead_letter_message BOOLEAN NOT NULL DEFAULT FALSE,
                    meta_data              JSONB DEFAULT NULL,
                    delivery_mode          TEXT NOT NULL,
                    key                    TEXT DEFAULT NULL,
                    key_order              BIGINT DEFAULT -1
                    )
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for creating the queue name, key and key order index.
     *
     * @return SQL statement for creating the queue name, key and key order index.
     */
    public String getCreateOrderedMessageIndexSql() {
        return bind("CREATE INDEX IF NOT EXISTS idx_{:tableName}_ordered_msg ON {:tableName} (queue_name, key, key_order)",
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for creating the queue name, is dead letter message, is being delivered and the next delivery timestamp index.
     *
     * @return SQL statement for creating the queue name, is dead letter message, is being delivered and the next delivery timestamp index
     */
    public String getCreateNextMessageIndexSql() {
        return bind("CREATE INDEX IF NOT EXISTS idx_{:tableName}_next_msg ON {:tableName} (queue_name, is_dead_letter_message, is_being_delivered, next_delivery_ts)",
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for creating the queue name, next delivery timestamp, key and key order where is dead letter message and is being delivered is false index.
     *
     * @return SQL statement for creating the queue name, next delivery timestamp, key and key order where is dead letter message and is being delivered is false index
     */
    public String getCreateNextReadyMessageIndexSql() {
        return bind("""
                    CREATE INDEX IF NOT EXISTS idx_{:tableName}_ready ON {:tableName} (
                        queue_name,
                        next_delivery_ts,
                        key,
                        key_order
                    )
                    WHERE
                        is_dead_letter_message = FALSE
                        AND is_being_delivered = FALSE
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for creating the queue name, next delivery timestamp, key and key order where key is not null and is dead letter message and is being delivered is false index.
     *
     * @return SQL statement for creating the queue name, next delivery timestamp, key and key order where key is not null and is dead letter message and is being delivered is false index
     */
    public String getCreateOrderedMessageReadyIndexSql() {
        return bind("""
                    CREATE INDEX IF NOT EXISTS idx_{:tableName}_ordered_ready
                      ON {:tableName} (key, queue_name, key_order, next_delivery_ts)
                      INCLUDE (id)
                      WHERE key IS NOT NULL
                        AND NOT is_dead_letter_message
                        AND NOT is_being_delivered
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for creating the queue name, next delivery timestamp, where key is null and is dead letter message and is being delivered is false index.
     *
     * @return SQL statement for creating the queue name, next delivery timestamp, where key is null and is dead letter message and is being delivered is false index.
     */
    public String getCreateUnorderedMessageReadyIndexSql() {
        return bind("""
                    CREATE INDEX IF NOT EXISTS idx_{:tableName}_unordered_ready
                      ON {:tableName} (queue_name, next_delivery_ts)
                      INCLUDE (id)
                      WHERE key IS NULL
                        AND NOT is_dead_letter_message
                        AND NOT is_being_delivered
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for creating the queue name, key order and next delivery timestamp, where key is not null and is dead letter message and is being delivered is false index.
     *
     * @return SQL statement for creating the queue name, key order and next delivery timestamp, where key is not null and is dead letter message and is being delivered is false index.
     */
    public String getCreateOrderedMessageHeadIndexSql() {
        return bind("""
                    CREATE INDEX IF NOT EXISTS idx_{:tableName}_ordered_head
                      ON {:tableName} (queue_name, key_order, next_delivery_ts)
                      INCLUDE (id)
                      WHERE key IS NOT NULL
                        AND is_dead_letter_message = FALSE
                        AND is_being_delivered     = FALSE;
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting unique queue names.
     *
     * @return SQL statement for getting unique queue names
     */
    public String getQueueNamesSql() {
        return bind("SELECT distinct queue_name FROM {:tableName}",
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting queue message sql.
     *
     * @return SQL statement for getting queue message sql
     */
    public String getQueueMessageSql() {
        return bind("""
                    INSERT INTO {:tableName} (
                    id,
                    queue_name,
                    message_payload,
                    message_payload_type,
                    added_ts,
                    next_delivery_ts,
                    last_delivery_error,
                    is_dead_letter_message,
                    meta_data,
                    delivery_mode,
                    key,
                    key_order
                    ) VALUES (
                    :id,
                    :queueName,
                    :message_payload::jsonb,
                    :message_payload_type,
                    :addedTimestamp,
                    :nextDeliveryTimestamp,
                    :lastDeliveryError,
                    :isDeadLetterMessage,
                    :metaData::jsonb,
                    :deliveryMode,
                    :key,
                    :order
                    )
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting queue message sql. If another message related to the same key and a lower order is already marked as a dead letter message,
     * in which case this message can be queued directly as a dead letter message.
     *
     * @return SQL statement for getting queue message sql
     */
    public String getQueueMessageSqlOptimized() {
        return bind("""
                      INSERT INTO {:tableName} (
                        id, queue_name, message_payload, message_payload_type,
                        added_ts, next_delivery_ts, last_delivery_error,
                        is_dead_letter_message, is_being_delivered,
                        meta_data, delivery_mode, key, key_order
                      )
                      SELECT
                        :id,
                        :queueName,
                        :message_payload::jsonb,
                        :message_payload_type,
                        :addedTimestamp,
                        :nextDeliveryTimestamp,
                        :lastDeliveryError,
                        -- inline dead-letter-barrier check:
                        CASE
                          WHEN :key IS NOT NULL
                            AND EXISTS (
                              SELECT 1
                              FROM {:tableName} dq
                              WHERE dq.queue_name             = :queueName
                                AND dq.key                    = :key
                                AND dq.key_order     < :order
                                AND dq.is_dead_letter_message = TRUE
                            )
                          THEN TRUE
                          ELSE :isDeadLetterMessage
                        END,
                        FALSE,
                        :metaData::jsonb,
                        :deliveryMode,
                        :key,
                        :order
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting the retry message sql.
     *
     * @return SQL statement for getting the retry message sql
     */
    public String getRetryMessageSql() {
        return bind("""
                    UPDATE {:tableName} SET
                    next_delivery_ts = :nextDeliveryTimestamp,
                    last_delivery_error = :lastDeliveryError,
                    redelivery_attempts = redelivery_attempts + 1,
                    is_being_delivered = FALSE,
                    delivery_ts = NULL
                    WHERE id = :id
                    RETURNING *
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting the mark as dead letter message sql.
     *
     * @return SQL statement for getting the mark as dead letter message sql
     */
    public String getMarkAsDeadLetterMessageSql() {
        return bind("""
                    UPDATE {:tableName} SET
                    next_delivery_ts = NULL,
                    last_delivery_error = :lastDeliveryError,
                    is_dead_letter_message = TRUE,
                    is_being_delivered = FALSE,
                    delivery_ts = NULL
                    WHERE id = :id AND is_dead_letter_message = FALSE
                    RETURNING *
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for marking a message as dead letter without returning the row.
     * This is used when the message has a deserialization error - returning the row
     * would cause the mapper to try deserializing again, which would fail.
     *
     * @return SQL statement for direct dead letter marking without returning the row
     */
    public String getMarkAsDeadLetterMessageDirectSql() {
        return bind("""
                    UPDATE {:tableName} SET
                    next_delivery_ts = NULL,
                    last_delivery_error = :lastDeliveryError,
                    is_dead_letter_message = TRUE,
                    is_being_delivered = FALSE,
                    delivery_ts = NULL
                    WHERE id = :id AND is_dead_letter_message = FALSE
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting the resurrect as dead letter message sql.
     *
     * @return SQL statement for getting the resurrect as dead letter message sql
     */
    public String getResurrectDeadLetterMessageSql() {
        return bind("""
                    UPDATE {:tableName} SET
                    next_delivery_ts = :nextDeliveryTimestamp,
                    is_dead_letter_message = FALSE
                    WHERE id = :id AND
                    is_dead_letter_message = TRUE
                    RETURNING *
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting the acknowledge message as handled sql.
     *
     * @return SQL statement for getting the acknowledge message as handled sql
     */
    public String getAcknowledgeMessageAsHandledSql() {
        return bind("DELETE FROM {:tableName} WHERE id = :id AND is_dead_letter_message = FALSE",
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * SQL statement for getting the delete message sql.
     *
     * @return SQL statement for getting the delete message sql
     */
    public String getDeleteMessageSql() {
        return bind("DELETE FROM {:tableName} WHERE id = :id",
                    arg("tableName", sharedQueueTableName));
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
}