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

package dk.trustworks.essentials.examples.perflab.queuedesign;

import java.util.*;

/**
 * Throwaway schema prototypes for the write-cost comparison driven by {@code QueueSchemaWriteCostScenario}.
 * Deliberately raw DDL and SQL rather than a {@code DurableQueues} implementation: the question is what
 * index maintenance costs per write, and routing the workload through the real component would bury that
 * under the per-message connection acquisition already known to dominate.
 *
 * Index names are single letters because PostgreSQL truncates identifiers at 63 bytes, and descriptive
 * suffixes on an already-long generated table name collided after truncation.
 *
 * <h2>The hypothesis under test, stated precisely</h2>
 * An earlier draft of the v2 plan claimed the win was that "no fetch can ever be a HOT update" because the
 * claim statement mutates columns appearing in index predicates. That framing is wrong, and this prototype is
 * built to expose why: the v2 unordered table indexes {@code next_delivery_ts} and {@code is_being_delivered}
 * too, so it cannot produce HOT updates either. Both schemas should show {@code n_tup_hot_upd} at or near
 * zero for the claim phase.
 * <p>
 * The real hypothesis is <strong>index write amplification</strong>. v1 keeps every message in one table
 * carrying six secondary indexes, three of which exist purely for ordered delivery, so an unordered message
 * pays maintenance on all six at insert, at claim, and at delete. A split unordered table needs one. If the
 * effect is real it should appear as a wall-clock difference in the insert and claim phases and as a large
 * difference in total index bytes.
 */
public final class QueueSchemaPrototype {

    private QueueSchemaPrototype() {
    }

    /**
     * Columns shared by every variant, matching v1's {@code durable_queues} table so the row width — and
     * therefore the heap cost — is comparable. The ordered variants add {@code key} and {@code key_order}.
     */
    private static final String COMMON_COLUMNS = """
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
            delivery_mode          TEXT NOT NULL
            """;

    /**
     * v1: one table for both delivery modes, carrying all six secondary indexes copied from
     * {@code DurableQueuesSql}. {@code fillFactor} of 100 reproduces v1 exactly; a lower value is the cheap
     * tuning alternative the scenario also measures.
     */
    public static List<String> v1SingleTableDdl(String table, int fillFactor) {
        return List.of(
                "CREATE TABLE " + table + " (" + COMMON_COLUMNS + ", key TEXT DEFAULT NULL, key_order BIGINT DEFAULT -1) WITH (fillfactor=" + fillFactor + ")",
                "CREATE INDEX idx_" + table + "_a ON " + table + " (queue_name, key, key_order)",
                "CREATE INDEX idx_" + table + "_b ON " + table + " (queue_name, is_dead_letter_message, is_being_delivered, next_delivery_ts)",
                """
                CREATE INDEX idx_%1$s_c ON %1$s (queue_name, next_delivery_ts, key, key_order)
                  WHERE is_dead_letter_message = FALSE AND is_being_delivered = FALSE
                """.formatted(table),
                """
                CREATE INDEX idx_%1$s_d ON %1$s (key, queue_name, key_order, next_delivery_ts) INCLUDE (id)
                  WHERE key IS NOT NULL AND NOT is_dead_letter_message AND NOT is_being_delivered
                """.formatted(table),
                """
                CREATE INDEX idx_%1$s_e ON %1$s (queue_name, next_delivery_ts) INCLUDE (id)
                  WHERE key IS NULL AND NOT is_dead_letter_message AND NOT is_being_delivered
                """.formatted(table),
                """
                CREATE INDEX idx_%1$s_f ON %1$s (queue_name, key_order, next_delivery_ts) INCLUDE (id)
                  WHERE key IS NOT NULL AND is_dead_letter_message = FALSE AND is_being_delivered = FALSE
                """.formatted(table));
    }

    /**
     * v2 unordered table: no {@code key}/{@code key_order} columns at all, and exactly one secondary index —
     * the only access pattern an unordered consumer has.
     */
    public static List<String> v2UnorderedTableDdl(String table, int fillFactor) {
        return List.of(
                "CREATE TABLE " + table + " (" + COMMON_COLUMNS + ") WITH (fillfactor=" + fillFactor + ")",
                """
                CREATE INDEX idx_%1$s_a ON %1$s (queue_name, next_delivery_ts) INCLUDE (id)
                  WHERE NOT is_dead_letter_message AND NOT is_being_delivered
                """.formatted(table));
    }

    /**
     * v2 ordered table: keeps the three indexes the ordered access patterns need — the per-key barrier lookup,
     * the ready scan and the head scan — but is never touched by unordered traffic.
     */
    public static List<String> v2OrderedTableDdl(String table, int fillFactor) {
        return List.of(
                "CREATE TABLE " + table + " (" + COMMON_COLUMNS + ", key TEXT NOT NULL, key_order BIGINT NOT NULL) WITH (fillfactor=" + fillFactor + ")",
                "CREATE INDEX idx_" + table + "_a ON " + table + " (queue_name, key, key_order)",
                """
                CREATE INDEX idx_%1$s_b ON %1$s (key, queue_name, key_order, next_delivery_ts) INCLUDE (id)
                  WHERE NOT is_dead_letter_message AND NOT is_being_delivered
                """.formatted(table),
                """
                CREATE INDEX idx_%1$s_c ON %1$s (queue_name, key_order, next_delivery_ts) INCLUDE (id)
                  WHERE NOT is_dead_letter_message AND NOT is_being_delivered
                """.formatted(table));
    }

    /**
     * {@link #COMMON_COLUMNS} minus the dead-letter flag. Filtered by line rather than by an exact-whitespace
     * replace, and it fails loudly if the column is not found — a silent no-op would leave the column in place
     * and the arm would quietly measure nothing.
     */
    private static String columnsWithoutDeadLetterFlag() {
        var kept = Arrays.stream(COMMON_COLUMNS.split("\n"))
                         .filter(line -> !line.contains("is_dead_letter_message"))
                         .toList();
        if (kept.size() == COMMON_COLUMNS.split("\n").length) {
            throw new IllegalStateException("is_dead_letter_message not found in COMMON_COLUMNS - the DLQ-split arm would measure nothing");
        }
        // The line before the removed one may now carry a trailing comma before the closing paren.
        return String.join("\n", kept).replaceAll(",\\s*$", "");
    }

    /**
     * Hot table with dead-letter messages moved out: no {@code is_dead_letter_message} column at all, and
     * therefore none of it in any index or predicate.
     * <p>
     * The claim is that this reduces index write amplification — the lever that has actually measured as
     * significant — because v1's {@code idx_b} carries the flag as a key column and three more indexes carry it
     * in their predicates. The secondary claim is that long-lived dead-letter rows stop occupying pages in the
     * hot table, which only shows up when there are some.
     */
    public static List<String> dlqSplitHotTableDdl(String table, int fillFactor) {
        return List.of(
                "CREATE TABLE " + table + " (" + columnsWithoutDeadLetterFlag() + ", key TEXT DEFAULT NULL, key_order BIGINT DEFAULT -1) WITH (fillfactor=" + fillFactor + ")",
                "CREATE INDEX idx_" + table + "_a ON " + table + " (queue_name, key, key_order)",
                "CREATE INDEX idx_" + table + "_b ON " + table + " (queue_name, is_being_delivered, next_delivery_ts)",
                """
                CREATE INDEX idx_%1$s_c ON %1$s (queue_name, next_delivery_ts, key, key_order)
                  WHERE is_being_delivered = FALSE
                """.formatted(table),
                """
                CREATE INDEX idx_%1$s_d ON %1$s (key, queue_name, key_order, next_delivery_ts) INCLUDE (id)
                  WHERE key IS NOT NULL AND NOT is_being_delivered
                """.formatted(table),
                """
                CREATE INDEX idx_%1$s_e ON %1$s (queue_name, next_delivery_ts) INCLUDE (id)
                  WHERE key IS NULL AND NOT is_being_delivered
                """.formatted(table),
                """
                CREATE INDEX idx_%1$s_f ON %1$s (queue_name, key_order, next_delivery_ts) INCLUDE (id)
                  WHERE key IS NOT NULL AND is_being_delivered = FALSE
                """.formatted(table));
    }

    /**
     * The dead-letter side table. One index, because the only access patterns are "browse a queue's dead
     * letters" and "fetch one by id" — the latter served by the primary key.
     */
    public static List<String> dlqSideTableDdl(String table) {
        return List.of(
                "CREATE TABLE " + table + " (" + columnsWithoutDeadLetterFlag() + ", key TEXT DEFAULT NULL, key_order BIGINT DEFAULT -1)",
                "CREATE INDEX idx_" + table + "_dlq ON " + table + " (queue_name, added_ts)");
    }

    /**
     * Moves a message to the dead-letter table, which is what {@code markAsDeadLetterMessage} becomes once the
     * flag is gone. One statement so it is atomic.
     */
    public static String moveToDlqSql(String hotTable, String dlqTable) {
        return """
               WITH moved AS (
                 DELETE FROM %1$s WHERE id = :id RETURNING *
               )
               INSERT INTO %2$s SELECT * FROM moved
               """.formatted(hotTable, dlqTable);
    }

    /**
     * v1's shape, partitioned by {@code queue_name}.
     *
     * <h2>The consequence that decides whether this is viable</h2>
     * PostgreSQL requires the partition key to be part of every unique constraint, so the primary key becomes
     * {@code (id, queue_name)} rather than {@code id}. The whole {@code DurableQueues} API is keyed by
     * {@link dk.trustworks.essentials.components.foundation.messaging.queue.QueueEntryId} <em>alone</em> —
     * {@code getQueuedMessage}, {@code acknowledgeMessageAsHandled}, {@code deleteMessage},
     * {@code markAsDeadLetterMessage}, {@code retryMessage} — so none of them can name a partition, and every
     * one degrades from a primary-key lookup to a probe of every partition.
     * <p>
     * Acknowledgement by id is the hot path that §7 measured at 16.5x, so this is the thing to measure rather
     * than assume: partitioning may win on purge and index size while losing more on the operation that matters
     * most.
     */
    public static List<String> v1PartitionedByQueueDdl(String table, List<String> queueNames, int fillFactor) {
        var statements = new ArrayList<String>();
        // `id TEXT PRIMARY KEY` has to become `id TEXT NOT NULL`, because a partitioned table cannot carry a
        // primary key that excludes the partition key - PostgreSQL rejects the DDL outright. This is the
        // constraint made concrete rather than argued: id stops being unique on its own, and every by-id
        // operation in the DurableQueues API loses its single-partition lookup.
        var columnsWithoutIdPrimaryKey = COMMON_COLUMNS.replace("id                     TEXT PRIMARY KEY",
                                                                "id                     TEXT NOT NULL");
        if (columnsWithoutIdPrimaryKey.equals(COMMON_COLUMNS)) {
            throw new IllegalStateException("Could not strip the id primary key - the partitioned DDL would declare two");
        }
        statements.add("CREATE TABLE " + table + " (" + columnsWithoutIdPrimaryKey
                               + ", key TEXT DEFAULT NULL, key_order BIGINT DEFAULT -1"
                               + ", PRIMARY KEY (id, queue_name)) PARTITION BY LIST (queue_name)");
        for (var i = 0; i < queueNames.size(); i++) {
            statements.add("CREATE TABLE " + table + "_p" + i + " PARTITION OF " + table
                                   + " FOR VALUES IN ('" + queueNames.get(i) + "') WITH (fillfactor=" + fillFactor + ")");
        }
        statements.add("CREATE INDEX idx_" + table + "_a ON " + table + " (queue_name, key, key_order)");
        statements.add("CREATE INDEX idx_" + table + "_b ON " + table + " (queue_name, is_dead_letter_message, is_being_delivered, next_delivery_ts)");
        statements.add("""
                       CREATE INDEX idx_%1$s_e ON %1$s (queue_name, next_delivery_ts) INCLUDE (id)
                         WHERE key IS NULL AND NOT is_dead_letter_message AND NOT is_being_delivered
                       """.formatted(table));
        return List.copyOf(statements);
    }

    /**
     * The primary-key column list for the variant, since partitioning changes it. Used to build the by-id
     * statements the comparison turns on.
     */
    public static String deleteByIdSql(String table) {
        return "DELETE FROM " + table + " WHERE id = :id";
    }

    /**
     * Insert used by every variant that has no key columns.
     */
    public static String insertUnorderedSql(String table) {
        return "INSERT INTO " + table + " (id, queue_name, message_payload, message_payload_type, added_ts, next_delivery_ts, delivery_mode) "
                + "VALUES (:id, :queueName, :payload::jsonb, :payloadType, :now, :now, 'NORMAL')";
    }

    public static String insertOrderedSql(String table, boolean hasKeyColumns) {
        if (!hasKeyColumns) {
            throw new IllegalArgumentException("Ordered insert requires a table with key columns");
        }
        return "INSERT INTO " + table + " (id, queue_name, message_payload, message_payload_type, added_ts, next_delivery_ts, delivery_mode, key, key_order) "
                + "VALUES (:id, :queueName, :payload::jsonb, :payloadType, :now, :now, 'IN_ORDER', :key, :keyOrder)";
    }

    /**
     * Unordered claim. Same shape as v1's {@code buildUnorderedSqlStatement} — the point of the comparison is
     * the index maintenance the UPDATE triggers, so the statement itself must not differ between arms.
     * <p>
     * {@code keyIsNullPredicate} is required on v1's shared table, where unordered rows must be distinguished
     * from ordered ones, and absent on v2's unordered table, where every row is unordered by construction.
     */
    public static String claimUnorderedSql(String table, boolean keyIsNullPredicate) {
        return """
               WITH ready AS (
                 SELECT id FROM %1$s
                  WHERE queue_name = :queueName
                    AND is_dead_letter_message = FALSE
                    AND is_being_delivered     = FALSE
                    AND next_delivery_ts      <= :now
                    %2$s
                  ORDER BY next_delivery_ts
                  LIMIT :limit
                  FOR UPDATE SKIP LOCKED
               )
               UPDATE %1$s q
                  SET total_attempts     = q.total_attempts + 1,
                      next_delivery_ts   = NULL,
                      is_being_delivered = TRUE,
                      delivery_ts        = :now
                 FROM ready r
                WHERE q.id = r.id
               RETURNING q.id
               """.formatted(table, keyIsNullPredicate ? "AND key IS NULL" : "");
    }

    /**
     * Ordered claim, carrying the per-key barrier. {@code keyNotNullPredicate} is needed only where unordered
     * rows share the table.
     */
    public static String claimOrderedSql(String table, boolean keyNotNullPredicate) {
        return """
               WITH ready AS (
                 SELECT id FROM %1$s q1
                  WHERE q1.queue_name = :queueName
                    AND q1.is_dead_letter_message = FALSE
                    AND q1.is_being_delivered     = FALSE
                    AND q1.next_delivery_ts      <= :now
                    %2$s
                    AND NOT EXISTS (SELECT 1 FROM %1$s q2
                                     WHERE q2.key = q1.key
                                       AND q2.queue_name = q1.queue_name
                                       AND q2.key_order < q1.key_order)
                  ORDER BY q1.key_order, q1.next_delivery_ts
                  LIMIT :limit
                  FOR UPDATE SKIP LOCKED
               )
               UPDATE %1$s q
                  SET total_attempts     = q.total_attempts + 1,
                      next_delivery_ts   = NULL,
                      is_being_delivered = TRUE,
                      delivery_ts        = :now
                 FROM ready r
                WHERE q.id = r.id
               RETURNING q.id
               """.formatted(table, keyNotNullPredicate ? "AND q1.key IS NOT NULL" : "");
    }

    /**
     * Acknowledgement, batched so the per-statement overhead does not swamp the index-maintenance signal the
     * comparison is after.
     */
    public static String deleteBatchSql(String table) {
        return "DELETE FROM " + table + " WHERE id IN (<ids>)";
    }

    /**
     * Single-row acknowledgement, matching the statement {@code PostgresqlDurableQueues.acknowledgeMessageAsHandled}
     * actually issues. Used by {@code QueueFrameworkOverheadScenario} to hold the SQL constant while the
     * transaction granularity around it varies, so the difference measured is the granularity and not the
     * statement.
     */
    public static String deleteSingleSql(String table) {
        return "DELETE FROM " + table + " WHERE id = :id";
    }

    // ------------------------------------------------------------------------------------------------
    // Cursor variant: ordered messages with an explicit per-key progress cursor instead of the
    // correlated NOT EXISTS barrier.
    // ------------------------------------------------------------------------------------------------

    /**
     * Ordered table for the cursor variant. Identical to {@link #v2OrderedTableDdl} minus the two indexes
     * that exist only to serve the barrier — the cursor drives from the key-state table instead, so the only
     * lookup needed is "lowest key_order above the cursor for this key".
     */
    public static List<String> cursorOrderedTableDdl(String table, int fillFactor) {
        return List.of(
                "CREATE TABLE " + table + " (" + COMMON_COLUMNS + ", key TEXT NOT NULL, key_order BIGINT NOT NULL) WITH (fillfactor=" + fillFactor + ")",
                """
                CREATE INDEX idx_%1$s_a ON %1$s (queue_name, key, key_order) INCLUDE (id)
                  WHERE NOT is_dead_letter_message AND NOT is_being_delivered
                """.formatted(table));
    }

    /**
     * The cursor itself: one row per {@code (queue_name, key)} recording how far that key has been handled.
     * <p>
     * {@code completed_through} is the <em>highest completed</em> order, not the next expected one. That
     * distinction is load-bearing: {@code key_order} has neither a uniqueness nor a contiguity guarantee, and
     * {@code deleteMessage} / {@code purgeQueue} can remove rows, so a next-expected cursor would wedge on the
     * first gap. "Lowest order above the cursor" is gap-tolerant, which is the property the NOT EXISTS
     * barrier has for free and which a naive cursor would throw away.
     */
    public static List<String> cursorKeyStateDdl(String table) {
        return List.of(
                """
                CREATE TABLE %1$s (
                  queue_name        TEXT   NOT NULL,
                  key               TEXT   NOT NULL,
                  completed_through BIGINT NOT NULL,
                  PRIMARY KEY (queue_name, key)
                )
                """.formatted(table));
    }

    /**
     * Seeds one cursor row per key present in the message table, starting below the lowest possible order.
     * A real implementation would create the row on first enqueue for the key.
     */
    public static String seedKeyStateSql(String keyStateTable, String messageTable) {
        return "INSERT INTO " + keyStateTable + " (queue_name, key, completed_through) "
                + "SELECT DISTINCT queue_name, key, -1 FROM " + messageTable + " WHERE queue_name = :queueName";
    }

    /**
     * Cursor claim. The structural difference from the barrier, and the whole point of the prototype.
     * <p>
     * The barrier version evaluates {@code NOT EXISTS (… key_order < mine)} against every candidate row in the
     * table. This drives from the key-state table instead: one row per key, and a {@code LATERAL} lookup that
     * walks the {@code (queue_name, key, key_order)} index to the first eligible message for that key. Work is
     * proportional to the number of keys rather than to the number of queued messages.
     * <p>
     * Per-key exclusivity still comes from {@code is_being_delivered}: the only eligible row for a key is the
     * lowest above its cursor, so while that row is in flight the key yields nothing. The
     * {@code is_being_delivered = FALSE} guard on the UPDATE is what makes the claim safe against a concurrent
     * claimer; full multi-node concurrency control is out of scope for this prototype, which runs
     * single-connection.
     */
    public static String claimOrderedViaCursorSql(String messageTable, String keyStateTable) {
        return """
               WITH candidate AS (
                 SELECT head.id
                   FROM %2$s ks
                   CROSS JOIN LATERAL (
                     SELECT m.id
                       FROM %1$s m
                      WHERE m.queue_name             = ks.queue_name
                        AND m.key                    = ks.key
                        AND m.key_order              > ks.completed_through
                        AND m.is_dead_letter_message = FALSE
                        AND m.is_being_delivered     = FALSE
                        AND m.next_delivery_ts      <= :now
                      ORDER BY m.key_order
                      LIMIT 1
                   ) head
                  WHERE ks.queue_name = :queueName
                  LIMIT :limit
               )
               UPDATE %1$s q
                  SET total_attempts     = q.total_attempts + 1,
                      next_delivery_ts   = NULL,
                      is_being_delivered = TRUE,
                      delivery_ts        = :now
                 FROM candidate c
                WHERE q.id = c.id
                  AND q.is_being_delivered = FALSE
               RETURNING q.id
               """.formatted(messageTable, keyStateTable);
    }

    /**
     * Batched ordered acknowledgement — the thing the barrier design cannot express.
     * <p>
     * Under the barrier, a key is unblocked only by its predecessor's row physically disappearing, so
     * deferring or grouping deletes stalls the key (measured at 0.82x). With a cursor, completion is
     * <em>recorded</em>: this deletes the handled rows and advances each affected key's cursor to the highest
     * order just completed, in one statement.
     */
    public static String ackOrderedViaCursorSql(String messageTable, String keyStateTable) {
        return """
               WITH deleted AS (
                 DELETE FROM %1$s WHERE id IN (<ids>) RETURNING queue_name, key, key_order
               ), highest AS (
                 SELECT queue_name, key, MAX(key_order) AS max_order FROM deleted GROUP BY queue_name, key
               )
               UPDATE %2$s ks
                  SET completed_through = highest.max_order
                 FROM highest
                WHERE ks.queue_name = highest.queue_name
                  AND ks.key        = highest.key
               """.formatted(messageTable, keyStateTable);
    }

    // ------------------------------------------------------------------------------------------------
    // Cursor variant, corrected. The arm above is kept as measured, because its numbers are quoted; this
    // one closes two correctness holes in it and exists to price the fix.
    // ------------------------------------------------------------------------------------------------

    /**
     * Ordered table for the corrected cursor: {@link #cursorOrderedTableDdl} plus one partial index on the
     * in-flight rows.
     * <p>
     * The correction's per-key {@code NOT EXISTS} looks for rows with {@code is_being_delivered = TRUE}, and
     * the cursor table's only index is partial on {@code NOT is_being_delivered} — so it excludes, by
     * construction, exactly the rows the check needs. Without this index the check falls back to a scan per
     * key and the arm becomes unmeasurably slow: at 1000 keys it ran for over fifteen minutes on a case every
     * other arm finishes in under twenty seconds.
     * <p>
     * The index is cheap to hold — only in-flight rows are in it, so its size is bounded by the number of
     * worker slots rather than by the backlog — but it is not free to maintain: a row enters it on claim and
     * leaves it on acknowledgement, which is index write amplification on the hot path, the very thing the
     * cursor design was reducing. Two secondary indexes still beats the barrier's three.
     */
    public static List<String> cursorSafeOrderedTableDdl(String table, int fillFactor) {
        var statements = new ArrayList<String>(cursorOrderedTableDdl(table, fillFactor));
        statements.add("CREATE INDEX idx_" + table + "_b ON " + table + " (queue_name, key) WHERE is_being_delivered");
        // Non-partial, and that is the point. The clamp's interval scan must see rows the claim cannot take -
        // dead-lettered, or not yet due - so it carries no predicate on those columns, and a partial index can
        // therefore never serve it. Without this the MIN falls back to a sequential scan of the whole message
        // table per key per round: measured at 116s against 250ms, identically across three repetitions.
        //
        // Note what this costs the design. The cursor's headline advantage over the barrier was one secondary
        // index instead of three, and less than half the index bytes. Gap-safety puts back the very index -
        // (queue_name, key, key_order) over all rows - that the barrier needed and the cursor claimed to delete.
        statements.add("CREATE INDEX idx_" + table + "_c ON " + table + " (queue_name, key, key_order)");
        return List.copyOf(statements);
    }

    /**
     * Creates the cursor row for a key at enqueue time, which is where a real implementation must do it — the
     * cursor claim drives <em>from</em> the key-state table, so a key with no row is invisible to every cursor
     * pod.
     * <p>
     * {@code -1} starts the cursor below the lowest possible order, and {@code ON CONFLICT DO NOTHING} makes it
     * safe to issue on every enqueue: the row must never be reset for a key that is already making progress, or
     * the whole key would be redelivered.
     */
    public static String upsertKeyStateOnEnqueueSql(String keyStateTable) {
        return "INSERT INTO " + keyStateTable + " (queue_name, key, completed_through) "
                + "VALUES (:queueName, :key, -1) ON CONFLICT (queue_name, key) DO NOTHING";
    }

    /**
     * Idempotent reconciliation: gives a cursor row to every key that has messages but no row.
     * <p>
     * This is the safety net the rollout needs, and it is not optional. During a rolling deploy an old pod
     * enqueues ordered messages without creating cursor rows, so those keys are invisible to cursor pods. While
     * any barrier pod survives they still get handled; once the fleet is fully migrated they are stranded
     * silently — no error, no dead letter, just messages that are never claimed. A one-off backfill before the
     * deploy cannot close that window, because the window is the deploy.
     * <p>
     * Cheap enough to run periodically: bounded by the number of distinct keys, not by the backlog, and
     * {@code ON CONFLICT DO NOTHING} makes repetition free. Running it when a claim comes back empty is the
     * obvious trigger, and it converges without operator involvement.
     */
    public static String reconcileKeyStateSql(String keyStateTable, String messageTable) {
        return "INSERT INTO " + keyStateTable + " (queue_name, key, completed_through) "
                + "SELECT DISTINCT m.queue_name, m.key, -1 FROM " + messageTable + " m WHERE m.queue_name = :queueName "
                + "ON CONFLICT (queue_name, key) DO NOTHING";
    }

    /**
     * Per-key exclusive cursor claim.
     *
     * <h2>What was wrong with {@link #claimOrderedViaCursorSql}</h2>
     * That statement filters {@code is_being_delivered = FALSE} <em>inside</em> the per-key LATERAL lookup, so
     * while order 5 is in flight — cursor still at 4, row excluded by the filter — the lookup returns order
     * <b>6</b>. A second claimer for the same key therefore gets 6 while 5 is still being handled, and per-key
     * ordering is violated under any concurrency at all: two worker threads on one node are enough. Its
     * javadoc asserts the opposite ("while that row is in flight the key yields nothing"), which is not what
     * the SQL does. The prototype never surfaced it because it runs single-connection with claim and
     * acknowledge strictly alternating, so nothing is ever in flight at the moment a claim runs.
     *
     * <h2>The fix, and why it is this one</h2>
     * An explicit {@code NOT EXISTS} over in-flight rows for the key. It is stateless: nothing is written at
     * claim time, so nothing can leak if a process dies mid-handling, and the existing
     * {@code resetMessagesStuckBeingDelivered} already restores eligibility by clearing
     * {@code is_being_delivered}.
     * <p>
     * The alternative — an {@code in_flight} marker on the key-state row, locked {@code FOR UPDATE SKIP
     * LOCKED} — is one indexed row per key rather than a subquery, so probably cheaper. But it is a lease, and
     * a lease needs expiry, recovery and a fence token to be safe under a partitioned node; that is the whole
     * of the v2 design plan §8. If the stateless form is fast enough, none of §8 is needed, which is worth
     * establishing before taking on that machinery.
     */
    public static String claimOrderedViaSafeCursorSql(String messageTable, String keyStateTable) {
        return """
               WITH candidate AS (
                 SELECT head.id
                   FROM %2$s ks
                   CROSS JOIN LATERAL (
                     SELECT m.id
                       FROM %1$s m
                      WHERE m.queue_name             = ks.queue_name
                        AND m.key                    = ks.key
                        AND m.key_order              > ks.completed_through
                        AND m.is_dead_letter_message = FALSE
                        AND m.is_being_delivered     = FALSE
                        AND m.next_delivery_ts      <= :now
                      ORDER BY m.key_order
                      LIMIT 1
                   ) head
                  WHERE ks.queue_name = :queueName
                    -- The correction: a key with anything in flight yields nothing at all, rather than
                    -- yielding its next-but-one.
                    AND NOT EXISTS (
                      SELECT 1
                        FROM %1$s inflight
                       WHERE inflight.queue_name         = ks.queue_name
                         AND inflight.key                = ks.key
                         AND inflight.is_being_delivered = TRUE
                    )
                  LIMIT :limit
               )
               UPDATE %1$s q
                  SET total_attempts     = q.total_attempts + 1,
                      next_delivery_ts   = NULL,
                      is_being_delivered = TRUE,
                      delivery_ts        = :now
                 FROM candidate c
                WHERE q.id = c.id
                  AND q.is_being_delivered = FALSE
               RETURNING q.id
               """.formatted(messageTable, keyStateTable);
    }

    /**
     * Per-key <b>run</b> claim: hands one claimer the next {@code :runLength} messages of a key, in order,
     * rather than only its head.
     *
     * <h2>Why this is the cursor's real advantage</h2>
     * Ordered acknowledgement cannot be batched by deferring it — under either design, a key's successor may
     * not be delivered until the predecessor's completion is durably recorded, so deferring the record stalls
     * the key. That is a property of per-key ordering with one message in flight, not of the barrier, and the
     * cursor does not change it.
     * <p>
     * What the cursor does change is how many of a key's messages can be claimed at once. The barrier's
     * {@code NOT EXISTS (… key_order < mine)} is evaluated per candidate row, so a key can only ever yield its
     * single head — raising the limit returns nothing extra. The cursor's condition is
     * {@code key_order > completed_through}, a range rather than a per-row test, so the next N messages of a key
     * fall out of one index scan.
     * <p>
     * That is what makes an ordered acknowledgement batch possible: one claimer owns a contiguous run, handles
     * it in order, and acknowledges the whole run in one statement and one transaction. Per-key exclusivity is
     * preserved because a single claimer owns the run, and ordering because it processes in {@code key_order}.
     * The §7 saving then applies to ordered traffic — which is the payoff the cursor is worth, stated correctly.
     *
     * <h2>Two constraints, both found by test rather than by reading</h2>
     * <ul>
     *     <li><b>The run must be a prefix, including blocked rows.</b> A first attempt simply filtered
     *     ineligible rows out of the run, which handed a claimer orders 5 and 7 with 6 dead-lettered between
     *     them — reintroducing the skipping fault by another route. The {@code bool_and} window truncates the
     *     run at the first row that cannot be claimed. Note the cost: the inner scan must see ineligible rows,
     *     so like the acknowledgement clamp it needs the non-partial {@code (queue_name, key, key_order)}
     *     index.</li>
     *     <li><b>The caller must sort by {@code key_order}.</b> {@code UPDATE … RETURNING} emits rows in
     *     executor order, not index order — a run of 0,1,2 came back as 1,2,0. A consumer handling them as
     *     returned would violate the ordering the whole design exists to preserve, so {@code key_order} is
     *     returned to make sorting possible and the requirement explicit.</li>
     * </ul>
     */
    public static String claimOrderedRunViaSafeCursorSql(String messageTable, String keyStateTable) {
        return """
               WITH candidate AS (
                 SELECT head.id, head.key_order
                   FROM %2$s ks
                   CROSS JOIN LATERAL (
                     SELECT prefix.id, prefix.key_order
                       FROM (
                         SELECT m.id,
                                m.key_order,
                                -- TRUE only while every row up to and including this one is claimable. A
                                -- dead-lettered or not-yet-due row flips it FALSE and it stays FALSE, which
                                -- truncates the run there.
                                bool_and(m.is_dead_letter_message = FALSE
                                         AND m.is_being_delivered = FALSE
                                         AND m.next_delivery_ts  <= :now)
                                  OVER (ORDER BY m.key_order ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS prefix_claimable
                           FROM %1$s m
                          WHERE m.queue_name = ks.queue_name
                            AND m.key        = ks.key
                            AND m.key_order  > ks.completed_through
                          ORDER BY m.key_order
                       ) prefix
                      WHERE prefix.prefix_claimable
                      ORDER BY prefix.key_order
                      LIMIT :runLength
                   ) head
                  WHERE ks.queue_name = :queueName
                    AND NOT EXISTS (
                      SELECT 1
                        FROM %1$s inflight
                       WHERE inflight.queue_name         = ks.queue_name
                         AND inflight.key                = ks.key
                         AND inflight.is_being_delivered = TRUE
                    )
                  LIMIT :limit
               )
               UPDATE %1$s q
                  SET total_attempts     = q.total_attempts + 1,
                      next_delivery_ts   = NULL,
                      is_being_delivered = TRUE,
                      delivery_ts        = :now
                 FROM candidate c
                WHERE q.id = c.id
                  AND q.is_being_delivered = FALSE
               -- key_order is returned because the caller MUST sort by it. UPDATE ... RETURNING emits rows in
               -- whatever order the executor produces them - a run of 0,1,2 came back as 1,2,0 - so a consumer
               -- that handled them as returned would violate the very ordering this design exists to preserve.
               RETURNING q.id, q.key_order
               """.formatted(messageTable, keyStateTable);
    }

    /**
     * Gap-safe cursor advance.
     *
     * <h2>What was wrong with {@link #ackOrderedViaCursorSql}</h2>
     * It sets {@code completed_through = MAX(key_order)} over the acknowledged batch. Suppose orders 5 and 7
     * for a key are handled while 6 is sitting in the table retried with a future {@code next_delivery_ts}, or
     * dead-lettered. The cursor jumps to 7, and because the claim only ever looks <em>above</em> the cursor,
     * order 6 becomes permanently invisible. That is message loss, not merely reordering — and it is a
     * capability the {@code NOT EXISTS} barrier has for free, since a retried or dead-lettered predecessor
     * simply keeps blocking its successors. The update was also unguarded, so a late acknowledgement carrying
     * a lower maximum could move a cursor backwards.
     *
     * <h2>The fix</h2>
     * Advance to the highest acknowledged order, but never past a row still blocking below the run, and never
     * backwards:
     * <pre>{@code GREATEST(current, LEAST(max_acknowledged, lowest_blocking_below_run - 1))}</pre>
     *
     * <h2>This statement is coupled to the run claim, deliberately</h2>
     * The interval scanned is {@code (cursor, min_acknowledged)} — below the run's first element, not below its
     * last. That is what lets a run of three advance the cursor across all three, and it is sound <b>because
     * {@link #claimOrderedRunViaSafeCursorSql} only ever produces prefixes</b>: its {@code bool_and} window
     * truncates at the first row that cannot be claimed, so everything between the cursor and the run's start
     * is already acknowledged or absent.
     * <p>
     * Bounding by {@code max_acknowledged} instead would be independently safe for arbitrary batches, but then a
     * run cannot advance the cursor at all — every row in the interval is one being deleted, the clamp pulls
     * back to the cursor's old value, and the gap scan grows from a stale cursor until it degrades. So the two
     * statements are correct together and neither is safe with an arbitrary batch from elsewhere. Any other
     * acknowledgement path for ordered messages must preserve the prefix property or it will skip a blocked
     * message.
     */
    public static String ackOrderedViaSafeCursorSql(String messageTable, String keyStateTable) {
        return """
               WITH deleted AS (
                 DELETE FROM %1$s WHERE id IN (<ids>) RETURNING id, queue_name, key, key_order
               ), highest AS (
                 SELECT queue_name, key, MAX(key_order) AS max_order, MIN(key_order) AS min_order
                   FROM deleted GROUP BY queue_name, key
               ), clamped AS (
                 SELECT h.queue_name,
                        h.key,
                        LEAST(h.max_order, COALESCE(gap.min_order - 1, h.max_order)) AS advance_to
                   FROM highest h
                   JOIN %2$s ks
                     ON ks.queue_name = h.queue_name AND ks.key = h.key
                   LEFT JOIN LATERAL (
                     -- Only the OPEN INTERVAL between the cursor and the order just acknowledged. Anything in
                     -- there is a row the claim could not take - dead-lettered, or not yet due - and it must
                     -- keep blocking, so the cursor stops below it.
                     --
                     -- No anti-join against the acknowledged rows is needed, and adding one was expensive.
                     -- The exclusive claim admits at most one message per key per batch, so max_order IS that
                     -- message's order and this interval is strictly below it - the acknowledged row cannot
                     -- fall inside. Two earlier attempts paid dearly for the redundant guard: "id NOT IN
                     -- (<ids>)" over every row of the key is quadratic in batch size (>15 min per case), and
                     -- replacing it with NOT EXISTS over the deleted CTE inside this LATERAL still cost 128s
                     -- against 284ms unguarded, because the CTE is re-scanned per candidate row. Here the range
                     -- is normally empty and costs an index probe.
                     SELECT MIN(m.key_order) AS min_order
                       FROM %1$s m
                      WHERE m.queue_name = h.queue_name
                        AND m.key        = h.key
                        AND m.key_order  > ks.completed_through
                        -- Bounded by the LOWEST order acknowledged for the key, not the highest. Rows between
                        -- the cursor and the run's first element are rows the claim could not take, and they
                        -- must keep blocking. Rows inside the run are excluded automatically, which is what
                        -- lets a multi-message run advance the cursor across the whole of it.
                        AND m.key_order  < h.min_order
                   ) gap ON TRUE
               )
               UPDATE %2$s ks
                  SET completed_through = GREATEST(ks.completed_through, clamped.advance_to)
                 FROM clamped
                WHERE ks.queue_name = clamped.queue_name
                  AND ks.key        = clamped.key
               """.formatted(messageTable, keyStateTable);
    }
}
