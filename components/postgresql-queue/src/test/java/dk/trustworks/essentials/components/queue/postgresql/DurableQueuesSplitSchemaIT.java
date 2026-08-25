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

import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * The schemas the split (S3) is built on: an unordered table and an ordered table, each with the index set the
 * evidence supports rather than the one v1 carries.
 *
 * <h2>What is worth pinning here and why</h2>
 * The split's <em>prototype</em> benefit — 1.38× total, 1.62× on insert for unordered traffic, and ~1.1-1.36×
 * through the component — was attributed to index count, six
 * secondary indexes down to one. So the schemas are the load-bearing part, and two properties of them are easy to
 * get wrong on inspection:
 * <ul>
 *     <li><b>The index sets.</b> §17 measured {@code idx_*_ordered_ready} at zero scans at both 8 and 200 ordered
 *     keys, and {@code idx_*_ordered_msg} superseded by the unique index once that exists. The ordered table
 *     therefore gets two indexes, not the three v1 carries for ordered traffic. Inheriting v1's set by inspection
 *     is exactly how the redundant one came to exist.</li>
 *     <li><b>That the columns are the same as v1's.</b> Trimming them was tried and reverted: every one of v1's
 *     statements references {@code key}, {@code key_order} or {@code delivery_mode}, so a narrower table would
 *     mean rewriting the whole SQL surface — and column width was never what the split's win was attributed to.
 *     Keeping them is what lets each split table be driven by v1's existing, tested statements unchanged, which
 *     is why it is asserted rather than assumed.</li>
 * </ul>
 */
@Testcontainers
class DurableQueuesSplitSchemaIT {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("split-schema-db");

    private static final String UNORDERED_TABLE = "split_unordered_queue";
    private static final String ORDERED_TABLE   = "split_ordered_queue";

    private JdbiUnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + UNORDERED_TABLE);
            unitOfWork.handle().execute("DROP TABLE IF EXISTS " + ORDERED_TABLE);
        });
    }

    @Test
    void the_unordered_table_carries_exactly_one_secondary_index() {
        var sql = new DurableQueuesSql(UNORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute(sql.getCreateSplitQueueTableSql());
            unitOfWork.handle().execute(sql.getCreateSplitUnorderedReadyIndexSql());
        });

        // The point of the split, in one assertion: one index where the shared table needs several.
        assertThat(secondaryIndexesOf(UNORDERED_TABLE)).containsExactly("idx_" + UNORDERED_TABLE + "_ready");
    }

    @Test
    void both_split_tables_keep_v1s_columns_so_v1s_statements_can_drive_them() {
        var sql = new DurableQueuesSql(ORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle().execute(sql.getCreateSplitQueueTableSql()));

        // Every one of these is referenced by v1's existing statements - claimUnorderedSql filters on
        // `key IS NULL`, the row mapper reads delivery_mode - so their presence is what makes reuse possible.
        assertThat(columnsOf(ORDERED_TABLE)).contains("key", "key_order", "delivery_mode",
                                                     "is_being_delivered", "is_dead_letter_message",
                                                     "next_delivery_ts", "total_attempts", "redelivery_attempts");
    }

    @Test
    void the_ordered_table_carries_two_secondary_indexes() {
        var sql = new DurableQueuesSql(ORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute(sql.getCreateSplitQueueTableSql());
            unitOfWork.handle().execute(sql.getCreateSplitOrderedHeadIndexSql());
            unitOfWork.handle().execute(sql.getCreateSplitOrderedKeyIndexSql(true));
        });

        // Two, not the three v1 carries for ordered traffic - see §17.
        assertThat(secondaryIndexesOf(ORDERED_TABLE))
                .containsExactlyInAnyOrder("idx_" + ORDERED_TABLE + "_head", "idx_" + ORDERED_TABLE + "_key");
    }

    /**
     * The duplicate strategy carries over to the split: {@code REJECT} makes the per-key index unique, and unlike
     * the shared table's version it needs no {@code WHERE key IS NOT NULL}, because every row here has one.
     */
    @Test
    void the_ordered_key_index_is_unique_only_under_REJECT() {
        var sql = new DurableQueuesSql(ORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute(sql.getCreateSplitQueueTableSql());
            unitOfWork.handle().execute(sql.getCreateSplitOrderedKeyIndexSql(true));
        });

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> insertOrdered(unitOfWork.handle(), "a", "key-1", 0));
        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> insertOrdered(unitOfWork.handle(), "b", "key-1", 0)))
                .as("REJECT must refuse a duplicate key and order")
                // Named specifically: asserting merely that "something threw" is how this test previously passed
                // while the insert was failing on an unrelated null violation.
                .rootCause()
                .hasMessageContaining("duplicate key value violates unique constraint")
                .hasMessageContaining("idx_" + ORDERED_TABLE + "_key");
        // Same key, different order still accepted - the index must not be broader than the defect it closes.
        assertThatNoException().isThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> insertOrdered(unitOfWork.handle(), "c", "key-1", 1)));
    }

    @Test
    void the_ordered_key_index_permits_duplicates_under_ALLOW() {
        var sql = new DurableQueuesSql(ORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute(sql.getCreateSplitQueueTableSql());
            unitOfWork.handle().execute(sql.getCreateSplitOrderedKeyIndexSql(false));
        });

        assertThatNoException().isThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            insertOrdered(unitOfWork.handle(), "a", "key-1", 0);
            insertOrdered(unitOfWork.handle(), "b", "key-1", 0);
        }));
    }

    /**
     * {@code delivery_mode} is included because it is {@code NOT NULL}, and omitting it made the REJECT test above
     * pass for the wrong reason: the insert failed on a null violation rather than on the unique index, so the
     * test would have passed with no unique index at all. Its sibling — the ALLOW case, which expects the insert
     * to succeed — is what exposed that.
     */
    private static void insertOrdered(org.jdbi.v3.core.Handle handle, String id, String key, long order) {
        handle.execute("INSERT INTO " + ORDERED_TABLE
                               + " (id, queue_name, message_payload, message_payload_type, added_ts, delivery_mode, key, key_order)"
                               + " VALUES (?, 'q', '{}'::jsonb, 'T', now(), 'IN_ORDER', ?, ?)", id, key, order);
    }

    private List<String> columnsOf(String table) {
        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                        .createQuery("SELECT column_name FROM information_schema.columns WHERE table_name = :table")
                                                                        .bind("table", table)
                                                                        .mapTo(String.class)
                                                                        .list());
    }

    private List<String> secondaryIndexesOf(String table) {
        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                        .createQuery("""
                                                                                     SELECT i.relname
                                                                                       FROM pg_index x
                                                                                       JOIN pg_class c ON c.oid = x.indrelid
                                                                                       JOIN pg_class i ON i.oid = x.indexrelid
                                                                                      WHERE c.relname = :table AND NOT x.indisprimary
                                                                                      ORDER BY i.relname
                                                                                     """)
                                                                        .bind("table", table)
                                                                        .mapTo(String.class)
                                                                        .list());
    }
}
