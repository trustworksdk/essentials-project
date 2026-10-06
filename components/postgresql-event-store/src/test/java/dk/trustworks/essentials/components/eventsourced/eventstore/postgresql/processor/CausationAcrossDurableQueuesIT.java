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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.reactive.command.CommandHandler;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link CausationDurableQueuesInterceptor} carries the cause across every {@link DurableQueues} hand-off - an
 * {@link Inbox}, an {@link Outbox}, the {@link DurableLocalCommandBus} - and on both PostgreSQL consumption engines:
 * the per-queue {@code DefaultDurableQueueConsumer} and the {@code CentralizedMessageFetcher}. The shard-owned engine is
 * covered in its own module ({@code InboxOutboxOnShardOwnedIT}).
 * <p>
 * Each test checks the cause where it matters: inside the handler, inside the handler's UnitOfWork commit - which is
 * what pins that no engine opens a UnitOfWork outside the interceptor chain - and, for the command bus, on the event the
 * command's handler persists.
 */
@Testcontainers
class CausationAcrossDurableQueuesIT {
    private static final AggregateType ORDERS = AggregateType.of("Orders");
    private static final EventId       CAUSE  = EventId.of("cause-before-the-queue");
    private static final Duration      WAIT   = Duration.ofSeconds(20);

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private PostgresqlFencedLockManager                                             fencedLockManager;
    private PostgresqlDurableQueues                                                 durableQueues;
    private DurableLocalCommandBus                                                  commandBus;
    private final List<Runnable>                                                    stopActions = new ArrayList<>();

    @AfterEach
    void teardown() {
        stopActions.reversed().forEach(Runnable::run);
        stopActions.clear();
    }

    @ParameterizedTest(name = "centralizedMessageFetcher={0}")
    @ValueSource(booleans = {false, true})
    void an_inbox_message_is_handled_and_committed_under_the_cause_bound_when_it_was_added(boolean centralizedMessageFetcher) {
        setup(centralizedMessageFetcher);
        var seenWhileHandling   = new CopyOnWriteArrayList<Optional<EventId>>();
        var seenWhileCommitting = new CopyOnWriteArrayList<Optional<EventId>>();
        var inbox = Inboxes.durableQueueBasedInboxes(durableQueues, fencedLockManager)
                           .getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(InboxName.of("causation-inbox"))
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                        .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                        .numberOfParallelMessageConsumers(1)
                                                        .build(),
                                             message -> {
                                                 seenWhileHandling.add(CausationContext.current());
                                                 recordCauseAtCommit(seenWhileCommitting);
                                             });
        stopActions.add(inbox::stopConsuming);

        CausationContext.where(CAUSE).run(() -> inbox.addMessageReceived("payload"));

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(seenWhileCommitting).hasSize(1));
        assertThat(seenWhileHandling).containsExactly(Optional.of(CAUSE));
        assertThat(seenWhileCommitting).containsExactly(Optional.of(CAUSE));
    }

    @ParameterizedTest(name = "centralizedMessageFetcher={0}")
    @ValueSource(booleans = {false, true})
    void an_outbox_message_is_handled_under_the_cause_bound_when_it_was_sent(boolean centralizedMessageFetcher) {
        setup(centralizedMessageFetcher);
        var seenWhileHandling = new CopyOnWriteArrayList<Optional<EventId>>();
        var outbox = Outboxes.durableQueueBasedOutboxes(durableQueues, fencedLockManager)
                             .getOrCreateOutbox(OutboxConfig.builder()
                                                            .setOutboxName(OutboxName.of("causation-outbox"))
                                                            .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                            .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                            .setNumberOfParallelMessageConsumers(1)
                                                            .build(),
                                                message -> seenWhileHandling.add(CausationContext.current()));
        stopActions.add(outbox::stopConsuming);

        CausationContext.where(CAUSE).run(() -> unitOfWorkFactory.usingUnitOfWork(() -> outbox.sendMessage("payload")));

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(seenWhileHandling).containsExactly(Optional.of(CAUSE)));
    }

    @ParameterizedTest(name = "centralizedMessageFetcher={0}")
    @ValueSource(booleans = {false, true})
    void an_event_persisted_by_a_command_sent_with_sendAndDontWait_records_the_cause_bound_at_send(boolean centralizedMessageFetcher) {
        setup(centralizedMessageFetcher);
        commandBus.addCommandHandler(new CommandHandler() {
            @Override
            public boolean canHandle(Class<?> commandType) {
                return PlaceOrder.class.equals(commandType);
            }

            @Override
            public Object handle(Object command) {
                var orderId = ((PlaceOrder) command).orderId();
                eventStore.appendToStream(ORDERS, orderId, new OrderPlaced(orderId));
                return null;
            }
        });

        CausationContext.where(CAUSE).run(() -> commandBus.sendAndDontWait(new PlaceOrder("order-1")));

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(causes("order-1")).containsExactly(Optional.of(CAUSE)));
    }

    @ParameterizedTest(name = "centralizedMessageFetcher={0}")
    @ValueSource(booleans = {false, true})
    void a_message_added_with_no_cause_bound_is_handled_without_one(boolean centralizedMessageFetcher) {
        setup(centralizedMessageFetcher);
        var seenWhileHandling = new CopyOnWriteArrayList<Optional<EventId>>();
        var inbox = Inboxes.durableQueueBasedInboxes(durableQueues, fencedLockManager)
                           .getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(InboxName.of("no-cause-inbox"))
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                        .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                        .numberOfParallelMessageConsumers(1)
                                                        .build(),
                                             message -> seenWhileHandling.add(CausationContext.current()));
        stopActions.add(inbox::stopConsuming);

        inbox.addMessageReceived("payload");

        Awaitility.waitAtMost(WAIT).untilAsserted(() -> assertThat(seenWhileHandling).containsExactly(Optional.empty()));
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private void setup(boolean centralizedMessageFetcher) {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
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
                                                                                  .setPersistableEventEnrichers(List.of(new CausationPersistableEventEnricher()))
                                                                                  .build();
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));

        fencedLockManager = PostgresqlFencedLockManager.builder()
                                                       .setEventBus(eventStore.localEventBus())
                                                       .setJdbi(jdbi)
                                                       .setLockTimeOut(Duration.ofSeconds(2))
                                                       .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .buildAndStart();
        stopActions.add(fencedLockManager::stop);

        durableQueues = PostgresqlDurableQueues.builder()
                                               .setJsonSerializer(EssentialsJSONEventSerializers.create())
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setUseCentralizedMessageFetcher(centralizedMessageFetcher)
                                               .build();
        durableQueues.addInterceptor(new CausationDurableQueuesInterceptor());
        durableQueues.start();
        stopActions.add(durableQueues::stop);

        commandBus = DurableLocalCommandBus.builder()
                                           .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                           .setDurableQueues(durableQueues)
                                           .build();
        commandBus.start();
        stopActions.add(commandBus::stop);
    }

    private void recordCauseAtCommit(List<Optional<EventId>> seen) {
        unitOfWorkFactory.getRequiredUnitOfWork().registerLifecycleCallbackForResource("cause", new UnitOfWorkLifecycleCallback<String>() {
            @Override
            public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
                seen.add(CausationContext.current());
                return BeforeCommitProcessingStatus.COMPLETED;
            }

            @Override
            public void afterCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
            }

            @Override
            public void beforeRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
            }

            @Override
            public void afterRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
            }
        });
    }

    private List<Optional<EventId>> causes(String orderId) {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.fetchStream(ORDERS, orderId)
                                                                .map(stream -> stream.eventList().stream().map(PersistedEvent::causedByEventId).toList())
                                                                .orElse(List.of()));
    }

    record PlaceOrder(String orderId) {
    }

    record OrderPlaced(String orderId) {
    }
}
