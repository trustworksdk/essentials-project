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

package dk.trustworks.essentials.examples.perflab;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventTypeOrName;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.examples.perflab.harness.*;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntFunction;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What recording event causation costs, as an interleaved A/B on the one thing that differs: whether the
 * {@link CausationPersistableEventEnricher} is registered (the starter's {@code essentials.eventstore.causation.enabled}).
 * <p>
 * Both arms run the same code otherwise, including the {@link CausationContext} bindings at every delivery site - those
 * are unconditional, so they are part of the baseline both arms share, not part of what is compared. See the performance
 * gate in {@code docs/event-causation.md}.
 * <p>
 * Two shapes:
 * <ul>
 *     <li><b>Append path</b> - events appended one per UnitOfWork with a cause bound, as a reacting handler does. This is
 *     the enricher's direct cost: one {@code PersistableEvent} copy per event and one more column value written.</li>
 *     <li><b>Processor chain</b> - an {@link EventProcessor} reacting to every event by creating an aggregate through a
 *     {@link StatefulAggregateRepository}, whose events are appended lazily when the handler's UnitOfWork commits. No
 *     other lab scenario drives this path, and it is the one phase 4 changes, so it is measured end to end from the
 *     start.</li>
 * </ul>
 * <b>Reading the result.</b> WAL bytes per event is a count of work that does not move with the machine, and it is
 * <em>expected</em> to differ: the enabled arm writes one more column value per event - a 36-character event id plus
 * its varlena header, about 40 bytes. A WAL difference much larger than that would mean causation costs more than the
 * value it stores, which is the finding to look for. Throughput and append latency are the regression check proper:
 * their distributions should overlap, and a run that separates them is a finding to explain, not a number to tune.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class EventCausationCostIT {
    private static final Logger log = LoggerFactory.getLogger(EventCausationCostIT.class);

    private static final int           APPEND_EVENT_COUNT    = Integer.getInteger("lab.causation.append-events", 5_000);
    private static final int           PROCESSOR_EVENT_COUNT = Integer.getInteger("lab.causation.processor-events", 2_000);
    private static final int           QUEUE_MESSAGE_COUNT   = Integer.getInteger("lab.causation.queue-messages", 5_000);
    private static final int           REPETITIONS           = Integer.getInteger("lab.causation.repetitions", 5);
    /**
     * Enough parallel inbox consumers, and a fast enough fetcher, that the processor chain is bound by the work each
     * delivery does rather than by the queue's poll interval - with the defaults it delivered exactly one message per
     * 20 ms poll, and both arms measured 50 events/s with an IQR of zero, which is a measurement of configuration
     */
    private static final int           PROCESSOR_CONSUMERS   = Integer.getInteger("lab.causation.processor-consumers", 8);
    private static final AggregateType ORDERS                = AggregateType.of("orders");
    private static final AggregateType SHIPMENTS             = AggregateType.of("shipments");
    private static final String        ORDERS_TABLE          = ORDERS + "_events";
    private static final String        SHIPMENTS_TABLE       = SHIPMENTS + "_events";
    private static final String        ARM_OFF               = "causation-off";
    private static final String        ARM_ON                = "causation-on";

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ------------------------------------------------------------------------------------------------- append path

    @Test
    void cost_of_recording_causation_on_the_append_path() {
        var environment = PgSnapshot.captureEnvironment(dataSource);
        var results     = new AbRunner(REPETITIONS).run(arms(enabled -> repetition -> measureAppendPath(enabled, repetition, environment)));

        report("APPEND PATH", APPEND_EVENT_COUNT, results);
        RunResult.writeAll("target/perf-lab-baseline/event-causation-append-path.json",
                           Map.of("comparison", "event causation, append path",
                                  "eventCount", APPEND_EVENT_COUNT,
                                  "environment", environment,
                                  "summaries", AbRunner.summarize(results),
                                  "runs", results));

        results.forEach(result -> {
            assertThat(result.opsCompleted()).isEqualTo(APPEND_EVENT_COUNT);
            assertThat((Long) result.extra().get("eventsWithACause"))
                    .as("%s rep %d: the arm must actually do what its name says", result.arm(), result.repetition())
                    .isEqualTo(result.arm().equals(ARM_ON) ? APPEND_EVENT_COUNT : 0L);
        });
    }

    private RunResult measureAppendPath(boolean causationEnabled, int repetition, Map<String, String> environment) {
        recreateSchema();
        var setup   = EventStoreSetup.create(dataSource, causationEnabled);
        var latency = new LatencyRecorder("append");
        // A stand-in for "the event this reaction was caused by"; its value does not matter, its presence does
        var cause = EventId.random();

        var before     = PgSnapshot.capture(dataSource, List.of(ORDERS_TABLE));
        var startNanos = System.nanoTime();
        CausationContext.where(cause).run(() -> {
            for (var index = 0; index < APPEND_EVENT_COUNT; index++) {
                var orderId     = "order-" + index;
                var appendStart = System.nanoTime();
                setup.unitOfWorkFactory.usingUnitOfWork(() -> setup.eventStore.appendToStream(ORDERS, orderId, new OrderPlaced(orderId)));
                latency.recordDuration(System.nanoTime() - appendStart);
            }
        });
        var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        var after         = PgSnapshot.capture(dataSource, List.of(ORDERS_TABLE));

        return result("append-path", causationEnabled, repetition, elapsedMillis, APPEND_EVENT_COUNT, before, after, environment,
                      List.of(latency.responseTimeSummary()),
                      Map.of("eventsWithACause", countEventsWithACause(ORDERS_TABLE)));
    }

    // --------------------------------------------------------------------------------------------- processor chain

    @Test
    void cost_of_recording_causation_through_an_EventProcessor_and_a_lazily_appending_repository() {
        var environment = PgSnapshot.captureEnvironment(dataSource);
        var results     = new AbRunner(REPETITIONS).run(arms(enabled -> repetition -> measureProcessorChain(enabled, repetition, environment)));

        report("PROCESSOR CHAIN", PROCESSOR_EVENT_COUNT, results);
        RunResult.writeAll("target/perf-lab-baseline/event-causation-processor-chain.json",
                           Map.of("comparison", "event causation, EventProcessor -> StatefulAggregateRepository",
                                  "eventCount", PROCESSOR_EVENT_COUNT,
                                  "environment", environment,
                                  "summaries", AbRunner.summarize(results),
                                  "runs", results));

        results.forEach(result -> {
            assertThat(result.opsCompleted()).isEqualTo(PROCESSOR_EVENT_COUNT);
            assertThat((Long) result.extra().get("eventsWithACause"))
                    .as("%s rep %d: the arm must actually do what its name says", result.arm(), result.repetition())
                    .isEqualTo(result.arm().equals(ARM_ON) ? PROCESSOR_EVENT_COUNT : 0L);
        });
    }

    /**
     * Seeds the triggering events first and times only the processor draining them, so both arms are charged the same
     * work and the fenced-lock acquisition at start-up is not part of the figure.
     */
    private RunResult measureProcessorChain(boolean causationEnabled, int repetition, Map<String, String> environment) {
        recreateSchema();
        var setup = EventStoreSetup.create(dataSource, causationEnabled);
        try (var infrastructure = ProcessorInfrastructure.start(dataSource, setup)) {
            for (var index = 0; index < PROCESSOR_EVENT_COUNT; index++) {
                var orderId = "order-" + index;
                setup.unitOfWorkFactory.usingUnitOfWork(() -> setup.eventStore.appendToStream(ORDERS, orderId, new OrderPlaced(orderId)));
            }

            var before    = PgSnapshot.capture(dataSource, List.of(ORDERS_TABLE, SHIPMENTS_TABLE));
            var processor = new ShipOrdersProcessor(infrastructure.dependencies(), setup.shipments);
            processor.start();
            try {
                Awaitility.await()
                          .atMost(Duration.ofSeconds(300))
                          .pollInterval(Duration.ofMillis(20))
                          .until(() -> countRows(SHIPMENTS_TABLE) == PROCESSOR_EVENT_COUNT);
                var completedNanos = System.nanoTime();
                var elapsedMillis  = (completedNanos - processor.firstHandledNanos.get()) / 1_000_000L;
                var after          = PgSnapshot.capture(dataSource, List.of(ORDERS_TABLE, SHIPMENTS_TABLE));

                return result("processor-chain", causationEnabled, repetition, elapsedMillis, PROCESSOR_EVENT_COUNT, before, after, environment,
                              List.of(),
                              Map.of("eventsWithACause", countEventsWithACause(SHIPMENTS_TABLE),
                                     "handlerInvocations", processor.handled.get()));
            } finally {
                processor.stop();
            }
        }
    }

    // -------------------------------------------------------------------------------------------------- queue path

    @Test
    void cost_of_carrying_causation_across_a_durable_queue() {
        var environment = PgSnapshot.captureEnvironment(dataSource);
        var results     = new AbRunner(REPETITIONS).run(arms(enabled -> repetition -> measureQueuePath(enabled, repetition, environment)));

        report("QUEUE PATH", QUEUE_MESSAGE_COUNT, results);
        RunResult.writeAll("target/perf-lab-baseline/event-causation-queue-path.json",
                           Map.of("comparison", "event causation, durable queue hand-off",
                                  "messageCount", QUEUE_MESSAGE_COUNT,
                                  "environment", environment,
                                  "summaries", AbRunner.summarize(results),
                                  "runs", results));

        results.forEach(result -> {
            assertThat(result.opsCompleted()).isEqualTo(QUEUE_MESSAGE_COUNT);
            assertThat((Long) result.extra().get("messagesHandledWithACause"))
                    .as("%s rep %d: the arm must actually do what its name says", result.arm(), result.repetition())
                    .isEqualTo(result.arm().equals(ARM_ON) ? QUEUE_MESSAGE_COUNT : 0L);
        });
    }

    /**
     * Messages queued one per transaction with a cause bound - the shape of {@code Inbox.addMessageReceived} - while a
     * consumer drains them. The enabled arm registers the {@link CausationDurableQueuesInterceptor}, which adds one
     * metadata entry per message on the way in and re-binds it on the way out.
     */
    private RunResult measureQueuePath(boolean causationEnabled, int repetition, Map<String, String> environment) {
        recreateSchema();
        var setup         = EventStoreSetup.create(dataSource, causationEnabled);
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(setup.unitOfWorkFactory())
                                                   .setUseCentralizedMessageFetcher(true)
                                                   .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(5))
                                                   .build();
        if (causationEnabled) {
            durableQueues.addInterceptor(new CausationDurableQueuesInterceptor());
        }
        durableQueues.start();
        var queueName        = QueueName.of("causation-cost");
        var handled          = new AtomicInteger();
        var handledWithCause = new AtomicLong();
        var consumer = durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                                      .setQueueName(queueName)
                                                                      .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                      .setParallelConsumers(PROCESSOR_CONSUMERS)
                                                                      .setQueueMessageHandler(message -> {
                                                                          if (CausationContext.current().isPresent()) {
                                                                              handledWithCause.incrementAndGet();
                                                                          }
                                                                          handled.incrementAndGet();
                                                                      })
                                                                      .build());
        try {
            var cause      = EventId.random();
            var before     = PgSnapshot.capture(dataSource, List.of(PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
            var startNanos = System.nanoTime();
            CausationContext.where(cause).run(() -> {
                for (var index = 0; index < QUEUE_MESSAGE_COUNT; index++) {
                    durableQueues.queueMessage(queueName, Message.of(new OrderPlaced("order-" + index)));
                }
            });
            Awaitility.await()
                      .atMost(Duration.ofSeconds(300))
                      .pollInterval(Duration.ofMillis(10))
                      .until(() -> handled.get() >= QUEUE_MESSAGE_COUNT);
            var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            var after         = PgSnapshot.capture(dataSource, List.of(PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));

            return result("queue-path", causationEnabled, repetition, elapsedMillis, QUEUE_MESSAGE_COUNT, before, after, environment,
                          List.of(),
                          Map.of("messagesHandledWithACause", handledWithCause.get()));
        } finally {
            consumer.stop();
            durableQueues.stop();
        }
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private static Map<String, IntFunction<RunResult>> arms(java.util.function.Function<Boolean, IntFunction<RunResult>> measure) {
        var arms = new LinkedHashMap<String, IntFunction<RunResult>>();
        arms.put(ARM_OFF, measure.apply(false));
        arms.put(ARM_ON, measure.apply(true));
        return arms;
    }

    private static void report(String title, int eventCount, List<RunResult> results) {
        var summaries = AbRunner.summarize(results);
        log.info("");
        log.info("======== EVENT CAUSATION COST - {}, {} events x {} reps ========", title, eventCount, REPETITIONS);
        log.info(String.format("%-14s %14s %10s %14s %10s %14s", "arm", "events/s", "IQR", "WAL B/event", "IQR", "p50 append us"));
        for (var summary : summaries) {
            log.info(String.format("%-14s %14.0f %10.0f %14.1f %10.1f %14.0f",
                                   summary.arm(),
                                   summary.throughputPerSecond().median(),
                                   summary.throughputPerSecond().interQuartileRange(),
                                   summary.walBytesPerOperation().median(),
                                   summary.walBytesPerOperation().interQuartileRange(),
                                   summary.responseTimeP50Micros().median()));
        }
        var off = summaries.stream().filter(summary -> summary.arm().equals(ARM_OFF)).findFirst().orElseThrow();
        var on  = summaries.stream().filter(summary -> summary.arm().equals(ARM_ON)).findFirst().orElseThrow();
        log.info("Throughput: {}   Append latency p50: {}",
                 off.throughputPerSecond().overlaps(on.throughputPerSecond()) ? "OVERLAP (no measurable difference)" : "SEPARATED",
                 off.responseTimeP50Micros().overlaps(on.responseTimeP50Micros()) ? "OVERLAP (no measurable difference)" : "SEPARATED");
        log.info("WAL per event/message: {} bytes more with causation (the cause value is ~40 bytes; a queue message also carries its metadata key)",
                 String.format("%+.1f", on.walBytesPerOperation().median() - off.walBytesPerOperation().median()));
        log.info("=====================================================================");
    }

    private static RunResult result(String scenario,
                                    boolean causationEnabled,
                                    int repetition,
                                    long elapsedMillis,
                                    int eventCount,
                                    PgSnapshot before,
                                    PgSnapshot after,
                                    Map<String, String> environment,
                                    List<LatencyRecorder.Summary> latencies,
                                    Map<String, Object> extra) {
        return new RunResult("event-causation-" + scenario,
                             causationEnabled ? ARM_ON : ARM_OFF,
                             repetition,
                             Instant.now(),
                             elapsedMillis,
                             eventCount,
                             elapsedMillis == 0 ? 0.0d : eventCount * 1000.0d / elapsedMillis,
                             Map.of("eventCount", eventCount, "causationEnabled", causationEnabled),
                             latencies,
                             after.deltaFrom(before),
                             Map.of(),
                             environment,
                             extra);
    }

    private void recreateSchema() {
        Jdbi.create(dataSource).useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
    }

    private long countRows(String table) {
        return Jdbi.create(dataSource).withHandle(handle -> handle.createQuery("SELECT count(*) FROM " + table).mapTo(Long.class).one());
    }

    private long countEventsWithACause(String table) {
        return Jdbi.create(dataSource).withHandle(handle -> handle.createQuery("SELECT count(*) FROM " + table + " WHERE caused_by_event_id IS NOT NULL")
                                                                  .mapTo(Long.class)
                                                                  .one());
    }

    /**
     * An event store whose only variable is whether the causation enricher is registered. The mapper sets no cause, as
     * the starter's default mapper does, so the enricher alone decides.
     */
    private record EventStoreSetup(Jdbi jdbi,
                                   EventStoreManagedUnitOfWorkFactory unitOfWorkFactory,
                                   PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                                   StatefulAggregateRepository<String, ShipmentEvent, Shipment> shipments) {

        static EventStoreSetup create(HikariDataSource dataSource, boolean causationEnabled) {
            var jdbi              = Jdbi.create(dataSource).installPlugin(new PostgresPlugin());
            var unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
            PersistableEventMapper mapper = (aggregateId, configuration, event, eventOrder) ->
                    PersistableEvent.builder()
                                    .setEvent(event)
                                    .setAggregateType(configuration.aggregateType)
                                    .setAggregateId(aggregateId)
                                    .setEventTypeOrName(EventTypeOrName.with(event.getClass()))
                                    .setEventOrder(eventOrder)
                                    .build();
            var persistenceStrategy = SeparateTablePerAggregateTypePersistenceStrategy.builder()
                                                                                      .setJdbi(jdbi)
                                                                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                      .setEventMapper(mapper)
                                                                                      .setAggregateEventStreamConfigurationFactory(
                                                                                              SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(
                                                                                                      EssentialsJSONEventSerializers.create(),
                                                                                                      IdentifierColumnType.TEXT,
                                                                                                      JSONColumnType.JSONB))
                                                                                      .setPersistableEventEnrichers(causationEnabled
                                                                                                                    ? List.of(new CausationPersistableEventEnricher())
                                                                                                                    : List.of())
                                                                                      .build();
            var eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
            eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
            var shipments = StatefulAggregateRepository.from(eventStore,
                                                             SHIPMENTS,
                                                             reflectionBasedAggregateRootFactory(),
                                                             Shipment.class);
            return new EventStoreSetup(jdbi, unitOfWorkFactory, eventStore, shipments);
        }
    }

    /**
     * Everything an {@link EventProcessor} needs, started and stopped as one
     */
    private record ProcessorInfrastructure(PostgresqlFencedLockManager lockManager,
                                           EventStoreSubscriptionManager subscriptionManager,
                                           PostgresqlDurableQueues durableQueues,
                                           DurableLocalCommandBus commandBus) implements AutoCloseable {

        static ProcessorInfrastructure start(HikariDataSource dataSource, EventStoreSetup setup) {
            var lockManager = PostgresqlFencedLockManager.builder()
                                                         .setJdbi(setup.jdbi())
                                                         .setUnitOfWorkFactory(setup.unitOfWorkFactory())
                                                         .setLockTimeOut(Duration.ofSeconds(3))
                                                         .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                         .buildAndStart();
            var subscriptionManager = EventStoreSubscriptionManager.builder()
                                                                   .setEventStore(setup.eventStore())
                                                                   .setFencedLockManager(lockManager)
                                                                   .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(setup.jdbi(), setup.eventStore()))
                                                                   .build();
            var durableQueues = PostgresqlDurableQueues.builder()
                                                       .setUnitOfWorkFactory(setup.unitOfWorkFactory())
                                                       .setUseCentralizedMessageFetcher(true)
                                                       .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(5))
                                                       .build();
            var commandBus = DurableLocalCommandBus.builder()
                                                   .setDurableQueues(durableQueues)
                                                   .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(setup.unitOfWorkFactory()))
                                                   .build();
            durableQueues.start();
            subscriptionManager.start();
            commandBus.start();
            return new ProcessorInfrastructure(lockManager, subscriptionManager, durableQueues, commandBus);
        }

        EventProcessorDependencies dependencies() {
            return new EventProcessorDependencies(subscriptionManager,
                                                  Inboxes.durableQueueBasedInboxes(durableQueues, lockManager),
                                                  commandBus,
                                                  List.of());
        }

        @Override
        public void close() {
            commandBus.stop();
            subscriptionManager.stop();
            durableQueues.stop();
            lockManager.stop();
        }
    }

    // ------------------------------------------------------------------------------------------------- domain model

    public record OrderPlaced(String orderId) {
    }

    public sealed interface ShipmentEvent permits ShipmentRequested {
    }

    public record ShipmentRequested(String shipmentId, String orderId) implements ShipmentEvent {
    }

    public static class Shipment extends AggregateRoot<String, ShipmentEvent, Shipment> {
        private String orderId;

        public Shipment(String shipmentId) {
            super(shipmentId);
        }

        public Shipment(String shipmentId, String orderId) {
            this(shipmentId);
            apply(new ShipmentRequested(shipmentId, orderId));
        }

        @EventHandler
        private void on(ShipmentRequested e) {
            orderId = e.orderId();
        }
    }

    /**
     * Reacts to every order by requesting a shipment - a new aggregate, saved through the repository and therefore
     * appended lazily when the handler's UnitOfWork commits
     */
    static class ShipOrdersProcessor extends EventProcessor {
        final AtomicLong    firstHandledNanos = new AtomicLong();
        final AtomicInteger handled           = new AtomicInteger();

        private final StatefulAggregateRepository<String, ShipmentEvent, Shipment> shipments;

        ShipOrdersProcessor(EventProcessorDependencies dependencies, StatefulAggregateRepository<String, ShipmentEvent, Shipment> shipments) {
            super(dependencies);
            this.shipments = shipments;
        }

        @Override
        public String getProcessorName() {
            return "ShipOrdersProcessor";
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @Override
        protected int getNumberOfParallelInboxMessageConsumers() {
            return PROCESSOR_CONSUMERS;
        }

        @MessageHandler
        void on(OrderPlaced e) {
            firstHandledNanos.compareAndSet(0, System.nanoTime());
            shipments.save(new Shipment("shipment-" + e.orderId(), e.orderId()));
            handled.incrementAndGet();
        }
    }
}
