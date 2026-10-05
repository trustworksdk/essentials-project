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
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PostgresqlDurableSubscriptionRepository.DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code reposition_epoch} guard: a save that captured a resume point before a reset must never overwrite the
 * reset, whichever of the two commits first. Two {@link SubscriptionResumePoint} instances for the same row stand in
 * for the stale save and the reset, which makes both commit orders deterministic
 */
@Testcontainers
class PostgresqlDurableSubscriptionRepositoryIT {
    private static final AggregateType ORDERS        = AggregateType.of("Orders");
    private static final SubscriberId  SUBSCRIBER_ID = SubscriberId.of("OrdersSub1");

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4").withDatabaseName("event-store")
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
