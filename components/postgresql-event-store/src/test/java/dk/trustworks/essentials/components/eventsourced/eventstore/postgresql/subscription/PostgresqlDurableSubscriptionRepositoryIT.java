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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PostgresqlDurableSubscriptionRepository.DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code reposition_epoch} guard: a save that captured a resume point before a reset must never overwrite the
 * reset, whichever of the two commits first. Two {@link SubscriptionResumePoint} instances for the same row stand in
 * for the stale save and the reset, which makes both commit orders deterministic
 */
@Testcontainers
class PostgresqlDurableSubscriptionRepositoryIT {
    private static final Logger        log           = LoggerFactory.getLogger(PostgresqlDurableSubscriptionRepositoryIT.class);
    private static final AggregateType ORDERS        = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER_ID = SubscriberId.of("OrdersSub1");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4").withDatabaseName("event-store")
                                                                                                                 .withUsername("test-user")
                                                                                                                 .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class: every test starts without the table
        jdbi.useHandle(handle -> handle.execute("DROP TABLE IF EXISTS " + DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME));

        EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(
                                                                                               EssentialsJSONEventSerializers.create()));
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
    }

    @Test
    void test_a_save_captured_before_a_reset_cannot_overwrite_the_reset() {
        var repository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        repository.createResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(1));
        var staleSave = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();
        var reset     = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();
        staleSave.advanceResumeFromAndIncluding(GlobalEventOrder.of(500));

        reset.setResumeFromAndIncluding(GlobalEventOrder.of(10));
        repository.saveResumePoint(reset);
        repository.saveResumePoint(staleSave);

        assertStored(10, 1);
        assertThat(staleSave.isChanged()).as("the refused save is not retried").isFalse();
    }

    @Test
    void test_a_reset_overwrites_a_save_that_committed_before_it() {
        var repository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        repository.createResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(1));
        var progress = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();
        var reset    = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();
        progress.advanceResumeFromAndIncluding(GlobalEventOrder.of(500));

        repository.saveResumePoint(progress);
        reset.setResumeFromAndIncluding(GlobalEventOrder.of(10));
        repository.saveResumePoint(reset);
        assertStored(10, 1);

        // The writer that missed the reset keeps being refused, however far it advances
        progress.advanceResumeFromAndIncluding(GlobalEventOrder.of(600));
        repository.saveResumePoint(progress);
        assertStored(10, 1);
    }

    @Test
    void test_progress_after_a_reset_is_saved_in_the_new_epoch() {
        var repository  = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        var resumePoint = repository.createResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(1));

        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(10));
        repository.saveResumePoint(resumePoint);
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(20));
        repository.saveResumePoint(resumePoint);

        assertStored(20, 1);
        assertThat(resumePoint.isChanged()).isFalse();
        assertThat(repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow().getRepositionEpoch()).isEqualTo(1);
    }

    @Test
    void test_a_reset_to_the_stored_value_still_fences_off_older_saves() {
        var repository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        repository.createResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(1));
        var staleSave = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();
        var reset     = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();
        staleSave.advanceResumeFromAndIncluding(GlobalEventOrder.of(500));

        reset.setResumeFromAndIncluding(GlobalEventOrder.of(1));
        repository.saveResumePoint(reset);
        repository.saveResumePoint(staleSave);

        assertStored(1, 1);
    }

    @Test
    void test_a_table_created_before_0_60_gets_the_column_and_existing_rows_start_in_epoch_0() {
        // The 0.50 DDL, verbatim
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE " + DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME + " (\n" +
                                                        "subscriber_id TEXT NOT NULL,\n" +
                                                        "aggregate_type TEXT NOT NULL,\n" +
                                                        "resume_from_and_including_global_eventorder bigint,\n" +
                                                        "last_updated TIMESTAMP WITH TIME ZONE,\n" +
                                                        "PRIMARY KEY (subscriber_id, aggregate_type))"));
        jdbi.useHandle(handle -> handle.execute("INSERT INTO " + DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME +
                                                        " VALUES ('" + SUBSCRIBER_ID + "', '" + ORDERS + "', 42, now())"));

        var repository  = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        var resumePoint = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();

        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(42));
        assertThat(resumePoint.getRepositionEpoch()).isZero();
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(50));
        repository.saveResumePoint(resumePoint);
        assertStored(50, 0);
    }

    /**
     * Two subscriptions for the same subscriber - started at the same time, by two threads or two instances - both find
     * no resume point and both create one. The second insert waits for the first to commit and then inserts nothing,
     * which made {@code getOrCreateResumePoint} fail with "a ResumePoint already exists" instead of returning the one
     * the first created
     */
    @Test
    void test_getOrCreateResumePoint_returns_the_resume_point_another_transaction_created_concurrently() throws Exception {
        var repository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        var first      = eventStore.getUnitOfWorkFactory().getOrCreateNewUnitOfWork();
        var executor   = Executors.newSingleThreadExecutor();
        try {
            var created = repository.getOrCreateResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(5));

            var second = executor.submit(() -> repository.getOrCreateResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(7)));
            // The second insert waits on the first one's uncommitted row
            Awaitility.await()
                      .atMost(Duration.ofSeconds(30))
                      .until(() -> second.isDone() || someoneWaitsForATransaction());
            first.commit();

            var foundBySecond = second.get(30, TimeUnit.SECONDS);
            assertThat(foundBySecond.getResumeFromAndIncluding()).isEqualTo(created.getResumeFromAndIncluding());
            assertStored(5, 0);
        } finally {
            // A failure before the commit leaves `first` open, holding its row and its table lock: the second insert waits on
            // it, and so would the next test's DROP TABLE in the shared container. Release it before stopping the executor
            rollbackIfStillOpen(first);
            executor.shutdownNow();
        }
    }

    private static void rollbackIfStillOpen(UnitOfWork unitOfWork) {
        if (unitOfWork.status().isCompleted()) {
            return;
        }
        try {
            unitOfWork.rollback();
        } catch (RuntimeException rollbackFailure) {
            // Thrown from a finally block it would replace the test's own failure
            log.warn("Rolling back the test's open UnitOfWork failed", rollbackFailure);
        }
    }

    private boolean someoneWaitsForATransaction() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted)")
                                               .mapTo(Boolean.class)
                                               .one());
    }

    /**
     * Reproducer for the intermittent failure of
     * {@code EventStoreSubscriptionManager_2_node_exclusivelySubscribeToAggregateEventsAsynchronously_IT#subscribe}
     * (node1's resume point read back as 180 instead of 213), with each step done by the call the subscription code
     * makes, in the order the failing run's log shows:
     * <ol>
     *     <li>node2 holds the fenced lock; its last periodic save stored 178.</li>
     *     <li>The database pauses. node2's I/O retry of #180 is overtaken by #181, so its in-memory resume point is 182.</li>
     *     <li>node1 acquires the lock and reads the row: it starts from 178 ({@code ExclusiveAsynchronousSubscription.subscribeOnLockAcquired}).</li>
     *     <li>node2 learns it lost the lock. Its subscriber is disposed while #180 is retried, and
     *     {@code PersistedEventSubscriber.holdResumePointAt} rewinds the resume point to 180 with
     *     {@code setResumeFromAndIncluding}, which starts reposition epoch 1.</li>
     *     <li>node2's {@code unsubscribeOnLockReleased} then saves it ({@code persistResumePointUntilSettled}), although
     *     the lock now belongs to node1. Saves are not fenced by the lock, and epoch 1 beats the stored epoch 0.</li>
     *     <li>node1 handles 178..212 and saves 213 in epoch 0. The guard refuses this save and every later one for as long
     *     as node1 holds the lock. The stored row stays at 180, behind everything node1 handled.</li>
     * </ol>
     */
    @Test
    @Disabled("Known bug, not fixed: the guard treats the previous lock holder's in-memory hold (an epoch bump) as a reset, so the new holder's saves are refused for its whole tenure. " +
            "Fixing it needs resume point saves fenced by the lock token, or the final save made before the lock is released - both contract changes")
    void test_a_late_save_by_the_previous_lock_holder_does_not_block_the_new_lock_holders_saves() {
        var repository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
        repository.createResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(1));

        // 1. node2 holds the lock and its periodic save stores 178
        var node2 = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();
        node2.advanceResumeFromAndIncluding(GlobalEventOrder.of(178));
        repository.saveResumePoint(node2);
        assertStored(178, 0);

        // 2. #181 completes while #180 is retried on I/O
        node2.advanceResumeFromAndIncluding(GlobalEventOrder.of(182));

        // 3. node1 acquires the lock and reads the row
        var node1 = repository.getOrCreateResumePoint(SUBSCRIBER_ID, ORDERS, aggregateType -> GlobalEventOrder.of(1));
        assertThat(node1.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(178));

        // 4. + 5. node2's subscriber is stopped during #180's retry: the hold rewinds to 180 (epoch 1), then the lock release saves it
        node2.setResumeFromAndIncluding(GlobalEventOrder.of(180));
        repository.saveResumePoint(node2);

        // 6. node1, the lock holder, handled 178..212 and saves
        node1.advanceResumeFromAndIncluding(GlobalEventOrder.of(213));
        repository.saveResumePoint(node1);

        var stored = repository.getResumePoint(SUBSCRIBER_ID, ORDERS).orElseThrow();
        assertThat(stored.getResumeFromAndIncluding())
                .as("the lock holder's progress is stored, not the previous holder's late rewind")
                .isEqualTo(GlobalEventOrder.of(213));
    }

    private void assertStored(long resumeFromAndIncluding, long repositionEpoch) {
        var stored = jdbi.withHandle(handle -> handle.createQuery("SELECT resume_from_and_including_global_eventorder, reposition_epoch FROM " + DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME +
                                                                          " WHERE subscriber_id = :subscriber_id AND aggregate_type = :aggregate_type")
                                                     .bind("subscriber_id", SUBSCRIBER_ID.toString())
                                                     .bind("aggregate_type", ORDERS.toString())
                                                     .map((rs, ctx) -> new long[]{rs.getLong(1), rs.getLong(2)})
                                                     .one());
        assertThat(stored).as("stored (resume_from_and_including_global_eventorder, reposition_epoch)")
                          .containsExactly(resumeFromAndIncluding, repositionEpoch);
    }
}
