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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfiguratorTest.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.reactive.command.UnitOfWorkControllingCommandBusInterceptor;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.reactive.command.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES-4: {@link AppendedEvents} records at the event store, so the caller of {@code commandBus.send(...)} learns the
 * {@code GlobalEventOrder} of what an {@link EventStreamDecider} appended - and of what a
 * {@link StatefulAggregateRepository} appended, as long as its {@link UnitOfWork} commits during the recording
 */
@Testcontainers
class AppendedEventsIT {
    private static final AggregateType ORDERS  = AggregateType.of("Orders");
    private static final AggregateType TICKETS = AggregateType.of("Tickets");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>              unitOfWorkFactory;
    private CommandBus                                                     commandBus;
    private StatefulAggregateRepository<String, TicketOpened, Ticket>      tickets;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
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
        var eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);

        commandBus = new LocalCommandBus(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory));
        var orders = new EventStreamAggregateTypeConfiguration(ORDERS,
                                                               OrderId.class,
                                                               AggregateIdSerializer.serializerFor(OrderId.class),
                                                               new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
                                                               command -> ((OrderCommand) command).orderId(),
                                                               event -> ((OrderEvent) event).orderId());
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orders), List.of(new CreateOrderDecider()));

        tickets = StatefulAggregateRepository.from(eventStore, TICKETS, reflectionBasedAggregateRootFactory(), Ticket.class);
    }

    @AfterEach
    void teardown() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
    }

    @Test
    void the_event_an_EventStreamDecider_appends_during_a_send_is_handed_to_the_caller() {
        var orderId = OrderId.of("o-1");

        var recorded = AppendedEvents.recordDuring(() -> commandBus.send(new CreateOrder(orderId, CustomerId.of("c-1"))));

        // send still returns what the adapter returned before - the event
        assertThat(recorded.result()).isInstanceOf(OrderCreated.class);
        assertThat(recorded.appendedEvents()).hasSize(1);
        assertThat(recorded.lastAppendedEvent()).hasValueSatisfying(event -> assertThat(event.aggregateId()).isEqualTo(orderId));
        assertThat(recorded.highestGlobalEventOrder(ORDERS)).hasValueSatisfying(order -> assertThat(order.longValue()).isEqualTo(1));
    }

    @Test
    void the_events_a_StatefulAggregateRepository_appends_when_its_UnitOfWork_commits_during_the_action_are_recorded() {
        var recorded = AppendedEvents.recordDuring(() -> unitOfWorkFactory.withUnitOfWork(() -> tickets.save(Ticket.opened("t-1"))));

        assertThat(recorded.appendedEvents()).extracting(PersistedEvent::aggregateId).containsExactly("t-1");
        assertThat(recorded.highestGlobalEventOrder(TICKETS)).isPresent();
    }

    @Test
    void a_StatefulAggregateRepository_whose_UnitOfWork_commits_after_the_action_is_not_recorded() {
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();

        var recorded = AppendedEvents.recordDuring(() -> tickets.save(Ticket.opened("t-1")));
        unitOfWork.commit();

        assertThat(recorded.appendedEvents()).isEmpty();
    }

    @Test
    void the_appends_of_several_appenders_during_one_action_are_all_recorded_once_in_append_order() {
        var recorded = AppendedEvents.recordDuring(() -> {
            commandBus.send(new CreateOrder(OrderId.of("o-1"), CustomerId.of("c-1")));
            unitOfWorkFactory.usingUnitOfWork(() -> tickets.save(Ticket.opened("t-1")));
            return commandBus.send(new CreateOrder(OrderId.of("o-2"), CustomerId.of("c-1")));
        });

        assertThat(recorded.appendedEvents()).extracting(PersistedEvent::aggregateId)
                                             .containsExactly(OrderId.of("o-1"), "t-1", OrderId.of("o-2"));
    }

    // ----------------------------------------------------------------------------------------------------- test data

    public record TicketOpened(String ticketId) {
    }

    public static class Ticket extends AggregateRoot<String, TicketOpened, Ticket> {
        public Ticket(String ticketId) {
            super(ticketId);
        }

        static Ticket opened(String ticketId) {
            var ticket = new Ticket(ticketId);
            ticket.apply(new TicketOpened(ticketId));
            return ticket;
        }
    }
}
