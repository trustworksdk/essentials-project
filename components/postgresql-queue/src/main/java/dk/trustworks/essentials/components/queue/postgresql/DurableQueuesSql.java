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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;
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
        private final Map<String, Integer>            intValueBindings;

        public BatchedSqlResult(String sql, Map<String, String> singleValueBindings, Map<String, Collection<String>> listBindings) {
            this(sql, singleValueBindings, listBindings, Map.of());
        }

        public BatchedSqlResult(String sql,
                                Map<String, String> singleValueBindings,
                                Map<String, Collection<String>> listBindings,
                                Map<String, Integer> intValueBindings) {
            this.sql = sql;
            this.singleValueBindings = singleValueBindings;
            this.listBindings = listBindings;
            this.intValueBindings = intValueBindings;
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

        /**
         * Per-queue worker-slot limits. Bound rather than interpolated so that the statement text stays the
         * same as the slot counts move, which is what lets PostgreSQL reuse a prepared plan — see the note in
         * {@link DurableQueuesSql#buildBatchedSqlStatement}.
         */
        public Map<String, Integer> getIntValueBindings() {
            return intValueBindings;
        }
    }

    /**
     * Builds a single statement that claims messages across several queues at once, replacing one statement
     * per queue per poll.
     * <p>
     * <b>Competing consumers are handled</b>, contrary to the "work in progress" note this javadoc used to
     * carry. Candidates are selected and numbered without locking, and then a second scan re-checks
     * {@code is_being_delivered = FALSE} under {@code FOR UPDATE SKIP LOCKED}, so a row another instance
     * claimed between the two steps is dropped rather than claimed twice. That is now established by
     * experiment rather than by reading: {@code PostgresqlBatchedFetchCompetingConsumersIT} runs two
     * independent instances against one database over several queues and asserts every message is handled
     * exactly once, with a negative control proving the duplicate detector fires.
     * <p>
     * Keys and queue names are bound rather than concatenated, so a queue name or an ordered-message key
     * cannot carry SQL into the statement. The per-queue slot limits are bound too — not for safety, since
     * they are internal integers, but so that the statement text stays stable as the slot counts move and
     * PostgreSQL can reuse a prepared plan. The text still varies with the <em>number</em> of active queues,
     * which is unavoidable in a {@code VALUES} list.
     *
     * @param excludeKeysPerQueue          Map of queue names to sets of keys to exclude
     * @param availableWorkerSlotsPerQueue Map of queue names to available worker slots
     * @param activeQueues                 List of active queue names
     * @return BatchedSqlResult containing SQL statement and parameter bindings for retrieving messages from multiple queues
     */
    public BatchedSqlResult buildBatchedSqlStatement(Map<QueueName, Set<String>> excludeKeysPerQueue,
                                                     Map<QueueName, Integer> availableWorkerSlotsPerQueue,
                                                     List<QueueName> activeQueues) {
        requireNonNull(activeQueues, "No activeQueues provided");
        // An empty VALUES list is a syntax error rather than an empty result, so this is a caller bug worth
        // failing on. PostgresqlDurableQueues.fetchNextBatchOfMessagesBatched already returns early in that
        // case; this guards the other callers of a public method.
        requireFalse(activeQueues.isEmpty(), "activeQueues must not be empty");

        var values              = new StringBuilder();
        var singleValueBindings = new HashMap<String, String>();
        var listBindings        = new HashMap<String, Collection<String>>();
        var intValueBindings    = new HashMap<String, Integer>();

        for (int i = 0; i < activeQueues.size(); i++) {
            var queueName                        = activeQueues.get(i);
            // Absent means no slots reported for the queue. Interpolating a null produced the literal "null"
            // and a syntax error; treating it as zero yields "rn <= 0", which simply selects nothing for it.
            var availableWorkerSlotsForThisQueue = availableWorkerSlotsPerQueue.getOrDefault(queueName, 0);
            var excludedKeysForThisQueue         = excludeKeysPerQueue.getOrDefault(queueName, Collections.emptySet());

            // Add queue name parameter binding as single value
            singleValueBindings.put("queueName" + i, queueName.toString());
            intValueBindings.put("slots" + i, availableWorkerSlotsForThisQueue);

            // Only add parameter binding if there are keys to exclude
            // For empty collections, we'll use a different SQL approach
            if (!excludedKeysForThisQueue.isEmpty()) {
                listBindings.put("excludeKeys" + i, excludedKeysForThisQueue);
            }

            if (i > 0) values.append(",\n    ");
            values.append("(:queueName").append(i).append(", ")
                  .append(":slots").append(i).append("::int, ")
                  .append(excludedKeysForThisQueue.isEmpty() ? "ARRAY[]::text[]" : "ARRAY[<excludeKeys" + i + ">]::text[]")
                  .append(")");
        }

        var sql = bind("""
                       WITH queue_config(queue_name, slots, exclude_keys) AS (
                           VALUES {:values}
                       ),
                       -- 2) Number every eligible candidate, ordered and unordered alike, oldest first.
                       --
                       --    Ordered and unordered candidates are numbered together in a single window so that
                       --    the per-queue slot limit in step 3 caps the TOTAL number of messages handed to a
                       --    queue. Numbering them separately would apply the limit twice and allow a queue to
                       --    be handed up to 2x its available worker slots.
                           candidates_rn AS (
                             SELECT
                               q.id,
                               q.queue_name,
                               ROW_NUMBER() OVER (
                                 PARTITION BY q.queue_name
                                 ORDER BY q.next_delivery_ts, q.id
                               ) AS rn
                             FROM {:tableName} q
                             JOIN queue_config cfg USING(queue_name)
                             WHERE
                                  q.is_dead_letter_message = FALSE
                              AND q.is_being_delivered     = FALSE
                              AND q.next_delivery_ts      <= :now
                              AND (
                                    -- Unordered candidate. Note there is deliberately no exclude_keys predicate
                                    -- here: the key is NULL, so it can never match an excluded key, and
                                    -- "NOT (q.key = ANY(cfg.exclude_keys))" would evaluate to NULL rather than
                                    -- TRUE for every row whenever exclude_keys is non-empty, silently starving
                                    -- all unordered messages on that queue.
                                    q.key IS NULL
                                 OR (
                                      -- Ordered candidate: not currently in process, and first in line for its key
                                      NOT (q.key = ANY(cfg.exclude_keys))
                                      AND NOT EXISTS (
                                        SELECT 1
                                        FROM {:tableName} q2
                                        WHERE q2.queue_name = q.queue_name
                                          AND q2.key        = q.key
                                          AND q2.key_order  < q.key_order
                                      )
                                    )
                              )
                           ),

                           -- 3) Take at most cfg.slots per queue *without locking*
                           candidates AS (
                             SELECT c.id
                             FROM candidates_rn c
                             JOIN queue_config cfg
                               ON c.queue_name = cfg.queue_name
                             WHERE c.rn <= cfg.slots
                           ),

                           -- 4) Now lock exactly those durable_queues rows
                           --
                           --    is_being_delivered is re-checked here on purpose. Under READ COMMITTED the
                           --    candidate CTE above runs against the statement snapshot, so a competing consumer
                           --    may have claimed a row in between. Repeating the predicate on the locking scan
                           --    makes Postgres re-evaluate it against the freshly locked row version and drop
                           --    rows that were claimed in the meantime.
                           locked AS (
                             SELECT q.id
                             FROM {:tableName} q
                             JOIN candidates c
                               ON q.id = c.id
                             WHERE q.is_being_delivered = FALSE
                             FOR UPDATE SKIP LOCKED
                           )

                         -- 5) Finally, update & return the locked rows
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

        return new BatchedSqlResult(sql, singleValueBindings, listBindings, intValueBindings);
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
     * Unique index enforcing at most one ordered message per {@code (queue_name, key, key_order)}.
     * <p>
     * Partial on {@code key IS NOT NULL} so unordered messages — which all carry a NULL key and a constant
     * {@code key_order} of -1 — are untouched by it. Without this, two ordered messages sharing a key and an
     * order never block each other in the per-key barrier, and that key's ordering guarantee silently does not
     * hold.
     *
     * @return SQL creating the unique ordered-message index
     */
    public String getCreateOrderedMessageUniqueIndexSql() {
        return bind("CREATE UNIQUE INDEX IF NOT EXISTS idx_{:tableName}_ordered_unique ON {:tableName} (queue_name, key, key_order) WHERE key IS NOT NULL",
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * Counts the existing {@code (queue_name, key, key_order)} groups that already have more than one ordered
     * message, so a failure to create the unique index can be reported with a number and an example rather than
     * only a constraint-violation stack trace.
     *
     * @return SQL returning one row per duplicated group, worst first
     */
    public String getFindDuplicateOrderedMessagesSql() {
        return bind("""
                    SELECT queue_name, key, key_order, count(*) AS duplicates
                      FROM {:tableName}
                     WHERE key IS NOT NULL
                     GROUP BY queue_name, key, key_order
                    HAVING count(*) > 1
                     ORDER BY count(*) DESC
                     LIMIT 10
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * Table DDL for the split <b>unordered</b> queue table: {@link #getCreateQueueTableSql()} without the
     * {@code key}, {@code key_order} or {@code delivery_mode} columns, none of which mean anything when every row
     * in the table is unordered by construction.
     * <p>
     * Dropping the columns is not cosmetic. It is what allows a single secondary index here where the shared
     * table needs several, and index count is the whole of the split's measured 1.38x - see
     * {@code docs/durable-queues-redesign-measurements.md} §1 and §8.
     *
     * @return DDL creating the unordered queue table
     */
    public String getCreateUnorderedQueueTableSql() {
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
                    meta_data              JSONB DEFAULT NULL
                    )
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * Table DDL for the split <b>ordered</b> queue table. {@code key} and {@code key_order} become
     * {@code NOT NULL}, because a row here without them is meaningless - which is a guarantee the shared table
     * cannot make, since it has to hold both kinds.
     *
     * @return DDL creating the ordered queue table
     */
    public String getCreateOrderedQueueTableSql() {
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
                    key                    TEXT   NOT NULL,
                    key_order              BIGINT NOT NULL
                    )
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * The unordered table's only ready-scan index. One index, against the shared table's several, because there
     * is no ordered traffic here to serve.
     *
     * @return DDL creating the unordered ready index
     */
    public String getCreateSplitUnorderedReadyIndexSql() {
        return bind("""
                    CREATE INDEX IF NOT EXISTS idx_{:tableName}_ready ON {:tableName} (queue_name, next_delivery_ts) INCLUDE (id)
                      WHERE NOT is_dead_letter_message AND NOT is_being_delivered
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * The ordered table's head-scan index.
     * <p>
     * Two indexes on this table, not the three v1 carries for ordered traffic: §17 measured
     * {@code idx_*_ordered_ready} at zero scans at both 8 and 200 ordered keys, and {@code idx_*_ordered_msg}
     * superseded by the unique index once that exists. Inheriting v1's set on inspection is exactly how the
     * redundant one came to be there.
     *
     * @return DDL creating the ordered head index
     */
    public String getCreateSplitOrderedHeadIndexSql() {
        return bind("""
                    CREATE INDEX IF NOT EXISTS idx_{:tableName}_head ON {:tableName} (queue_name, key_order, next_delivery_ts) INCLUDE (id)
                      WHERE NOT is_dead_letter_message AND NOT is_being_delivered
                    """,
                    arg("tableName", sharedQueueTableName));
    }

    /**
     * The ordered table's per-key index, unique when the duplicate strategy is {@code REJECT}. Non-partial on
     * key, unlike the shared table's version, because every row here has one.
     *
     * @param unique whether to enforce at most one message per {@code (queue_name, key, key_order)}
     * @return DDL creating the ordered per-key index
     */
    public String getCreateSplitOrderedKeyIndexSql(boolean unique) {
        return bind("CREATE " + (unique ? "UNIQUE " : "") + "INDEX IF NOT EXISTS idx_{:tableName}_key ON {:tableName} (queue_name, key, key_order)",
                    arg("tableName", sharedQueueTableName));
    }
    /**
     * SQL statement for acknowledging several messages as handled in one statement.
     * <p>
     * Same predicate as {@link #getAcknowledgeMessageAsHandledSql()}, widened to a list. The
     * {@code is_dead_letter_message = FALSE} guard is what makes a batch safe: a message that was marked as a
     * dead letter while its acknowledgement sat in the buffer is skipped rather than deleted, so the batch
     * cannot destroy a dead letter the operator still needs. The caller learns this from the row count being
     * lower than the batch size.
     *
     * @return SQL statement for acknowledging several messages as handled
     */
    public String getAcknowledgeMessagesAsHandledSql() {
        return bind("DELETE FROM {:tableName} WHERE id IN (<ids>) AND is_dead_letter_message = FALSE",
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
