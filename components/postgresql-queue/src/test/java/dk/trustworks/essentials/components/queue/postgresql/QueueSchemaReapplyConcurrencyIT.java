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
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.test.schema.SchemaReapplyLockProbe;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every instance re-applies the queue schema when it starts, while other instances keep using the table. The
 * change-notification trigger step used to {@code DROP TRIGGER} and re-create it on every boot. The index changes
 * before it in the same transaction already hold a {@code SHARE} lock on the table - {@code CREATE INDEX IF NOT EXISTS}
 * takes it even when the index exists - so the drop's {@code ACCESS EXCLUSIVE} was a lock upgrade. A running
 * transaction that had read the table and then wrote it (the claim's CTE takes its read lock before the
 * {@code UPDATE}'s) closed the cycle, and PostgreSQL aborted one side with "deadlock detected": seen as a failed
 * startup in the performance lab, whose comparison scenario starts a second application context against the same
 * database while the first one runs.
 */
@Testcontainers
class QueueSchemaReapplyConcurrencyIT {
    private static final String QUEUE_TABLE_NAME = "durable_queues";
    /** pg_trigger.tgtype of AFTER INSERT OR UPDATE ... FOR EACH ROW */
    private static final int    NOTIFY_TRIGGER_TYPE = 1 | 4 | 16;

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("queue-schema-reapply-db");

    private JdbiUnitOfWorkFactory                               unitOfWorkFactory;
    private MultiTableChangeListener<TableChangeNotification> multiTableChangeListener;
    private PostgresqlDurableQueues                             durableQueues;
    private ExecutorService                                     executor;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                  postgreSQLContainer.getUsername(),
                                                                  postgreSQLContainer.getPassword()));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + QUEUE_TABLE_NAME + " CASCADE");
            uow.handle().execute("DROP FUNCTION IF EXISTS notify_" + QUEUE_TABLE_NAME + "_change()");
        });
        var jsonSerializer = EssentialsObjectMappers.createJSONSerializer();
        multiTableChangeListener = new MultiTableChangeListener<>(unitOfWorkFactory.getJdbi(),
                                                                  Duration.ofMillis(50),
                                                                  jsonSerializer,
                                                                  LocalEventBus.builder().busName("queue-schema-reapply").build(),
                                                                  true);
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(jsonSerializer)
                                               .setSharedQueueTableName(QUEUE_TABLE_NAME)
                                               .setMultiTableChangeListener(multiTableChangeListener)
                                               .build();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (multiTableChangeListener != null) {
            multiTableChangeListener.close();
        }
    }

    @Test
    void reapplying_the_schema_does_not_deadlock_with_a_transaction_that_read_the_queue_table_and_then_writes_it() throws Exception {
        assertThat(notifyTriggerType()).isEqualTo(NOTIFY_TRIGGER_TYPE);

        try (var otherInstance = unitOfWorkFactory.getJdbi().open()) {
            otherInstance.begin();
            otherInstance.createQuery("SELECT count(*) FROM " + QUEUE_TABLE_NAME).mapTo(Long.class).one();

            var reapply = executor.submit(() -> PostgresqlCreateSchemaApplier.applyOwnSchema(unitOfWorkFactory, durableQueues));
            // Before the fix the re-apply stops here holding SHARE on the table and waiting for ACCESS EXCLUSIVE
            Awaitility.await()
                      .atMost(Duration.ofSeconds(30))
                      .until(() -> reapply.isDone() || someoneWaitsForALockOnTheQueueTable());

            // Takes ROW EXCLUSIVE, as the claim's UPDATE and every enqueue and acknowledgement do
            otherInstance.execute("DELETE FROM " + QUEUE_TABLE_NAME + " WHERE false");
            otherInstance.commit();

            reapply.get(30, TimeUnit.SECONDS);
        }

        assertThat(notifyTriggerType()).isEqualTo(NOTIFY_TRIGGER_TYPE);
    }

    /**
     * {@code CREATE INDEX IF NOT EXISTS} takes a {@code SHARE} lock even when the index exists, so every start waited
     * for the enqueues, claims and acknowledgements in flight on other instances and blocked the next ones until the
     * queue's schema transaction committed
     */
    @Test
    void reapplying_the_schema_does_not_wait_for_queue_operations_in_flight() {
        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(unitOfWorkFactory.getJdbi(), durableQueues);
    }

    @Test
    void a_missing_trigger_is_created_again() {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TRIGGER notify_on_" + QUEUE_TABLE_NAME + "_changes ON " + QUEUE_TABLE_NAME));
        assertThat(notifyTriggerType()).isNull();

        PostgresqlCreateSchemaApplier.applyOwnSchema(unitOfWorkFactory, durableQueues);

        assertThat(notifyTriggerType()).isEqualTo(NOTIFY_TRIGGER_TYPE);
    }

    @Test
    void a_trigger_on_other_operations_is_replaced() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TRIGGER notify_on_" + QUEUE_TABLE_NAME + "_changes ON " + QUEUE_TABLE_NAME);
            uow.handle().execute("CREATE TRIGGER notify_on_" + QUEUE_TABLE_NAME + "_changes AFTER INSERT ON " + QUEUE_TABLE_NAME +
                                         " FOR EACH ROW EXECUTE FUNCTION notify_" + QUEUE_TABLE_NAME + "_change()");
        });
        assertThat(notifyTriggerType()).isEqualTo(1 | 4);

        PostgresqlCreateSchemaApplier.applyOwnSchema(unitOfWorkFactory, durableQueues);

        assertThat(notifyTriggerType()).isEqualTo(NOTIFY_TRIGGER_TYPE);
    }

    private boolean someoneWaitsForALockOnTheQueueTable() {
        return unitOfWorkFactory.getJdbi().withHandle(handle -> handle.createQuery("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE relation = to_regclass(:table) AND NOT granted)")
                                                                      .bind("table", QUEUE_TABLE_NAME)
                                                                      .mapTo(Boolean.class)
                                                                      .one());
    }

    private Integer notifyTriggerType() {
        return unitOfWorkFactory.getJdbi().withHandle(handle -> handle.createQuery("SELECT tgtype::int FROM pg_trigger WHERE tgrelid = to_regclass(:table) AND tgname = :trigger")
                                                                      .bind("table", QUEUE_TABLE_NAME)
                                                                      .bind("trigger", "notify_on_" + QUEUE_TABLE_NAME + "_changes")
                                                                      .mapTo(Integer.class)
                                                                      .findOne()
                                                                      .orElse(null));
    }
}
