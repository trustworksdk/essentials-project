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
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Migrating an existing v1 backlog onto the split.
 *
 * <h2>Why this needs to exist at all</h2>
 * The split reads {@code <base>_unordered} and {@code <base>_ordered} and never the v1 shared table. So a
 * deployment that switches with messages still queued does not lose them — it stops delivering them, silently,
 * which is worse than an error. `migrateFromSharedTable` is the answer, and these tests pin the three properties
 * that make it trustworthy: everything moves, nothing is altered in transit, and it refuses to run while the old
 * side is live.
 */
@Testcontainers
class PostgresqlSplitDurableQueuesMigrationIT {

    private static final String LEGACY_TABLE = "legacy_durable_queues";
    private static final String BASE_TABLE   = "migrated_queues";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("split-migration-db");

    private JdbiUnitOfWorkFactory        unitOfWorkFactory;
    private PostgresqlDurableQueues      legacyQueues;
    private PostgresqlSplitDurableQueues splitQueues;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + LEGACY_TABLE);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX);
        });

        legacyQueues = PostgresqlDurableQueues.builder()
                                              .setUnitOfWorkFactory(unitOfWorkFactory)
                                              .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                              .setSharedQueueTableName(LEGACY_TABLE)
                                              .build();
        legacyQueues.start();

        splitQueues = PostgresqlSplitDurableQueues.builder()
                                                  .setUnitOfWorkFactory(unitOfWorkFactory)
                                                  .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                  .setBaseQueueTableName(BASE_TABLE)
                                                  .build();
        splitQueues.start();
    }

    @AfterEach
    void tearDown() {
        if (splitQueues != null) {
            splitQueues.stop();
        }
        if (legacyQueues != null) {
            legacyQueues.stop();
        }
    }

    /**
     * The whole backlog arrives, routed by mode, and is readable through the split's own API afterwards — which is
     * the property that matters: a row that landed in the right table but cannot be read back has not migrated.
     */
    @Test
    void a_backlog_of_both_modes_moves_into_the_right_tables_and_is_readable_afterwards() {
        var queueName = QueueName.of("Backlog");
        var unorderedIds = new ArrayList<QueueEntryId>();
        var orderedIds   = new ArrayList<QueueEntryId>();
        for (var i = 0; i < 10; i++) {
            unorderedIds.add(legacyQueues.queueMessage(queueName, Message.of("plain-" + i)));
            orderedIds.add(legacyQueues.queueMessage(queueName, OrderedMessage.of("ordered-" + i, "key-" + (i % 3), i)));
        }

        var result = splitQueues.migrateFromSharedTable(LEGACY_TABLE);

        assertThat(result.unorderedMessagesMoved()).isEqualTo(10);
        assertThat(result.orderedMessagesMoved()).isEqualTo(10);
        assertThat(result.totalMessagesMoved()).isEqualTo(20);

        assertThat(rowCountOf(LEGACY_TABLE)).as("the shared table is emptied").isZero();
        assertThat(rowCountOf(splitQueues.getUnorderedTableName())).isEqualTo(10L);
        assertThat(rowCountOf(splitQueues.getOrderedTableName())).isEqualTo(10L);

        assertThat(splitQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(20L);
        for (var id : unorderedIds) {
            assertThat(splitQueues.getQueuedMessage(id)).as("unordered %s must be readable after migration", id).isPresent();
        }
        for (var id : orderedIds) {
            assertThat(splitQueues.getQueuedMessage(id)).as("ordered %s must be readable after migration", id).isPresent();
        }
    }

    /**
     * A message keeps its identity and its history. Ids are preserved because anything holding one — an
     * application, an admin bookmark, a dead-letter report — would otherwise be silently pointing at nothing. The
     * attempt counts and the last error matter because a message that has failed four of five allowed deliveries
     * must not come back with a fresh budget.
     */
    @Test
    void a_partially_delivered_message_keeps_its_id_attempts_and_error() {
        var queueName = QueueName.of("History");
        var id        = legacyQueues.queueMessage(queueName, Message.of("payload"));
        legacyQueues.retryMessage(id, new RuntimeException("first failure"), java.time.Duration.ZERO);
        legacyQueues.retryMessage(id, new RuntimeException("second failure"), java.time.Duration.ZERO);

        var before = legacyQueues.getQueuedMessage(id).orElseThrow();

        splitQueues.migrateFromSharedTable(LEGACY_TABLE);

        var after = splitQueues.getQueuedMessage(id).orElseThrow();
        assertThat((CharSequence) after.getId()).isEqualTo(id);
        assertThat(after.getTotalDeliveryAttempts()).isEqualTo(before.getTotalDeliveryAttempts());
        assertThat(after.getRedeliveryAttempts()).isEqualTo(before.getRedeliveryAttempts());
        assertThat(after.getLastDeliveryError()).isEqualTo(before.getLastDeliveryError());
        assertThat(after.getAddedTimestamp()).isEqualTo(before.getAddedTimestamp());
        assertThat(after.getPayload()).isEqualTo(before.getPayload());
    }

    /**
     * Dead letters migrate as dead letters. Moving them across as ordinary queued messages would redeliver a
     * poison batch to a system that had already quarantined it.
     */
    @Test
    void dead_letter_messages_stay_dead_letters() {
        var queueName = QueueName.of("DeadLetters");
        var id        = legacyQueues.queueMessage(queueName, Message.of("poison"));
        legacyQueues.markAsDeadLetterMessage(id, new RuntimeException("poison"));

        splitQueues.migrateFromSharedTable(LEGACY_TABLE);

        assertThat(splitQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(1L);
        assertThat(splitQueues.getTotalMessagesQueuedFor(queueName)).isZero();
        assertThat(splitQueues.getDeadLetterMessage(id)).isPresent();
    }

    /**
     * The safety interlock. A row marked {@code is_being_delivered} is the observable signature of a v1 consumer
     * still running, and migrating out from under it would hand the same message to two instances.
     */
    @Test
    void it_refuses_to_migrate_while_the_shared_table_still_has_messages_being_delivered() {
        var queueName = QueueName.of("StillLive");
        legacyQueues.queueMessage(queueName, Message.of("in-flight"));
        // Exactly what a live v1 consumer's claim leaves behind.
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("UPDATE " + LEGACY_TABLE + " SET is_being_delivered = TRUE"));

        assertThatThrownBy(() -> splitQueues.migrateFromSharedTable(LEGACY_TABLE))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to migrate")
                .hasMessageContaining("still running");

        // And nothing moved - a refused migration must not be a partial one.
        assertThat(rowCountOf(LEGACY_TABLE)).isEqualTo(1L);
        assertThat(rowCountOf(splitQueues.getUnorderedTableName())).isZero();
    }

    /**
     * Migrating a deployment that has nothing queued is the common case, and must not be an error - otherwise the
     * migration cannot be left in a startup script.
     */
    @Test
    void migrating_an_empty_or_absent_shared_table_is_a_no_op() {
        assertThat(splitQueues.migrateFromSharedTable(LEGACY_TABLE).totalMessagesMoved()).isZero();

        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE " + LEGACY_TABLE));
        assertThat(splitQueues.migrateFromSharedTable(LEGACY_TABLE).totalMessagesMoved()).isZero();
    }

    /**
     * Running it twice must not duplicate the backlog - the second run finds an empty table. Worth pinning
     * because the obvious operational instinct on an interrupted deployment is to run it again.
     */
    @Test
    void running_the_migration_twice_moves_nothing_the_second_time() {
        var queueName = QueueName.of("Idempotent");
        for (var i = 0; i < 5; i++) {
            legacyQueues.queueMessage(queueName, Message.of("plain-" + i));
        }

        assertThat(splitQueues.migrateFromSharedTable(LEGACY_TABLE).totalMessagesMoved()).isEqualTo(5);
        assertThat(splitQueues.migrateFromSharedTable(LEGACY_TABLE).totalMessagesMoved()).isZero();
        assertThat(splitQueues.getTotalMessagesQueuedFor(queueName)).isEqualTo(5L);
    }

    private long rowCountOf(String tableName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle()
                                                          .createQuery("SELECT count(*) FROM " + tableName)
                                                          .mapTo(Long.class)
                                                          .one());
    }
}
