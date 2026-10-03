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

package dk.trustworks.essentials.components.eventsourced.aggregates.stateful;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.aggregates.decider.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An in-transaction subscription handler runs inside the appending UnitOfWork's commit, during
 * {@code beforeCommitting()}. An aggregate it saves through a {@link StatefulAggregateRepository} registers a lifecycle
 * callback at that point - and must still be appended before the transaction commits, however the event that triggered
 * the handler was appended.
 */
@Testcontainers
class InTransactionHandlerLazyAppendIT {
    private static final AggregateType ORDERS  = AggregateType.of("Orders");
    private static final AggregateType TALLIES = AggregateType.of("Tallies");
    private static final AggregateType LEDGERS = AggregateType.of("Ledgers");

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private StatefulAggregateRepository<String, Object, Tally>                      tallies;
    private PostgresqlFencedLockManager                                             fencedLockManager;
    private EventStoreSubscriptionManager                                           subscriptionManager;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                                  .setJdbi(jdbi)
                                                                                  .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                  .setEventMapper((aggregateId, configuration, event, eventOrder) ->
                                                                                                          PersistableEvent.builder()
                                                                                                                          .setEvent(event)
                                                                                                                          .setAggregateType(configuration.aggregateType)
                                                                                                                          .setAggregateId(aggregateId)
                                                                                                                          .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                                                                                                          .setEventOrder(eventOrder)
                                                                                                                          .build())
                                                                                  .setAggregateEventStreamConfigurationFactory(standardSingleTenantConfiguration(
                                                                                          EssentialsJSONEventSerializers.create(),
                                                                                          IdentifierColumnType.TEXT,
                                                                                          JSONColumnType.JSONB))
                                                                                  .build();
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
        tallies = StatefulAggregateRepository.from(eventStore, TALLIES, reflectionBasedAggregateRootFactory(), Tally.class);

        fencedLockManager = PostgresqlFencedLockManager.builder()
                                                       .setEventBus(eventStore.localEventBus())
                                                       .setJdbi(jdbi)
                                                       .setLockTimeOut(Duration.ofSeconds(2))
                                                       .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .buildAndStart();
        subscriptionManager = EventStoreSubscriptionManager.builder()
                                                           .setEventStore(eventStore)
                                                           .setFencedLockManager(fencedLockManager)
                                                           .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                           .build();
        subscriptionManager.start();
        subscriptionManager.subscribeToAggregateEventsInTransaction(SubscriberId.of("start-a-tally-per-order"),
                                                                    ORDERS,
                                                                    (event, unitOfWork) -> tallies.save(Tally.started("tally-" + event.aggregateId())));
    }

    @AfterEach
    void teardown() {
        subscriptionManager.stop();
        fencedLockManager.stop();
    }

    @Test
    void an_aggregate_saved_by_an_in_transaction_handler_is_appended_when_the_trigger_was_appended_directly() {
        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, "order-1", new OrderPlaced("order-1")));

        assertThat(tallyEvents("tally-order-1")).as("the tally saved by the in-transaction handler").hasSize(1);
    }

    @Test
    void an_aggregate_saved_by_an_in_transaction_handler_is_appended_when_the_trigger_was_appended_by_a_repository() {
        var orders = StatefulAggregateRepository.from(eventStore, ORDERS, reflectionBasedAggregateRootFactory(), Tally.class);
        unitOfWorkFactory.usingUnitOfWork(() -> orders.save(Tally.started("order-2")));

        assertThat(tallyEvents("tally-order-2")).as("the tally saved by the in-transaction handler").hasSize(1);
    }

    /**
     * The commit makes another pass whenever a callback asks for one - a StatefulAggregateRepository does after every
     * append - and calls every callback again. A decider's events must not be appended again on that pass.
     */
    @Test
    void a_deciders_events_are_appended_once_when_the_commit_makes_more_than_one_pass() {
        var ledgers = CommandHandler.deciderBasedCommandHandler(eventStore,
                                                                LEDGERS,
                                                                String.class,
                                                                cmd -> Optional.of(cmd.ledgerId()),
                                                                event -> Optional.of(event.ledgerId()),
                                                                null,
                                                                LedgerState.class,
                                                                new LedgerDecider());

        unitOfWorkFactory.usingUnitOfWork(() -> {
            ledgers.handle(new OpenLedger("ledger-1"));
            tallies.save(Tally.started("tally-in-the-same-unit-of-work"));
        });

        assertThat(unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(LEDGERS, "ledger-1").orElseThrow().eventList())).hasSize(1);
        assertThat(tallyEvents("tally-in-the-same-unit-of-work")).hasSize(1);
    }

    private List<PersistedEvent> tallyEvents(String tallyId) {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(TALLIES, tallyId)
                                                                .map(stream -> stream.eventList())
                                                                .orElse(List.of()));
    }

    record OrderPlaced(String orderId) {
    }

    record OpenLedger(String ledgerId) {
    }

    record LedgerOpened(String ledgerId) {
    }

    record LedgerState(boolean opened) {
    }

    static class LedgerDecider implements Decider<OpenLedger, LedgerOpened, String, LedgerState> {
        @Override
        public HandlerResult<String, LedgerOpened> handle(OpenLedger cmd, LedgerState state) {
            return HandlerResult.events(new LedgerOpened(cmd.ledgerId()));
        }

        @Override
        public LedgerState initialState() {
            return new LedgerState(false);
        }

        @Override
        public LedgerState applyEvent(LedgerOpened event, LedgerState state) {
            return new LedgerState(true);
        }

        @Override
        public boolean isFinal(LedgerState state) {
            return false;
        }
    }

    public record TallyStarted(String tallyId) {
    }

    public static class Tally extends AggregateRoot<String, Object, Tally> {
        public Tally(String tallyId) {
            super(tallyId);
        }

        static Tally started(String tallyId) {
            var tally = new Tally(tallyId);
            tally.apply(new TallyStarted(tallyId));
            return tally;
        }
    }
}
