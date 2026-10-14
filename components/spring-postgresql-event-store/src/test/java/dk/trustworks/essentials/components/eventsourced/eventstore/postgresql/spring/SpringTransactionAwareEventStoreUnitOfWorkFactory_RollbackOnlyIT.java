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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.*;

/**
 * Work run in a {@link SpringTransactionAwareEventStoreUnitOfWorkFactory} unit of work appends an event, then calls an
 * inner Spring transactional participant (here a {@link TransactionTemplate}, as an inner {@code @Transactional} method
 * would) that fails, and catches the exception. The participant has marked the Spring transaction rollback-only, so it
 * can never commit.
 * <p>
 * Committing the unit of work must complete the transaction - roll it back - and tell the caller its work was
 * discarded with an {@link UnexpectedRollbackException}, rather than leave the transaction and the unit of work bound
 * to the thread. The appended event was never committed, so it must never be handed to
 * {@link PersistedEventsCommitLifecycleCallback#afterCommit(EventStoreUnitOfWork, List)}.
 */
@SpringBootTest
@DirtiesContext
@Testcontainers
class SpringTransactionAwareEventStoreUnitOfWorkFactory_RollbackOnlyIT {
    private static final AggregateType ORDERS    = AggregateType.of("RollbackOnlyOrders");
    private static final EventMetaData META_DATA = EventMetaData.of("Key1", "Value1");

    @Container
    static PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgreSQLContainer::getJdbcUrl);
        registry.add("spring.datasource.password", postgreSQLContainer::getPassword);
        registry.add("spring.datasource.username", postgreSQLContainer::getUsername);
    }

    @Autowired
    Jdbi jdbi;

    @Autowired
    PlatformTransactionManager transactionManager;

    private SpringTransactionAwareEventStoreUnitOfWorkFactory                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private TransactionTemplate                                                     transactionTemplate;
    private RecordingPersistedEventsCommitLifecycleCallback                         recordingCallback;

    @BeforeEach
    void setup() {
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        transactionTemplate = new TransactionTemplate(transactionManager);
        unitOfWorkFactory = new SpringTransactionAwareEventStoreUnitOfWorkFactory(jdbi, transactionManager);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new TestPersistableEventMapper(),
                                                                                       standardSingleTenantConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                                         IdentifierColumnType.UUID,
                                                                                                                         JSONColumnType.JSONB));
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
        persistenceStrategy.resetEventStorageFor(ORDERS);

        recordingCallback = new RecordingPersistedEventsCommitLifecycleCallback();
        unitOfWorkFactory.registerPersistedEventsCommitLifeCycleCallback(recordingCallback);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
    }

    @Test
    void a_unit_of_work_whose_transaction_a_failed_participant_marked_rollback_only_is_rolled_back_and_reported() {
        var orderId = OrderId.random();

        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(uow -> {
            eventStore.appendToStream(ORDERS, orderId, List.of(new OrderPlaced(orderId)));
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    throw new IllegalStateException("the inner transactional participant fails");
                });
            } catch (IllegalStateException caught) {
                // The work carries on - but the participant has marked the transaction rollback-only
            }
        })).isInstanceOf(UnitOfWorkException.class)
           .cause()
           .as("the caller learns its work was discarded rather than believing it committed")
           .isInstanceOf(UnexpectedRollbackException.class);

        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).as("unit of work still bound to the thread").isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("transaction still bound to the thread").isFalse();
        assertThat(recordingCallback.afterCommitEvents).as("events handed to afterCommit").isEmpty();
        // The event stream is read lazily, so its presence is resolved inside the UnitOfWork
        var streamPersisted = unitOfWorkFactory.withUnitOfWork(uow -> eventStore.fetchStream(ORDERS, orderId).isPresent());
        assertThat(streamPersisted).as("the appended event was rolled back").isFalse();
    }

    public static class OrderPlaced {
        public final OrderId orderId;

        public OrderPlaced(OrderId orderId) {
            this.orderId = orderId;
        }
    }

    private static class RecordingPersistedEventsCommitLifecycleCallback implements PersistedEventsCommitLifecycleCallback {
        private final List<PersistedEvent> afterCommitEvents = new CopyOnWriteArrayList<>();

        @Override
        public void beforeCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
        }

        @Override
        public void afterCommit(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
            afterCommitEvents.addAll(persistedEvents);
        }

        @Override
        public void afterRollback(EventStoreUnitOfWork unitOfWork, List<PersistedEvent> persistedEvents) {
        }
    }

    private static class TestPersistableEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration aggregateEventStreamConfiguration, Object event, EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         EventRevision.of(1),
                                         META_DATA,
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
