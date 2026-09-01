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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.components.foundation.messaging.queue.TransactionalMode;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for {@link MessageHandler#unitOfWork()} on an {@link EventProcessor}.
 * <p>
 * A {@link UnitOfWorkMode#NONE} handler is meant for blocking I/O against an external system, so it must be invoked
 * with no {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork} active - which, since a
 * UnitOfWork is what opens the JDBI handle and begins the database transaction, is also what guarantees that no
 * pooled connection is held while the blocking call runs. The transactional tail after the blocking call is wrapped
 * by the handler itself via {@link AbstractEventProcessor#usingUnitOfWork}.
 */
@Testcontainers
class NonTransactionalMessageHandlerIT {

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private PostgresqlFencedLockManager                                             fencedLockManager;
    private PostgresqlDurableQueues                                                 durableQueues;
    private DurableLocalCommandBus                                                  commandBus;
    private RiskCheckProcessor                                                      processor;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var jsonSerializer = EssentialsJSONEventSerializers.createForActiveJacksonFlavor();
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       standardSingleTenantConfiguration(
                                                                                               jsonSerializer,
                                                                                               IdentifierColumnType.UUID,
                                                                                               JSONColumnType.JSONB));
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                persistenceStrategy,
                                                Optional.empty(),
                                                eventStore -> new PostgresqlEventStreamGapHandler<>(eventStore,
                                                                                                    unitOfWorkFactory),
                                                new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver());

        fencedLockManager = PostgresqlFencedLockManager.builder()
                                                       .setEventBus(eventStore.localEventBus())
                                                       .setJdbi(jdbi)
                                                       .setLockTimeOut(Duration.ofSeconds(2))
                                                       .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                       .setReleaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation(true)
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .buildAndStart();

        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setFencedLockManager(fencedLockManager)
                                                                     .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                                     .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                                     .build();
        eventStoreSubscriptionManager.start();

        durableQueues = PostgresqlDurableQueues.builder()
                                               .setJsonSerializer(jsonSerializer)
                                               // Must comfortably exceed the simulated blocking call, otherwise the
                                               // message is reset as stuck and redelivered while the first attempt runs
                                               .setMessageHandlingTimeout(Duration.ofSeconds(30))
                                               .setTransactionalMode(TransactionalMode.SingleOperationTransaction)
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .build();
        durableQueues.start();

        commandBus = DurableLocalCommandBus.builder()
                                           .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                           .setDurableQueues(durableQueues)
                                           .build();
        commandBus.start();

        processor = new RiskCheckProcessor(new EventProcessorDependencies(eventStoreSubscriptionManager,
                                                                          new Inboxes.DurableQueueBasedInboxes(durableQueues, fencedLockManager),
                                                                          commandBus,
                                                                          List.of()),
                                            eventStore);
    }

    @AfterEach
    void teardown() {
        if (processor != null && processor.isStarted()) {
            processor.stop();
        }
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
        if (fencedLockManager != null) {
            fencedLockManager.stop();
        }
        if (commandBus != null) {
            commandBus.stop();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    @Test
    void a_blocking_handler_runs_without_a_UnitOfWork_and_can_commit_its_transactional_tail() {
        processor.start();
        var instrumentId = OrderId.random();

        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(RiskCheckProcessor.INSTRUMENTS,
                                                                          instrumentId,
                                                                          new InstrumentRegistrationRequested(instrumentId)));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .until(() -> processor.tailCompleted.get());

        // The blocking part of the handler saw no UnitOfWork, hence held no connection and no open database transaction
        assertThat(processor.unitOfWorkActiveDuringBlockingCall).isFalse();
        // ... while the tail it wrapped itself did run inside one
        assertThat(processor.unitOfWorkActiveDuringTail).isTrue();

        // ... and the tail's write was committed
        var events = unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(RiskCheckProcessor.INSTRUMENTS, instrumentId)
                                                                      .orElseThrow()
                                                                      .eventList());
        assertThat(events).hasSize(2);
        assertThat(events.get(1).event().getEventTypeAsJavaClass()).contains(RiskDecisionRecorded.class);
    }

    @Test
    void a_REQUIRED_handler_in_the_same_processor_still_runs_inside_a_UnitOfWork() {
        processor.start();
        var instrumentId = OrderId.random();

        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(RiskCheckProcessor.INSTRUMENTS,
                                                                          instrumentId,
                                                                          new InstrumentDelisted(instrumentId)));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .until(() -> processor.transactionalHandlerCompleted.get());

        assertThat(processor.unitOfWorkActiveDuringTransactionalHandler).isTrue();
    }

    @Test
    void a_processor_with_a_blocking_handler_is_rejected_under_FullyTransactional() {
        var fullyTransactionalQueues = PostgresqlDurableQueues.builder()
                                                              .setJsonSerializer(EssentialsJSONEventSerializers.createForActiveJacksonFlavor())
                                                              .setTransactionalMode(TransactionalMode.FullyTransactional)
                                                              .setUnitOfWorkFactory(unitOfWorkFactory)
                                                              .build();
        fullyTransactionalQueues.start();
        try {
            var rejectedProcessor = new RiskCheckProcessor(new EventProcessorDependencies(eventStoreSubscriptionManager,
                                                                                          new Inboxes.DurableQueueBasedInboxes(fullyTransactionalQueues, fencedLockManager),
                                                                                          commandBus,
                                                                                          List.of()),
                                                            eventStore);

            assertThatThrownBy(rejectedProcessor::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UnitOfWorkMode.NONE");
        } finally {
            fullyTransactionalQueues.stop();
        }
    }

    // -------------------------------------------------------------------------------------------------------------------

    record InstrumentRegistrationRequested(OrderId instrumentId) {
    }

    record InstrumentDelisted(OrderId instrumentId) {
    }

    record RiskDecisionRecorded(OrderId instrumentId, String decision) {
    }

    static class RiskCheckProcessor extends EventProcessor {
        static final AggregateType INSTRUMENTS = AggregateType.of("Instruments");

        private final PostgresqlEventStore<?> eventStore;

        volatile boolean       unitOfWorkActiveDuringBlockingCall        = true;
        volatile boolean       unitOfWorkActiveDuringTail                = false;
        volatile boolean       unitOfWorkActiveDuringTransactionalHandler = false;
        final    AtomicBoolean tailCompleted                             = new AtomicBoolean();
        final    AtomicBoolean transactionalHandlerCompleted             = new AtomicBoolean();

        RiskCheckProcessor(EventProcessorDependencies dependencies, PostgresqlEventStore<?> eventStore) {
            super(dependencies);
            this.eventStore = eventStore;
            eventStore.addAggregateEventStreamConfiguration(INSTRUMENTS,
                                                            AggregateIdSerializer.serializerFor(OrderId.class));
        }

        @Override
        public String getProcessorName() {
            return "RiskCheckProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(INSTRUMENTS);
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(InstrumentRegistrationRequested e) {
            unitOfWorkActiveDuringBlockingCall = hasActiveUnitOfWork();

            // Stands in for a blocking HTTP call to an external risk service
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }

            usingUnitOfWork(() -> {
                unitOfWorkActiveDuringTail = hasActiveUnitOfWork();
                eventStore.appendToStream(INSTRUMENTS,
                                          e.instrumentId(),
                                          new RiskDecisionRecorded(e.instrumentId(), "APPROVED"));
            });
            tailCompleted.set(true);
        }

        @MessageHandler
        void on(InstrumentDelisted e) {
            unitOfWorkActiveDuringTransactionalHandler = hasActiveUnitOfWork();
            transactionalHandlerCompleted.set(true);
        }

        @MessageHandler
        void on(RiskDecisionRecorded e) {
            // The tail's own event comes back around through the subscription - nothing to do
        }

        private boolean hasActiveUnitOfWork() {
            return eventStore.getUnitOfWorkFactory().getCurrentUnitOfWork().isPresent();
        }
    }
}
