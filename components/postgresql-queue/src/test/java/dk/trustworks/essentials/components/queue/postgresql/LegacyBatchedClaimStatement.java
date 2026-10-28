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

import java.util.*;

/**
 * The batched claim as it was before it took each queue's messages under a per-queue {@code LIMIT}: one
 * {@code ROW_NUMBER()} window over every eligible message of every queue, the per-key barrier probed for each ordered
 * one. Kept as the reference the current statement must claim the same rows as, and as the baseline for the
 * batched-claim benchmark. Same bindings as {@link DurableQueuesSql#buildBatchedSqlStatement}.
 */
public final class LegacyBatchedClaimStatement {
    private LegacyBatchedClaimStatement() {
    }

    public static DurableQueuesSql.BatchedSqlResult build(String tableName,
                                                          Map<QueueName, Set<String>> excludeKeysPerQueue,
                                                          Map<QueueName, Integer> availableWorkerSlotsPerQueue,
                                                          List<QueueName> activeQueues) {
        var values              = new StringBuilder();
        var singleValueBindings = new HashMap<String, String>();
        var listBindings        = new HashMap<String, Collection<String>>();
        for (int i = 0; i < activeQueues.size(); i++) {
            var queueName    = activeQueues.get(i);
            var excludedKeys = excludeKeysPerQueue.getOrDefault(queueName, Collections.emptySet());
            singleValueBindings.put("queueName" + i, queueName.toString());
            if (!excludedKeys.isEmpty()) {
                listBindings.put("excludeKeys" + i, excludedKeys);
            }
            if (i > 0) values.append(",\n    ");
            values.append("(:queueName").append(i).append(", ")
                  .append(availableWorkerSlotsPerQueue.get(queueName)).append(", ")
                  .append(excludedKeys.isEmpty() ? "ARRAY[]::text[]" : "ARRAY[<excludeKeys" + i + ">]::text[]")
                  .append(")");
        }
        var sql = """
                  WITH queue_config(queue_name, slots, exclude_keys) AS (
                      VALUES %2$s
                  ),
                  candidates_rn AS (
                    SELECT q.id, cfg.slots,
                           ROW_NUMBER() OVER (PARTITION BY q.queue_name ORDER BY q.next_delivery_ts, q.id) AS rn
                    FROM %1$s q
                    JOIN queue_config cfg USING(queue_name)
                    WHERE q.is_dead_letter_message = FALSE
                      AND q.is_being_delivered     = FALSE
                      AND q.next_delivery_ts      <= :now
                      AND (q.key IS NULL
                           OR (NOT (q.key = ANY(cfg.exclude_keys))
                               AND NOT EXISTS (SELECT 1 FROM %1$s q2
                                               WHERE q2.queue_name = q.queue_name
                                                 AND q2.key        = q.key
                                                 AND q2.key_order  < q.key_order)))
                  ),
                  candidates AS (
                    SELECT c.id FROM candidates_rn c WHERE c.rn <= c.slots
                  ),
                  locked AS (
                    SELECT q.id FROM %1$s q
                    WHERE q.id = ANY(ARRAY(SELECT id FROM candidates))
                      AND q.is_dead_letter_message = FALSE
                      AND q.is_being_delivered     = FALSE
                      AND q.next_delivery_ts      <= :now
                    FOR UPDATE SKIP LOCKED
                  )
                  UPDATE %1$s dq
                  SET total_attempts = dq.total_attempts + 1, next_delivery_ts = NULL, is_being_delivered = TRUE, delivery_ts = :now
                  WHERE dq.id = ANY(ARRAY(SELECT id FROM locked))
                  RETURNING dq.*;
                  """.formatted(tableName, values);
        return new DurableQueuesSql.BatchedSqlResult(sql, singleValueBindings, listBindings);
    }
}
