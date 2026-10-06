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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test that decides whether event causation works (phase 7 of {@code docs/event-causation.md}): the two shapes of the
 * webshop's capture flow, through the real auto-configuration and lifecycle, with nothing bound by hand except where an
 * application would bind it.
 * <ol>
 *     <li>An {@link EventProcessor} handler ({@code REQUIRED}) reacts to {@code OrderPlaced} by creating an aggregate
 *     through a {@link StatefulAggregateRepository} - appended lazily, when the handler's UnitOfWork commits. Its event
 *     must record the {@code OrderPlaced} as its cause.</li>
 *     <li>A {@link UnitOfWorkMode#NONE} handler reacts to {@code CaptureApproved} by committing
 *     {@code FundsCaptureRequested} in its own UnitOfWork, then - standing in for the payment gateway's webhook, which
 *     arrives on another request - binds that event's id explicitly and adds a message to an {@link Inbox}. The Inbox
 *     handler creates an aggregate through the repository, appended lazily at commit, which must record
 *     {@code FundsCaptureRequested} as its cause.</li>
 * </ol>
 * Every assertion is on a specific event id, never on "some cause is set": a missing binding fails silently, so only an
 * exact id proves the right one was written. The admin API then walks the resulting chain back to its root.
 */
@Testcontainers
class EventCausationEndToEndIT {
    static final AggregateType ORDERS   = AggregateType.of("Orders");
    static final AggregateType PAYMENTS = AggregateType.of("Payments");
    static final AggregateType TALLIES  = AggregateType.of("Tallies");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-causation-e2e")
            .withUsername("test-user")
            .withPassword("secret-password");

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                                                             DataSourceTransactionManagerAutoConfiguration.class,
                                                             EssentialsComponentsConfiguration.class,
                                                             EventStoreConfiguration.class))
                    .withBean(EssentialsSecurityProvider.AllAccessSecurityProvider.class)
                    .withUserConfiguration(CaptureFlowConfiguration.class)
                    .withPropertyValues("spring.datasource.url=" + postgreSQLContainer.getJdbcUrl(),
                                        "spring.datasource.username=" + postgreSQLContainer.getUsername(),
                                        "spring.datasource.password=" + postgreSQLContainer.getPassword(),
                                        "essentials.eventstore.cdc.enabled=false",
                                        "essentials.eventstore.causation.index-enabled=true",
                                        "essentials.life-cycles.start-life-cycles=true");

    @BeforeEach
    void emptyDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        }
    }

    @Test
    void an_event_appended_lazily_by_a_REQUIRED_handler_records_the_event_that_triggered_it() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var orderPlaced = append(ctx, ORDERS, "order-1", new OrderPlaced("order-1"));

            var tally = awaitFirstEvent(ctx, TALLIES, "packing-order-1");

            assertThat(tally.causedByEventId()).contains(orderPlaced);
        });
    }

    @Test
    void an_event_reached_through_a_NONE_handler_and_an_Inbox_records_the_event_bound_before_the_hand_off() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var captureApproved = append(ctx, PAYMENTS, "payment-1", new CaptureApproved("payment-1"));

            var captureRecorded = awaitFirstEvent(ctx, TALLIES, "captured-payment-1");

            var fundsCaptureRequested = firstEvent(ctx, PAYMENTS, "payment-1", FundsCaptureRequested.class);
            assertThat(fundsCaptureRequested.causedByEventId()).as("committed by the NONE handler in its own UnitOfWork")
                                                               .contains(captureApproved);
            assertThat(captureRecorded.causedByEventId()).as("bound explicitly before the Inbox, carried across it, appended lazily at commit")
                                                         .contains(fundsCaptureRequested.eventId());

            // The admin API walks the chain back to its root
            var chain = ctx.getBean(EventStoreApi.class).findCausationChain("principal", captureRecorded.eventId(), 20);
            assertThat(chain).extracting(ApiCausationEvent::eventId)
                             .containsExactly(captureRecorded.eventId().toString(),
                                              fundsCaptureRequested.eventId().toString(),
                                              captureApproved.toString());
            assertThat(ctx.getBean(EventStoreApi.class).findEventsCausedBy("principal", captureApproved))
                    .extracting(ApiCausationEvent::eventId)
                    .containsExactly(fundsCaptureRequested.eventId().toString());
        });
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore(ApplicationContext ctx) {
        return ctx.getBean(ConfigurableEventStore.class);
    }

    private static EventId append(ApplicationContext ctx, AggregateType aggregateType, String aggregateId, Object event) {
        var eventStore = eventStore(ctx);
        return eventStore.getUnitOfWorkFactory()
                         .withUnitOfWork(() -> eventStore.appendToStream(aggregateType, aggregateId, event).eventList().getLast().eventId());
    }

    private static PersistedEvent awaitFirstEvent(ApplicationContext ctx, AggregateType aggregateType, String aggregateId) {
        var eventStore = eventStore(ctx);
        return Awaitility.waitAtMost(Duration.ofSeconds(30))
                         .until(() -> eventStore.getUnitOfWorkFactory()
                                                .withUnitOfWork(() -> eventStore.fetchStream(aggregateType, aggregateId)
                                                                                .map(stream -> stream.eventList().getFirst())),
                                Optional::isPresent)
                         .orElseThrow();
    }

    private static PersistedEvent firstEvent(ApplicationContext ctx, AggregateType aggregateType, String aggregateId, Class<?> eventType) {
        var eventStore = eventStore(ctx);
        return eventStore.getUnitOfWorkFactory()
                         .withUnitOfWork(() -> eventStore.fetchStream(aggregateType, aggregateId)
                                                         .orElseThrow()
                                                         .eventList()
                                                         .stream()
                                                         .filter(event -> event.event().getEventTypeAsJavaClass().filter(eventType::equals).isPresent())
                                                         .findFirst()
                                                         .orElseThrow());
    }

    // -------------------------------------------------------------------------------------------- the application

    record OrderPlaced(String orderId) {
    }

    record CaptureApproved(String paymentId) {
    }

    record FundsCaptureRequested(String paymentId) {
    }

    record RecordCaptureOutcome(String paymentId) {
    }

    public record TallyStarted(String tallyId) {
    }

    /**
     * Stands in for any aggregate a reaction creates; what matters is that the repository appends it lazily, at commit
     */
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

    @Configuration(proxyBeanMethods = false)
    static class CaptureFlowConfiguration {
        @Bean
        StatefulAggregateRepository<String, Object, Tally> tallies(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
            return StatefulAggregateRepository.from(eventStore, TALLIES, reflectionBasedAggregateRootFactory(), Tally.class);
        }

        @Bean
        Inbox paymentGatewayCallbacks(Inboxes inboxes, StatefulAggregateRepository<String, Object, Tally> tallies) {
            return inboxes.getOrCreateInbox(InboxConfig.builder()
                                                       .inboxName(InboxName.of("PaymentGatewayCallbacks"))
                                                       .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                       .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                       .numberOfParallelMessageConsumers(1)
                                                       .build(),
                                            message -> {
                                                var outcome = (RecordCaptureOutcome) message.getPayload();
                                                tallies.save(Tally.started("captured-" + outcome.paymentId()));
                                            });
        }

        @Bean
        CaptureFlowProcessor captureFlowProcessor(EventProcessorDependencies dependencies,
                                                  ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                                                  StatefulAggregateRepository<String, Object, Tally> tallies,
                                                  Inbox paymentGatewayCallbacks) {
            return new CaptureFlowProcessor(dependencies, eventStore, tallies, paymentGatewayCallbacks);
        }
    }

    static class CaptureFlowProcessor extends EventProcessor {
        private final ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
        private final StatefulAggregateRepository<String, Object, Tally>                       tallies;
        private final Inbox                                                                    paymentGatewayCallbacks;

        CaptureFlowProcessor(EventProcessorDependencies dependencies,
                             ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                             StatefulAggregateRepository<String, Object, Tally> tallies,
                             Inbox paymentGatewayCallbacks) {
            super(dependencies);
            this.eventStore = eventStore;
            this.tallies = tallies;
            this.paymentGatewayCallbacks = paymentGatewayCallbacks;
            eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
            eventStore.addAggregateEventStreamConfiguration(PAYMENTS, AggregateIdSerializer.serializerFor(String.class));
        }

        @Override
        public String getProcessorName() {
            return "CaptureFlowProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS, PAYMENTS);
        }

        /**
         * Shape 1: a reaction appended lazily, at the commit of the UnitOfWork the processor opened for this handler
         */
        @MessageHandler
        void on(OrderPlaced e) {
            tallies.save(Tally.started("packing-" + e.orderId()));
        }

        /**
         * Shape 2: commit before a blocking call, then hand the outcome back through an Inbox - as the webshop's capture
         * policy and the gateway's webhook do
         */
        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(CaptureApproved e) {
            var requested = withUnitOfWork(() -> eventStore.appendToStream(PAYMENTS, e.paymentId(), new FundsCaptureRequested(e.paymentId()))
                                                           .eventList()
                                                           .getLast()
                                                           .eventId());
            // ... the blocking call to the gateway happens here; its webhook later arrives on another request, which
            // looks up the FundsCaptureRequested it answers and binds it before handing the outcome on
            CausationContext.where(requested)
                            .run(() -> paymentGatewayCallbacks.addMessageReceived(new RecordCaptureOutcome(e.paymentId())));
        }

        @MessageHandler
        void on(FundsCaptureRequested e) {
            // The processor's own event comes back around through the subscription - nothing to do
        }
    }
}
