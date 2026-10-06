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

package dk.trustworks.essentials.components.eventsourced.aggregates;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.aggregates.decider.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.flex.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
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
 * The three lazily appending repositories - {@link StatefulAggregateRepository}, {@link FlexAggregateRepository} and the
 * decider {@link CommandHandler} - append when the {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
 * commits, which need not be where the cause is bound. They record the cause bound when the aggregate joined the
 * UnitOfWork instead (phase 4 of {@code docs/event-causation.md}).
 */
@Testcontainers
class CausationCapturedAtRegistrationIT {
    private static final AggregateType TALLIES = AggregateType.of("Tallies");
    private static final AggregateType PARCELS = AggregateType.of("Parcels");
    private static final AggregateType LEDGERS = AggregateType.of("Ledgers");
    private static final EventId       CAUSE   = EventId.of("cause-at-registration");
    private static final EventId       OTHER   = EventId.of("cause-at-commit");

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private StatefulAggregateRepository<String, TallyEvent, Tally>                  tallies;
    private FlexAggregateRepository<String, Parcel>                                 parcels;
    private CommandHandler<OpenLedger, LedgerOpened, String>                        ledgers;
    private PostgresqlFencedLockManager                                             fencedLockManager;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        // A mapper that sets no cause, so the causation enricher decides it - as with the starter's default mapper
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
                                                                                  .setPersistableEventEnrichers(List.of(new CausationPersistableEventEnricher()))
                                                                                  .build();
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);

        tallies = StatefulAggregateRepository.from(eventStore, TALLIES, reflectionBasedAggregateRootFactory(), Tally.class);
        parcels = FlexAggregateRepository.from(eventStore, PARCELS, unitOfWorkFactory, String.class, Parcel.class);
        ledgers = CommandHandler.deciderBasedCommandHandler(eventStore,
                                                            LEDGERS,
                                                            String.class,
                                                            cmd -> Optional.of(cmd.ledgerId()),
                                                            event -> Optional.of(event.ledgerId()),
                                                            null,
                                                            LedgerState.class,
                                                            new LedgerDecider());
    }

    @AfterEach
    void teardown() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
        if (fencedLockManager != null) {
            fencedLockManager.stop();
        }
    }

    // ---------------------------------------------------------------------------------------- the cause at registration

    @Test
    void StatefulAggregateRepository_appends_under_the_cause_bound_when_the_aggregate_joined_the_UnitOfWork() {
        registerUnderThenCommitUnder(Optional.of(CAUSE), Optional.empty(), () -> tallies.save(Tally.started("tally-1")));

        assertThat(causes(TALLIES, "tally-1")).containsExactly(Optional.of(CAUSE));
    }

    @Test
    void FlexAggregateRepository_appends_under_the_cause_bound_when_the_events_joined_the_UnitOfWork() {
        registerUnderThenCommitUnder(Optional.of(CAUSE), Optional.empty(), () -> parcels.persist(FlexAggregate.newAggregateEvents("parcel-1", new ParcelRegistered("parcel-1"))));

        assertThat(causes(PARCELS, "parcel-1")).containsExactly(Optional.of(CAUSE));
    }

    @Test
    void the_decider_CommandHandler_appends_under_the_cause_bound_when_the_command_was_handled() {
        registerUnderThenCommitUnder(Optional.of(CAUSE), Optional.empty(), () -> ledgers.handle(new OpenLedger("ledger-1")));

        assertThat(causes(LEDGERS, "ledger-1")).containsExactly(Optional.of(CAUSE));
    }

    // ------------------------------------------------------------------------------------------ "no cause" is captured too

    @Test
    void StatefulAggregateRepository_does_not_take_a_cause_bound_only_at_commit() {
        registerUnderThenCommitUnder(Optional.empty(), Optional.of(OTHER), () -> tallies.save(Tally.started("tally-1")));

        assertThat(causes(TALLIES, "tally-1")).containsExactly(Optional.empty());
    }

    @Test
    void FlexAggregateRepository_does_not_take_a_cause_bound_only_at_commit() {
        registerUnderThenCommitUnder(Optional.empty(), Optional.of(OTHER), () -> parcels.persist(FlexAggregate.newAggregateEvents("parcel-1", new ParcelRegistered("parcel-1"))));

        assertThat(causes(PARCELS, "parcel-1")).containsExactly(Optional.empty());
    }

    @Test
    void the_decider_CommandHandler_does_not_take_a_cause_bound_only_at_commit() {
        registerUnderThenCommitUnder(Optional.empty(), Optional.of(OTHER), () -> ledgers.handle(new OpenLedger("ledger-1")));

        assertThat(causes(LEDGERS, "ledger-1")).containsExactly(Optional.empty());
    }

    @Test
    void the_first_registration_of_an_aggregate_in_a_UnitOfWork_decides_its_cause() {
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        var tally      = Tally.started("tally-1");
        CausationContext.where(CAUSE).run(() -> tallies.save(tally));
        CausationContext.where(OTHER).run(() -> tallies.save(tally));
        unitOfWork.commit();

        assertThat(causes(TALLIES, "tally-1")).containsExactly(Optional.of(CAUSE));
    }

    @Test
    void an_aggregate_loaded_under_one_cause_appends_later_changes_under_it() {
        unitOfWorkFactory.usingUnitOfWork(() -> tallies.save(Tally.started("tally-1")));

        registerUnderThenCommitUnder(Optional.of(CAUSE), Optional.of(OTHER), () -> tallies.load("tally-1").increment());

        assertThat(causes(TALLIES, "tally-1")).containsExactly(Optional.empty(), Optional.of(CAUSE));
    }

    // ------------------------------------------------------------------------------- the case that motivated phase 4

    /**
     * An in-transaction handler runs inside the appending UnitOfWork's commit, so the aggregate it changes is appended
     * in a later pass of that commit - after the handler's own binding has ended. Without the cause captured at
     * registration the reaction would be recorded as caused by whatever caused the <em>trigger</em>.
     */
    @Test
    void an_aggregate_changed_by_an_in_transaction_handler_records_the_event_the_handler_was_given() {
        startSubscriptionManager();
        eventStoreSubscriptionManager.subscribeToAggregateEventsInTransaction(SubscriberId.of("react-to-tallies"),
                                                                              TALLIES,
                                                                              (event, unitOfWork) -> {
                                                                                  var tallyId = (String) event.aggregateId();
                                                                                  if (tallyId.startsWith("trigger-")) {
                                                                                      tallies.save(Tally.started("reaction-" + tallyId));
                                                                                  }
                                                                              });

        // The trigger is appended the way a repository appends - at commit, under the outer work's cause
        CausationContext.where(OTHER).run(() -> unitOfWorkFactory.usingUnitOfWork(() -> tallies.save(Tally.started("trigger-1"))));

        var triggerEventId = eventIds(TALLIES, "trigger-1").getFirst();
        assertThat(causes(TALLIES, "trigger-1")).containsExactly(Optional.of(OTHER));
        assertThat(causes(TALLIES, "reaction-trigger-1")).containsExactly(Optional.of(triggerEventId));
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    /**
     * Register work with a UnitOfWork under one binding, then commit it under another
     */
    private void registerUnderThenCommitUnder(Optional<EventId> atRegistration, Optional<EventId> atCommit, Runnable register) {
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        CausationContext.where(atRegistration).run(register);
        CausationContext.where(atCommit).run(unitOfWork::commit);
    }

    private List<Optional<EventId>> causes(AggregateType aggregateType, String aggregateId) {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(aggregateType, aggregateId)
                                                                .map(stream -> stream.eventList().stream().map(PersistedEvent::causedByEventId).toList())
                                                                .orElse(List.of()));
    }

    private List<EventId> eventIds(AggregateType aggregateType, String aggregateId) {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(aggregateType, aggregateId)
                                                                .orElseThrow()
                                                                .eventList()
                                                                .stream()
                                                                .map(PersistedEvent::eventId)
                                                                .toList());
    }

    private void startSubscriptionManager() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        fencedLockManager = PostgresqlFencedLockManager.builder()
                                                       .setEventBus(eventStore.localEventBus())
                                                       .setJdbi(jdbi)
                                                       .setLockTimeOut(Duration.ofSeconds(2))
                                                       .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .buildAndStart();
        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setFencedLockManager(fencedLockManager)
                                                                     .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                                     .build();
        eventStoreSubscriptionManager.start();
    }

    // ----------------------------------------------------------------------------------------------------- test data

    public sealed interface TallyEvent permits TallyStarted, TallyIncremented {
    }

    public record TallyStarted(String tallyId) implements TallyEvent {
    }

    public record TallyIncremented(String tallyId) implements TallyEvent {
    }

    public static class Tally extends AggregateRoot<String, TallyEvent, Tally> {
        public Tally(String tallyId) {
            super(tallyId);
        }

        /**
         * A new tally; the single-argument constructor is the one rehydration uses
         */
        static Tally started(String tallyId) {
            var tally = new Tally(tallyId);
            tally.apply(new TallyStarted(tallyId));
            return tally;
        }

        public void increment() {
            apply(new TallyIncremented(aggregateId()));
        }
    }

    public record ParcelRegistered(String parcelId) {
    }

    public static class Parcel extends FlexAggregate<String, Parcel> {
        public Parcel() {
        }
    }

    public record OpenLedger(String ledgerId) {
    }

    public record LedgerOpened(String ledgerId) {
    }

    public record LedgerState(boolean opened) {
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
}
