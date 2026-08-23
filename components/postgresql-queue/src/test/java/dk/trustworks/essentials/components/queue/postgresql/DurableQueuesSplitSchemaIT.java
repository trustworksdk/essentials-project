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
 * The split's entire measured benefit — 1.38× total, 1.62× on insert for unordered traffic — is index count, six
 * secondary indexes down to one. So the schemas are the load-bearing part, and two properties of them are easy to
 * get wrong on inspection:
 * <ul>
 *     <li><b>The index sets.</b> §17 measured {@code idx_*_ordered_ready} at zero scans at both 8 and 200 ordered
 *     keys, and {@code idx_*_ordered_msg} superseded by the unique index once that exists. The ordered table
 *     therefore gets two indexes, not the three v1 carries for ordered traffic. Inheriting v1's set by inspection
 *     is exactly how the redundant one came to exist.</li>
 *     <li><b>The columns each table does not have.</b> The unordered table has no {@code key}, {@code key_order}
 *     or {@code delivery_mode}; the ordered table has {@code key} and {@code key_order} as {@code NOT NULL}. Those
 *     are guarantees the shared table cannot make, because it has to hold both kinds, and they are what let each
 *     table carry a smaller index set.</li>
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
    void the_unordered_table_carries_no_ordering_columns_and_exactly_one_secondary_index() {
        var sql = new DurableQueuesSql(UNORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute(sql.getCreateUnorderedQueueTableSql());
            unitOfWork.handle().execute(sql.getCreateSplitUnorderedReadyIndexSql());
        });

        assertThat(columnsOf(UNORDERED_TABLE)).doesNotContain("key", "key_order", "delivery_mode");
        // The point of the split, in one assertion: one index where the shared table needs several.
        assertThat(secondaryIndexesOf(UNORDERED_TABLE)).containsExactly("idx_" + UNORDERED_TABLE + "_ready");
    }

    @Test
    void the_ordered_table_requires_key_and_key_order_and_carries_two_secondary_indexes() {
        var sql = new DurableQueuesSql(ORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute(sql.getCreateOrderedQueueTableSql());
            unitOfWork.handle().execute(sql.getCreateSplitOrderedHeadIndexSql());
            unitOfWork.handle().execute(sql.getCreateSplitOrderedKeyIndexSql(true));
        });

        // Two, not the three v1 carries for ordered traffic - see §17.
        assertThat(secondaryIndexesOf(ORDERED_TABLE))
                .containsExactlyInAnyOrder("idx_" + ORDERED_TABLE + "_head", "idx_" + ORDERED_TABLE + "_key");

        // NOT NULL is the guarantee the shared table cannot make, so it is asserted against the database rather
        // than read off the DDL.
        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                                           .execute("INSERT INTO " + ORDERED_TABLE
                                                                                                            + " (id, queue_name, message_payload, message_payload_type, added_ts, key, key_order)"
                                                                                                            + " VALUES ('x', 'q', '{}'::jsonb, 'T', now(), NULL, 0)")))
                .as("an ordered row without a key is meaningless and the table must refuse it")
                .isNotNull();
    }

    /**
     * The duplicate strategy carries over to the split: {@code REJECT} makes the per-key index unique, and unlike
     * the shared table's version it needs no {@code WHERE key IS NOT NULL}, because every row here has one.
     */
    @Test
    void the_ordered_key_index_is_unique_only_under_REJECT() {
        var sql = new DurableQueuesSql(ORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute(sql.getCreateOrderedQueueTableSql());
            unitOfWork.handle().execute(sql.getCreateSplitOrderedKeyIndexSql(true));
        });

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> insertOrdered(unitOfWork.handle(), "a", "key-1", 0));
        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> insertOrdered(unitOfWork.handle(), "b", "key-1", 0)))
                .as("REJECT must refuse a duplicate key and order")
                .isNotNull();
        // Same key, different order still accepted - the index must not be broader than the defect it closes.
        assertThatNoException().isThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> insertOrdered(unitOfWork.handle(), "c", "key-1", 1)));
    }

    @Test
    void the_ordered_key_index_permits_duplicates_under_ALLOW() {
        var sql = new DurableQueuesSql(ORDERED_TABLE);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.handle().execute(sql.getCreateOrderedQueueTableSql());
            unitOfWork.handle().execute(sql.getCreateSplitOrderedKeyIndexSql(false));
        });

        assertThatNoException().isThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            insertOrdered(unitOfWork.handle(), "a", "key-1", 0);
            insertOrdered(unitOfWork.handle(), "b", "key-1", 0);
        }));
    }

    private static void insertOrdered(org.jdbi.v3.core.Handle handle, String id, String key, long order) {
        handle.execute("INSERT INTO " + ORDERED_TABLE
                               + " (id, queue_name, message_payload, message_payload_type, added_ts, key, key_order)"
                               + " VALUES (?, 'q', '{}'::jsonb, 'T', now(), ?, ?)", id, key, order);
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
