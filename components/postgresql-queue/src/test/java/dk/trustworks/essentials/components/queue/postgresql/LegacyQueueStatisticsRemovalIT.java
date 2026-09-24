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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue statistics feature was removed in 0.60. Its trigger sat on the <em>queue</em> table, so a deployment
 * that upgrades without dropping it keeps executing it on every acknowledged message for a table nothing reads.
 * {@code PostgresqlDurableQueues} therefore removes the trigger, its function and the statistics table on startup.
 * <p>
 * The removal has to run exactly once. The statistics table name was configurable, so a {@code DROP TABLE}
 * re-issued on every boot would destroy a table that a later deployment happened to create under the same name.
 */
@Testcontainers
class LegacyQueueStatisticsRemovalIT {
    private static final String QUEUE_TABLE_NAME      = "durable_queues";
    private static final String STATISTICS_TABLE_NAME = "custom_queue_stats";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("legacy-queue-stats-db");

    private JdbiUnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + QUEUE_TABLE_NAME + " CASCADE");
            uow.handle().execute("DROP TABLE IF EXISTS " + STATISTICS_TABLE_NAME + " CASCADE");
            uow.handle().execute("DROP FUNCTION IF EXISTS log_message_delivery_stats()");
        });
    }

    @Test
    void the_statistics_trigger_function_and_table_are_removed_on_startup() {
        createDurableQueues();
        installLegacyQueueStatistics();
        assertThat(triggerExists()).isTrue();

        createDurableQueues();

        assertThat(triggerExists()).as("trigger").isFalse();
        assertThat(functionExists()).as("function").isFalse();
        assertThat(tableExists(STATISTICS_TABLE_NAME)).as("statistics table").isFalse();
    }

    @Test
    void the_removal_does_not_run_again_once_the_trigger_is_gone() {
        createDurableQueues();
        installLegacyQueueStatistics();
        createDurableQueues();
        assertThat(tableExists(STATISTICS_TABLE_NAME)).isFalse();

        // A later deployment creates its own table that happens to reuse the old statistics table's name.
        unitOfWorkFactory.usingUnitOfWork(uow ->
                uow.handle().execute("CREATE TABLE " + STATISTICS_TABLE_NAME + " (id TEXT PRIMARY KEY)"));

        createDurableQueues();

        assertThat(tableExists(STATISTICS_TABLE_NAME))
                .as("a table that merely reuses the name must survive every subsequent startup")
                .isTrue();
    }

    @Test
    void a_table_whose_shape_is_not_the_statistics_table_is_left_alone() {
        createDurableQueues();
        installLegacyQueueStatistics();
        // Same name, but not the statistics table the framework created.
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE " + STATISTICS_TABLE_NAME);
            uow.handle().execute("CREATE TABLE " + STATISTICS_TABLE_NAME + " (id TEXT PRIMARY KEY, note TEXT)");
        });

        createDurableQueues();

        assertThat(triggerExists()).as("trigger is removed regardless").isFalse();
        assertThat(functionExists()).as("function is removed regardless").isFalse();
        assertThat(tableExists(STATISTICS_TABLE_NAME)).as("foreign table").isTrue();
    }

    private PostgresqlDurableQueues createDurableQueues() {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                      .setSharedQueueTableName(QUEUE_TABLE_NAME)
                                      .build();
    }

    /**
     * The DDL {@code PostgresqlDurableQueuesStatistics} installed up to 0.50, reproduced so the removal has
     * something real to remove. Uses a non-default statistics table name, because recovering the configured name
     * is the part that cannot be done by assuming the default.
     */
    private void installLegacyQueueStatistics() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("""
                                 CREATE TABLE IF NOT EXISTS %s (
                                     id                     TEXT PRIMARY KEY,
                                     queue_name             TEXT NOT NULL,
                                     added_ts               TIMESTAMPTZ NOT NULL,
                                     delivery_ts            TIMESTAMPTZ NOT NULL,
                                     deletion_ts            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                     total_attempts         INTEGER NOT NULL,
                                     redelivery_attempts    INTEGER NOT NULL,
                                     delivery_mode          TEXT NOT NULL,
                                     delivery_latency       INTERVAL NOT NULL,
                                     delivery_error         BOOLEAN NOT NULL,
                                     meta_data              JSONB DEFAULT NULL
                                 )
                                 """.formatted(STATISTICS_TABLE_NAME));
            uow.handle().execute("""
                                 CREATE OR REPLACE FUNCTION log_message_delivery_stats() RETURNS TRIGGER AS $$
                                     BEGIN
                                       BEGIN
                                         INSERT INTO %s (
                                             id, queue_name, added_ts, delivery_ts, deletion_ts,
                                             total_attempts, redelivery_attempts, delivery_mode,
                                             delivery_latency, delivery_error, meta_data
                                         )
                                         VALUES (
                                             OLD.id, OLD.queue_name, OLD.added_ts, OLD.delivery_ts, NOW(),
                                             OLD.total_attempts, OLD.redelivery_attempts, OLD.delivery_mode,
                                             NOW() - OLD.added_ts, OLD.last_delivery_error IS NOT NULL, OLD.meta_data
                                         );
                                       EXCEPTION WHEN OTHERS THEN
                                         RAISE NOTICE 'Trigger insert into queue message stats failed: %%', SQLERRM;
                                       END;
                                       RETURN OLD;
                                     END;
                                     $$ LANGUAGE plpgsql;
                                 """.formatted(STATISTICS_TABLE_NAME));
            uow.handle().execute("""
                                 CREATE TRIGGER trg_log_message_delivery_stats
                                 AFTER DELETE ON %s
                                 FOR EACH ROW
                                 EXECUTE FUNCTION log_message_delivery_stats()
                                 """.formatted(QUEUE_TABLE_NAME));
        });
    }

    private boolean triggerExists() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("""
                                                                       SELECT EXISTS (SELECT 1 FROM pg_trigger t
                                                                                      JOIN pg_class c ON c.oid = t.tgrelid
                                                                                      WHERE t.tgname = 'trg_log_message_delivery_stats'
                                                                                        AND c.relname = :queueTableName)
                                                                       """)
                                                          .bind("queueTableName", QUEUE_TABLE_NAME)
                                                          .mapTo(Boolean.class)
                                                          .one());
    }

    private boolean functionExists() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'log_message_delivery_stats')")
                                                          .mapTo(Boolean.class)
                                                          .one());
    }

    private boolean tableExists(String tableName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = :tableName)")
                                                          .bind("tableName", tableName)
                                                          .mapTo(Boolean.class)
                                                          .one());
    }
}
