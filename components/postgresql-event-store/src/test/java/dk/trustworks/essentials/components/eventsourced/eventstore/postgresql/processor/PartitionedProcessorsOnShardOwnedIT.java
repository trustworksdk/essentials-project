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

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueues;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.*;

/**
 * {@link PartitionedViewEventProcessor} and the reset rule both partitioned processors share ({@link StoppedEverywhereReset}),
 * on the shard-owned engine with several instances in one JVM - each instance its own lock manager, subscription manager,
 * engine registry and instance id, as separate processes would have.
 * <p>
 * The reset tests construct each hazard and assert at the moment it holds: another instance still consuming; an
 * instance whose stop is still waiting for a handler; an instance starting while a reset runs elsewhere.
 */
@Testcontainers
class PartitionedProcessorsOnShardOwnedIT {
    private static final String        VIEW      = "PartitionedView";
    private static final String        PROCESSOR = "PartitionedProcessor";
    private static final AggregateType ORDERS    = AggregateType.of("PvOrders");
    private static final AggregateType RETURNS   = AggregateType.of("PvReturns");

    @Container
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

    private final List<AutoCloseable>                  toClose  = new CopyOnWriteArrayList<>();
    /**
     * "aggregateType|key|order@instance" per handled event, in handling order
     */
    private final ConcurrentLinkedQueue<String>        handled  = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String>        resets   = new ConcurrentLinkedQueue<>();
    private volatile CountDownLatch                    holdHandler = new CountDownLatch(0);
    private volatile CountDownLatch                    holdReset   = new CountDownLatch(0);
    private volatile CountDownLatch                    handlerEntered = new CountDownLatch(1);
    private HikariDataSource                           dataSource;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private EventStoreManagedUnitOfWorkFactory         unitOfWorkFactory;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(60);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        Jdbi.create(dataSource).useHandle(handle -> handle.execute("DROP TABLE IF EXISTS fenced_locks, durable_subscriptions, pvorders_events, pvreturns_events CASCADE"));
        ShardOwnedSchema.registerQueue(dataSource, engineName(QueueName.of(VIEW + ":queue")), 2);
        ShardOwnedSchema.registerQueue(dataSource, engineName(InboxName.of(PROCESSOR).asQueueName()), 2);
        ShardOwnedSchema.registerQueue(dataSource, engineName(DurableLocalCommandBus.DEFAULT_COMMAND_QUEUE_NAME), 2);

        var jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new PostgresPlugin());
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new TestPersistableEventMapper(),
                                                                                       standardSingleTenantConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                                         IdentifierColumnType.TEXT,
                                                                                                                         JSONColumnType.JSONB));
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStreamGapHandlerFactory(store -> new PostgresqlEventStreamGapHandler<>(unitOfWorkFactory))
                                         .build();
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));
        eventStore.addAggregateEventStreamConfiguration(RETURNS, AggregateIdSerializer.serializerFor(OrderId.class));
    }

    @AfterEach
    void tearDown() {
        holdHandler.countDown();
        holdReset.countDown();
        var closeables = new ArrayList<>(toClose);
        Collections.reverse(closeables);
        for (var closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                // best effort
            }
        }
        dataSource.close();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Handling
    // ------------------------------------------------------------------------------------------------------------------

    @Test
    void two_instances_both_handle_and_every_aggregate_is_handled_in_event_order() {
        var a = instance("a");
        var b = instance("b");
        a.view.start();
        b.view.start();
        awaitConsumers(a, 2);
        // Let the ordered units spread over both instances before any event arrives, so no unit moves mid-run
        sleep(Duration.ofSeconds(3));

        var aggregates = 40;
        var perAggregate = 10;
        var ids = new ArrayList<OrderId>();
        for (var i = 0; i < aggregates; i++) {
            ids.add(OrderId.random());
        }
        for (var round = 0; round < perAggregate; round++) {
            var r = round;
            unitOfWorkFactory.usingUnitOfWork(() -> ids.forEach(id -> eventStore.appendToStream(ORDERS, id, new OrderPlacedEvent(id, "e" + r))));
        }

        Awaitility.await().atMost(Duration.ofSeconds(60))
                  .untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(aggregates * perAggregate));
        var perKey = new HashMap<String, List<Long>>();
        var instances = new HashSet<String>();
        for (var entry : handled) {
            var at = entry.indexOf('@');
            var event = entry.substring(0, at);
            instances.add(entry.substring(at + 1));
            var lastBar = event.lastIndexOf('|');
            perKey.computeIfAbsent(event.substring(0, lastBar), k -> new ArrayList<>()).add(Long.parseLong(event.substring(lastBar + 1)));
        }
        assertThat(instances).as("instances that handled").containsExactlyInAnyOrder("a", "b");
        perKey.forEach((key, orders) -> assertThat(orders).as("orders handled for %s", key).isSorted());
    }

    @Test
    void the_same_aggregate_id_in_two_aggregate_types_is_two_keys_and_handlers_see_the_plain_id() {
        var a = instance("a");
        a.view.start();
        var id = OrderId.random();
        for (var i = 0; i < 5; i++) {
            var n = i;
            unitOfWorkFactory.usingUnitOfWork(() -> {
                eventStore.appendToStream(ORDERS, id, new OrderPlacedEvent(id, "o" + n));
                eventStore.appendToStream(RETURNS, id, new OrderPlacedEvent(id, "r" + n));
            });
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(10));
        assertThat(handled).allSatisfy(entry -> assertThat(entry).contains("|" + id + "|"));
        for (var type : List.of(ORDERS, RETURNS)) {
            assertThat(handled.stream().filter(entry -> entry.startsWith(type + "|")).map(entry -> Long.parseLong(entry.substring(entry.lastIndexOf('|') + 1, entry.indexOf('@')))).toList())
                    .as("orders handled for %s", type)
                    .containsExactly(0L, 1L, 2L, 3L, 4L);
        }
    }

    @Test
    void a_view_forwarding_payloads_handles_every_event_in_order_with_the_plain_id_and_the_event_as_cause() {
        forwardPayloads = true;
        var a = instance("a");
        var b = instance("b");
        a.view.start();
        b.view.start();
        var id = OrderId.random();
        var eventIds = new ArrayList<String>();
        for (var i = 0; i < 10; i++) {
            var n = i;
            eventIds.add(unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(ORDERS, id, new OrderPlacedEvent(id, "o" + n)).eventList().getFirst().eventId().toString()));
        }

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(10));
        assertThat(handled.stream().map(entry -> entry.substring(0, entry.indexOf('@'))).distinct().toList())
                .containsExactly(java.util.stream.IntStream.range(0, 10).mapToObj(n -> ORDERS + "|" + id + "|" + n).toArray(String[]::new));
        assertThat(causes).as("each handler bound to its event as the cause").containsAll(eventIds);
        // The queue carried payloads, not references
        long deadLetters = Jdbi.create(dataSource).withHandle(handle -> handle.createQuery("SELECT count(*) FROM shard_queue_dead_letter").mapTo(Long.class).one());
        assertThat(deadLetters).isZero();
    }

    @Test
    void await_processed_waits_on_the_qualified_key() {
        var a = instance("a");
        a.view.start();
        holdHandler = new CountDownLatch(1);
        var id = OrderId.random();
        var event = unitOfWorkFactory.withUnitOfWork(() -> eventStore.appendToStream(ORDERS, id, new OrderPlacedEvent(id, "held")).eventList().getFirst());

        assertThatThrownBy(() -> a.view.awaitProcessed(event, Duration.ofSeconds(2))).isInstanceOf(AwaitProcessedTimeoutException.class);
        holdHandler.countDown();
        a.view.awaitProcessed(event, Duration.ofSeconds(20));
        assertThat(distinctHandled()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Reset: only while stopped on every instance
    // ------------------------------------------------------------------------------------------------------------------

    @Test
    void a_reset_is_refused_while_another_instance_still_consumes_and_runs_once_every_instance_stopped() {
        var a = instance("a");
        var b = instance("b");
        a.view.start();
        b.view.start();
        appendOrders(5);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(5));

        a.view.stop();
        assertThatThrownBy(() -> a.view.resetAllSubscriptions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reset refused");
        assertThat(resets).isEmpty();

        b.view.stop();
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(consumers(a)).isZero());
        a.view.resetAllSubscriptions();
        assertThat(resets).containsExactly(ORDERS + "@a", RETURNS + "@a");

        // The events are handled again from the start
        handled.clear();
        b.view.start();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(5));
    }

    @Test
    void a_reset_is_refused_while_the_processor_is_started_on_this_instance() {
        var a = instance("a");
        a.view.start();
        assertThatThrownBy(() -> a.view.resetAllSubscriptions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("started on this instance");
        assertThat(resets).isEmpty();
    }

    @Test
    void a_reset_runs_from_an_instance_that_never_read_and_moves_the_stored_resume_point() {
        var a = instance("a");
        a.view.start();
        appendOrders(4);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(4));
        a.view.stop();
        var never = instance("never-started");
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(consumers(never)).isZero());

        never.view.resetAllSubscriptions();

        assertThat(resets).containsExactly(ORDERS + "@never-started", RETURNS + "@never-started");
        assertThat(storedResumePoint(VIEW, ORDERS)).isEqualTo(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue());
        handled.clear();
        a.view.start();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(4));
    }

    @Test
    void a_reset_is_refused_while_a_stopped_instance_is_still_waiting_for_its_handler_however_long_that_takes() throws Exception {
        var a = instance("a");
        var other = stopWhileAHandlerRuns(a);
        // Past the lease TTL (1 s): a is no longer a live consumer, but it still holds the unit its handler runs in
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(consumers(other)).isZero());
        sleep(Duration.ofSeconds(3));
        assertThat(departed(other)).as("departed instances holding units while a's handler runs").isEqualTo(1);
        assertThatThrownBy(() -> other.view.resetAllSubscriptions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 departed instance(s) still hold part of");
        assertThat(resets).isEmpty();

        // The handler returns, the stop hands the unit back, and the reset runs
        holdHandler.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(departed(other)).isZero());
        other.view.resetAllSubscriptions();
        assertThat(resets).containsExactly(ORDERS + "@other", RETURNS + "@other");
    }

    @Test
    void a_reset_asked_to_ignore_departed_instances_runs_while_a_stopped_instance_still_holds_a_unit_but_not_while_one_consumes() throws Exception {
        var a = instance("a");
        var other = stopWhileAHandlerRuns(a);
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(departed(other)).isEqualTo(1));

        var live = instance("live");
        live.view.start();
        awaitConsumers(live, 1);
        assertThatThrownBy(() -> other.view.resetAllSubscriptionsIgnoringDepartedInstances())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Reset refused");
        live.view.stop();
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(consumers(other)).isZero());

        other.view.resetAllSubscriptionsIgnoringDepartedInstances();
        assertThat(resets).containsExactly(ORDERS + "@other", RETURNS + "@other");
    }

    /**
     * Instance {@code a} handles one event with its handler held, then is stopped on another thread - a stop that cannot
     * finish while the handler runs. Returns another, never started, instance to reset from.
     */
    private Instance stopWhileAHandlerRuns(Instance a) throws InterruptedException {
        a.view.start();
        holdHandler = new CountDownLatch(1);
        handlerEntered = new CountDownLatch(1);
        appendOrders(1);
        assertThat(handlerEntered.await(20, TimeUnit.SECONDS)).as("handler entered").isTrue();
        var stopping = new Thread(a.view::stop);
        stopping.start();
        toClose.add(() -> stopping.join(30_000));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !a.view.isStarted());
        return instance("other");
    }

    @Test
    void an_instance_starting_while_a_reset_runs_elsewhere_registers_its_consumer_only_after_the_reset() throws Exception {
        var resetter = instance("resetter");
        var starter  = instance("starter");
        holdReset = new CountDownLatch(1);
        var resetFailure = new AtomicReference<Throwable>();
        var resetting = new Thread(() -> {
            try {
                resetter.view.resetAllSubscriptions();
            } catch (Throwable e) {
                resetFailure.set(e);
            }
        });
        resetting.start();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !resets.isEmpty() || resetFailure.get() != null);
        assertThat(resetFailure.get()).isNull();

        var startFailure = new AtomicReference<Throwable>();
        var starting = new Thread(() -> {
            try {
                starter.view.start();
            } catch (Throwable e) {
                startFailure.set(e);
            }
        });
        starting.start();
        sleep(Duration.ofSeconds(3));
        assertThat(consumers(starter)).as("consumers registered while the reset runs").isZero();

        holdReset.countDown();
        resetting.join(20_000);
        starting.join(30_000);
        assertThat(startFailure.get()).isNull();
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(consumers(starter)).isEqualTo(1));
    }

    @Test
    void a_partitioned_event_processor_resets_only_while_stopped_on_every_instance() {
        var a = instance("a");
        var b = instance("b");
        a.processor.start();
        b.processor.start();
        appendOrders(3);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(3));

        assertThatThrownBy(() -> a.processor.resetAllSubscriptions()).hasMessageContaining("started on this instance");
        a.processor.stop();
        assertThatThrownBy(() -> a.processor.resetAllSubscriptions()).hasMessageContaining("Reset refused");
        assertThat(resets).isEmpty();

        b.processor.stop();
        Awaitility.await().atMost(Duration.ofSeconds(20))
                  .untilAsserted(() -> assertThat(a.durableQueues.getQueueConsumers(InboxName.of(PROCESSOR).asQueueName()).orElseThrow().none()).isTrue());
        var never = instance("never-started");
        never.processor.resetAllSubscriptions();
        assertThat(resets).containsExactly(ORDERS + "@processor-never-started");
        assertThat(storedResumePoint(PROCESSOR, ORDERS)).isEqualTo(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue());

        handled.clear();
        b.processor.start();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(distinctHandled()).isEqualTo(3));
    }

    // ------------------------------------------------------------------------------------------------------------------

    private void appendOrders(int count) {
        var id = OrderId.random();
        for (var i = 0; i < count; i++) {
            var n = i;
            unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, id, new OrderPlacedEvent(id, "e" + n)));
        }
    }

    private long distinctHandled() {
        return handled.stream().map(entry -> entry.substring(0, entry.indexOf('@'))).distinct().count();
    }

    private int consumers(Instance instance) {
        return instance.durableQueues.getQueueConsumers(QueueName.of(VIEW + ":queue")).orElseThrow().liveInstances();
    }

    private int departed(Instance instance) {
        return instance.durableQueues.getQueueConsumers(QueueName.of(VIEW + ":queue")).orElseThrow().departedInstancesHoldingUnits();
    }

    private void awaitConsumers(Instance instance, int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(consumers(instance)).isEqualTo(expected));
    }

    private long storedResumePoint(String processorName, AggregateType aggregateType) {
        return Jdbi.create(dataSource).withHandle(handle -> handle.createQuery("SELECT resume_from_and_including_global_eventorder FROM durable_subscriptions WHERE subscriber_id = :id AND aggregate_type = :type")
                                                                  .bind("id", AbstractEventProcessor.resolveSubscriberId(aggregateType, processorName).toString())
                                                                  .bind("type", aggregateType.toString())
                                                                  .mapTo(Long.class)
                                                                  .one());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Instance instance(String id) {
        var lockManager = PostgresqlFencedLockManager.builder()
                                                     .setJdbi(Jdbi.create(dataSource))
                                                     .setUnitOfWorkFactory(unitOfWorkFactory)
                                                     .setLockManagerInstanceId(id)
                                                     .setLockTimeOut(Duration.ofSeconds(2))
                                                     .setLockConfirmationInterval(Duration.ofMillis(500))
                                                     .buildAndStart();
        toClose.add(lockManager::stop);
        var subscriptionManager = EventStoreSubscriptionManager.builder()
                                                               .setEventStore(eventStore)
                                                               .setFencedLockManager(lockManager)
                                                               .setDurableSubscriptionRepository(new PostgresqlDurableSubscriptionRepository(Jdbi.create(dataSource), eventStore))
                                                               .setSnapshotResumePointsEvery(Duration.ofMillis(200))
                                                               .build();
        subscriptionManager.start();
        toClose.add(subscriptionManager::stop);
        var queues = new RegisteredMessageQueues(dataSource, id);
        toClose.add(queues);
        var durableQueues = ShardOwnedDurableQueues.builder()
                                                   .setQueues(queues)
                                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setDataSource(dataSource)
                                                   .build();
        durableQueues.start();
        toClose.add(durableQueues::stop);
        var commandBus = DurableLocalCommandBus.builder().setDurableQueues(durableQueues).build();
        commandBus.start();
        toClose.add(commandBus::stop);
        var view = new View(new ViewEventProcessorDependencies(subscriptionManager, lockManager, durableQueues, commandBus, List.of()), id);
        toClose.add(view::stop);
        var processor = new Processor(new EventProcessorDependencies(subscriptionManager,
                                                                     new Inboxes.DurableQueueBasedInboxes(durableQueues, lockManager),
                                                                     commandBus,
                                                                     List.of()),
                                      "processor-" + id);
        toClose.add(processor::stop);
        return new Instance(durableQueues, view, processor);
    }

    private record Instance(ShardOwnedDurableQueues durableQueues, View view, Processor processor) {
    }

    private void record(OrderPlacedEvent event, OrderedMessage message, String instance) throws InterruptedException {
        causes.add(dk.trustworks.essentials.components.foundation.causation.CausationContext.current().map(Object::toString).orElse("none"));
        handlerEntered.countDown();
        holdHandler.await();
        // The handler sees the plain id; the aggregate type is told apart by the details' prefix
        var type = event.orderDetails.startsWith("r") ? RETURNS : ORDERS;
        handled.add(type + "|" + message.getKey() + "|" + message.getOrder() + "@" + instance);
    }

    private void onReset(AggregateType aggregateType, String instance) {
        resets.add(aggregateType + "@" + instance);
        try {
            holdReset.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private volatile boolean forwardPayloads;
    private final ConcurrentLinkedQueue<String> causes = new ConcurrentLinkedQueue<>();

    private class View extends PartitionedViewEventProcessor {
        private final String instance;

        @Override
        protected boolean forwardsEventPayloads() {
            return forwardPayloads;
        }

        View(ViewEventProcessorDependencies dependencies, String instance) {
            super(dependencies);
            this.instance = instance;
        }

        @Override
        public String getProcessorName() {
            return VIEW;
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS, RETURNS);
        }

        @Override
        protected int getNumberOfParallelQueuedMessageConsumers() {
            return 4;
        }

        @Override
        protected RedeliveryPolicy getDurableQueueRedeliveryPolicy() {
            return RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 100);
        }

        @MessageHandler
        void on(OrderPlacedEvent event, OrderedMessage message) throws InterruptedException {
            record(event, message, instance);
        }

        @Override
        protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
            onReset(aggregateType, instance);
        }
    }

    private class Processor extends PartitionedEventProcessor {
        private final String instance;

        Processor(EventProcessorDependencies dependencies, String instance) {
            super(dependencies);
            this.instance = instance;
        }

        @Override
        public String getProcessorName() {
            return PROCESSOR;
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(ORDERS);
        }

        @Override
        protected RedeliveryPolicy getInboxRedeliveryPolicy() {
            return RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 100);
        }

        @MessageHandler
        void on(OrderPlacedEvent event, OrderedMessage message) throws InterruptedException {
            record(event, message, instance);
        }

        @Override
        protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
            onReset(aggregateType, instance);
        }
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    /**
     * Builds each engine queue on demand with a one-second lease, as the adapter's own ITs do
     */
    private static final class RegisteredMessageQueues
            implements dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues, AutoCloseable {
        private final javax.sql.DataSource dataSource;
        private final String               instanceId;
        private final Map<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName, PostgresqlMessageQueue> built = new LinkedHashMap<>();

        RegisteredMessageQueues(javax.sql.DataSource dataSource, String instanceId) {
            this.dataSource = dataSource;
            this.instanceId = instanceId;
        }

        @Override
        public synchronized List<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName> queueNames() throws java.sql.SQLException {
            return ShardOwnedSchema.queueNames(dataSource);
        }

        @Override
        public synchronized Optional<dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue> findQueue(
                dk.trustworks.essentials.components.queue.shardowned.spi.QueueName queueName) throws java.sql.SQLException {
            if (ShardOwnedSchema.resolve(dataSource, queueName).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(built.computeIfAbsent(queueName, name -> PostgresqlMessageQueue.builder()
                                                                                              .setDataSource(dataSource)
                                                                                              .setQueueName(name)
                                                                                              .setInstanceId(instanceId)
                                                                                              .setSettings(ShardOwnerSettings.builder()
                                                                                                                             .setSweepInterval(Duration.ofMillis(100))
                                                                                                                             .setMaxSweepInterval(Duration.ofMillis(200))
                                                                                                                             .setLeaseTtl(Duration.ofSeconds(1))
                                                                                                                             .build())
                                                                                              .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(PostgresqlMessageQueue::close);
        }
    }
}
