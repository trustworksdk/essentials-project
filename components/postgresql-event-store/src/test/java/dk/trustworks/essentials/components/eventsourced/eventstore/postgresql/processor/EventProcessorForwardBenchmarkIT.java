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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.reactive.command.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.reactive.LocalEventBus;
import com.zaxxer.hikari.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drain rate of an {@link EventProcessor} over a backlog, opt-in via {@code -Dbenchmark.run=true}: EF-2
 * ({@code docs/key-partitioned-event-processing.md} §5, "Field evidence").
 * <p>
 * Every order stream carries five events, as the trading demo's {@code Orders} stream does. In the
 * {@code two_of_five} shape the processor has handlers for two of them, as the demo's {@code ExchangeOrderRouter} and
 * {@code OrderLedgerProcessor} do; in {@code all_five} it handles every event, the control that shows what reading,
 * forwarding and handling cost when nothing can be skipped. Two times per run, both from {@code start()}: <b>forward</b>
 * until every event with a handler is in the inbox (handled, or still queued) — the reader's rate, which EF-2 is about —
 * and <b>drain</b> until every one of them was handled, which adds the single inbox consumer. {@code view_all_five} is
 * a {@link ViewEventProcessor} handling all five on the reader's thread, so its forward and drain are one number: the
 * reader's rate is what a projection gets end to end.
 * <p>
 * Each repeat uses its own aggregate type and processor name, so its event table, resume point and inbox queue are
 * fresh. {@code mvn verify -pl components/postgresql-event-store -Dbenchmark.run=true -Dit.test=EventProcessorForwardBenchmarkIT}
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class EventProcessorForwardBenchmarkIT {
    private static final Logger   log      = LoggerFactory.getLogger(EventProcessorForwardBenchmarkIT.class);
    private static final int      ORDERS   = Integer.getInteger("benchmark.forward.orders", 2_000);
    private static final int      REPEATS  = Integer.getInteger("benchmark.forward.repeats", 3);
    private static final Duration TIME_OUT = Duration.ofMinutes(5);

    @Container
    private final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private PostgresqlFencedLockManager                                             fencedLockManager;
    private PostgresqlDurableQueues                                                 durableQueues;
    private DurableLocalCommandBus                                                  commandBus;
    private EventProcessorDependencies                                              dependencies;
    private ViewEventProcessorDependencies                                          viewDependencies;
    private HikariDataSource                                                        dataSource;
    private MultiTableChangeListener<TableChangeNotification>                       changeListener;

    @BeforeEach
    void setup() {
        // Pooled like an application: Jdbi.create(url, ...) would open a physical connection per unit of work
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        hikariConfig.setUsername(postgreSQLContainer.getUsername());
        hikariConfig.setPassword(postgreSQLContainer.getPassword());
        hikariConfig.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(hikariConfig);
        var jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new PostgresPlugin());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var jsonSerializer = EssentialsJSONEventSerializers.create();
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       standardSingleTenantConfiguration(jsonSerializer,
                                                                                                                         IdentifierColumnType.TEXT,
                                                                                                                         JSONColumnType.JSONB));
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(eventStore -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                         .setEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver())
                                         .build();
        fencedLockManager = PostgresqlFencedLockManager.builder()
                                                       .setEventBus(eventStore.localEventBus())
                                                       .setJdbi(jdbi)
                                                       .setLockTimeOut(Duration.ofSeconds(3))
                                                       .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .buildAndStart();
        eventStoreSubscriptionManager = EventStoreSubscriptionManager.builder()
                                                                     .setEventStore(eventStore)
                                                                     .setFencedLockManager(fencedLockManager)
                                                                     .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(jdbi, eventStore))
                                                                     .setSnapshotResumePointsEvery(Duration.ofSeconds(1))
                                                                     // The trading demo's compose profile
                                                                     .setEventStorePollingBatchSize(100)
                                                                     .setEventStorePollingInterval(Duration.ofMillis(200))
                                                                     .build();
        eventStoreSubscriptionManager.start();
        // Wired as the Spring Boot starter does (postgresql-queue's SpringWiredDurableQueues), since that is what users run
        changeListener = new MultiTableChangeListener<>(jdbi, Duration.ofMillis(50), EssentialsObjectMappers.createJSONSerializer(), new LocalEventBus("benchmark-change-listener"), true);
        changeListener.start();
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setJsonSerializer(jsonSerializer)
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setMultiTableChangeListener(changeListener)
                                               .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
                                               .setCentralizedQueuePollingOptimizerFactory(queueName -> new CentralizedQueuePollingOptimizer(queueName, 20, 2_000, 1.5, 0.1))
                                               .build();
        durableQueues.start();
        commandBus = DurableLocalCommandBus.builder()
                                           .setInterceptors(new UnitOfWorkControllingCommandBusInterceptor(unitOfWorkFactory))
                                           .setDurableQueues(durableQueues)
                                           .build();
        commandBus.start();
        dependencies = new EventProcessorDependencies(eventStoreSubscriptionManager, new Inboxes.DurableQueueBasedInboxes(durableQueues, fencedLockManager), commandBus, List.of());
        viewDependencies = new ViewEventProcessorDependencies(eventStoreSubscriptionManager, fencedLockManager, durableQueues, commandBus, List.of());
    }

    @AfterEach
    void teardown() {
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
        if (changeListener != null) {
            changeListener.stop();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void two_of_five() {
        measure("two_of_five", aggregateType -> new TwoOfFiveProcessor(dependencies, aggregateType), 2);
    }

    @Test
    void all_five() {
        measure("all_five", aggregateType -> new AllFiveProcessor(dependencies, aggregateType), 5);
    }

    @Test
    void view_all_five() {
        measure("view_all_five", aggregateType -> new AllFiveViewProcessor(viewDependencies, aggregateType), 5);
    }

    private interface BenchProcessor {
        AtomicInteger handled();

        /**
         * @return the inbox the reader forwards to, or null when the reader handles the events itself
         */
        QueueName inboxQueueName();

        void start();

        void stop();
    }

    private interface ProcessorFactory {
        BenchProcessor create(AggregateType aggregateType);
    }

    private void measure(String shape, ProcessorFactory factory, int handledPerOrder) {
        var rates = new ArrayList<Double>();
        for (var repeat = 0; repeat < REPEATS; repeat++) {
            var aggregateType = AggregateType.of("Bench" + shape.replace("_", "") + repeat);
            eventStore.addAggregateEventStreamConfiguration(aggregateType, AggregateIdSerializer.serializerFor(EventProcessorIT.OrderId.class));
            seed(aggregateType);

            var processor = factory.create(aggregateType);
            var expected  = ORDERS * handledPerOrder;
            var started   = System.nanoTime();
            processor.start();
            try {
                Duration forwarded = null;
                while (processor.handled().get() < expected) {
                    assertThat(Duration.ofNanos(System.nanoTime() - started)).as("%s handled %d of %d", shape, processor.handled().get(), expected).isLessThan(TIME_OUT);
                    if (forwarded == null && processor.inboxQueueName() != null &&
                            processor.handled().get() + durableQueues.getTotalMessagesQueuedFor(processor.inboxQueueName()) >= expected) {
                        forwarded = Duration.ofNanos(System.nanoTime() - started);
                    }
                    sleep();
                }
                var drained = Duration.ofNanos(System.nanoTime() - started);
                if (forwarded == null) {
                    forwarded = drained;
                }
                var rate = ORDERS * 5 / (forwarded.toNanos() / 1e9);
                rates.add(rate);
                log.info("BENCHMARK EventProcessorForward shape={} repeat={} orders={} events={} handled={} forward_ms={} drain_ms={} events_read_per_s={}",
                         shape, repeat, ORDERS, ORDERS * 5, expected, forwarded.toMillis(), drained.toMillis(), String.format("%.0f", rate));
            } finally {
                processor.stop();
            }
        }
        var sorted = rates.stream().sorted().toList();
        log.info("BENCHMARK EventProcessorForward shape={} median_events_read_per_s={} all={}", shape, String.format("%.0f", sorted.get(sorted.size() / 2)), rates);
    }

    private void seed(AggregateType aggregateType) {
        var perUnitOfWork = 200;
        for (var from = 0; from < ORDERS; from += perUnitOfWork) {
            var first = from;
            unitOfWorkFactory.usingUnitOfWork(() -> {
                for (var i = first; i < Math.min(first + perUnitOfWork, ORDERS); i++) {
                    var orderId = EventProcessorIT.OrderId.of("O-" + i);
                    eventStore.appendToStream(aggregateType, orderId, List.of(new BenchPlaced(orderId), new BenchSubmitted(orderId), new BenchAccepted(orderId),
                                                                              new BenchFilled(orderId), new BenchClosed(orderId)));
                }
            });
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public record BenchPlaced(EventProcessorIT.OrderId orderId) {
    }

    public record BenchSubmitted(EventProcessorIT.OrderId orderId) {
    }

    public record BenchAccepted(EventProcessorIT.OrderId orderId) {
    }

    public record BenchFilled(EventProcessorIT.OrderId orderId) {
    }

    public record BenchClosed(EventProcessorIT.OrderId orderId) {
    }

    abstract static class CountingProcessor extends EventProcessor implements BenchProcessor {
        final         AtomicInteger handled = new AtomicInteger();
        private final AggregateType aggregateType;

        CountingProcessor(EventProcessorDependencies dependencies, AggregateType aggregateType) {
            super(dependencies);
            this.aggregateType = aggregateType;
        }

        @Override
        public String getProcessorName() {
            return "Bench" + aggregateType;
        }

        @Override
        public AtomicInteger handled() {
            return handled;
        }

        @Override
        public QueueName inboxQueueName() {
            return getInbox().name().asQueueName();
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(aggregateType);
        }
    }

    static class TwoOfFiveProcessor extends CountingProcessor {
        TwoOfFiveProcessor(EventProcessorDependencies dependencies, AggregateType aggregateType) {
            super(dependencies, aggregateType);
        }

        @MessageHandler
        void on(BenchPlaced e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchFilled e) {
            handled.incrementAndGet();
        }
    }

    static class AllFiveProcessor extends CountingProcessor {
        AllFiveProcessor(EventProcessorDependencies dependencies, AggregateType aggregateType) {
            super(dependencies, aggregateType);
        }

        @MessageHandler
        void on(BenchPlaced e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchSubmitted e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchAccepted e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchFilled e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchClosed e) {
            handled.incrementAndGet();
        }
    }

    static class AllFiveViewProcessor extends ViewEventProcessor implements BenchProcessor {
        private final AtomicInteger handled = new AtomicInteger();
        private final AggregateType aggregateType;

        AllFiveViewProcessor(ViewEventProcessorDependencies dependencies, AggregateType aggregateType) {
            super(dependencies);
            this.aggregateType = aggregateType;
        }

        @Override
        public String getProcessorName() {
            return "BenchView" + aggregateType;
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(aggregateType);
        }

        @Override
        public AtomicInteger handled() {
            return handled;
        }

        @Override
        public QueueName inboxQueueName() {
            return null;
        }

        @MessageHandler
        void on(BenchPlaced e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchSubmitted e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchAccepted e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchFilled e) {
            handled.incrementAndGet();
        }

        @MessageHandler
        void on(BenchClosed e) {
            handled.incrementAndGet();
        }
    }
}
