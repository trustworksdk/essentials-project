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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 end to end: a {@link PostgresqlEventStore} polling subscription whose optimizer has backed off to a long wait receives
 * an appended event within a small bound when the interruptible wait is on, and only after the wait when it is off (Phase 1).
 * The append is made only once the polling worker is seen entering a wait of at least {@link #LONG_WAIT_MS}, so the
 * outcome does not depend on machine speed.
 */
@Testcontainers
class NotifyInterruptibleWaitIT {
    private static final AggregateType ORDERS       = AggregateType.of("Orders");
    private static final long          LONG_WAIT_MS = 3_200; // 50 ms ramped x2 six times

    @Container
    private static final PostgreSQLContainer postgres = EssentialsTestContainers.postgres("notify-interruptible-wait");

    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private LocalEventBus                                                           bus;
    private MultiTableChangeListener<TableChangeNotification>                       listener;
    private NotifyEpochSource                                                       epochSource;
    private String                                                                  table;
    private Disposable                                                              subscription;

    @BeforeEach
    void setUp() {
        var jdbi = Jdbi.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
        bus = LocalEventBus.builder().busName("NotifyInterruptibleWaitIT").build();
        listener = new MultiTableChangeListener<>(jdbi, Duration.ofMillis(50), EssentialsObjectMappers.createJSONSerializer(), bus, true);
        listener.start();
        epochSource = new NotifyEpochSource(bus);
        persistenceStrategy.enableNotifyTriggers(EventStreamTableNotificationDuplicationFilter.registerWith(listener)::listenTo);

        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(String.class));
        table = eventStore.getAggregateEventStreamConfiguration(ORDERS).eventStreamTableName;
    }

    @AfterEach
    void tearDown() {
        if (subscription != null) subscription.dispose();
        if (epochSource != null) epochSource.close();
        if (listener != null && listener.isStarted()) listener.stop();
    }

    @Test
    void with_the_interruptible_wait_an_append_ends_a_long_wait_at_once() throws Exception {
        var optimizer = new RecordingOptimizer(notifyAware(true));
        var received  = subscribe(optimizer);
        assertThat(optimizer.longWaitStarted.await(30, TimeUnit.SECONDS)).as("worker backed off to a long wait").isTrue();

        long appendedAt = append("order-1");

        long receivedAt = received.poll(30, TimeUnit.SECONDS);
        long latencyMs  = TimeUnit.NANOSECONDS.toMillis(receivedAt - appendedAt);
        // The worker was in a wait of at least LONG_WAIT_MS (max-delay 60 s); the NOTIFY path is the listener's 50 ms poll plus one poll
        assertThat(latencyMs).as("wake-up latency").isLessThan(1_000);
    }

    @Test
    void without_the_interruptible_wait_the_append_waits_for_the_wait_to_end_as_before() throws Exception {
        var optimizer = new RecordingOptimizer(notifyAware(false));
        var received  = subscribe(optimizer);
        assertThat(optimizer.longWaitStarted.await(30, TimeUnit.SECONDS)).isTrue();
        long waitStartedAt = optimizer.longWaitStartedAt;

        append("order-1");

        long receivedAt = received.poll(30, TimeUnit.SECONDS);
        assertThat(TimeUnit.NANOSECONDS.toMillis(receivedAt - waitStartedAt)).as("received only after the full wait").isGreaterThanOrEqualTo(LONG_WAIT_MS);
    }

    @Test
    void plain_polling_uses_the_default_sleep_and_still_delivers() throws Exception {
        var optimizer = new RecordingOptimizer(new SimpleEventStorePollingOptimizer("plain", 50, 50, 200));
        var received  = subscribe(optimizer);

        append("order-1");

        assertThat(received.poll(30, TimeUnit.SECONDS)).isNotNull();
        assertThat(optimizer.pollsStarted.get()).isPositive();
    }

    @Test
    void cancelling_the_subscription_interrupts_a_long_interruptible_wait() throws Exception {
        var optimizer = new RecordingOptimizer(notifyAware(true));
        subscribe(optimizer);
        assertThat(optimizer.longWaitStarted.await(30, TimeUnit.SECONDS)).isTrue();

        subscription.dispose();

        assertThat(optimizer.waitInterrupted.await(5, TimeUnit.SECONDS)).as("the wait ended with an interrupt").isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private NotifyAwareEventStorePollingOptimizer notifyAware(boolean interruptibleWait) {
        return new NotifyAwareEventStorePollingOptimizer(epochSource,
                                                         table,
                                                         new NotifyPollingSettings(true, Duration.ofMillis(50), Duration.ofSeconds(60), 2.0, interruptibleWait));
    }

    private BlockingQueue<Long> subscribe(EventStorePollingOptimizer optimizer) {
        var received = new LinkedBlockingQueue<Long>();
        subscription = eventStore.pollEvents(ORDERS,
                                             GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(),
                                             Optional.empty(),
                                             Optional.of(Duration.ofMillis(50)),
                                             Optional.empty(),
                                             Optional.of(SubscriberId.of("s2-it")),
                                             Optional.of(logName -> optimizer))
                                 .subscribe(event -> received.add(System.nanoTime()));
        return received;
    }

    private long append(String orderId) {
        unitOfWorkFactory.usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, orderId, List.of(Map.of("orderId", orderId))));
        return System.nanoTime();
    }

    /** Delegates every call, and records the polling worker's waits */
    private static final class RecordingOptimizer implements EventStorePollingOptimizer {
        private final    EventStorePollingOptimizer        delegate;
        private final    CountDownLatch                    longWaitStarted = new CountDownLatch(1);
        private final    CountDownLatch                    waitInterrupted = new CountDownLatch(1);
        private final    AtomicInteger                     pollsStarted    = new AtomicInteger();
        private volatile long                              longWaitStartedAt;

        RecordingOptimizer(EventStorePollingOptimizer delegate) {
            this.delegate = delegate;
        }

        @Override
        public void eventStorePollingReturnedNoEvents() {
            delegate.eventStorePollingReturnedNoEvents();
        }

        @Override
        public void eventStorePollingReturnedEvents() {
            delegate.eventStorePollingReturnedEvents();
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldSkipPolling() {
            return delegate.shouldSkipPolling();
        }

        @Override
        public long currentDelayMs() {
            return delegate.currentDelayMs();
        }

        @Override
        public boolean mayRepollImmediatelyAfterAnEmptyPoll() {
            return delegate.mayRepollImmediatelyAfterAnEmptyPoll();
        }

        @Override
        public void eventStorePollingStarting() {
            pollsStarted.incrementAndGet();
            delegate.eventStorePollingStarting();
        }

        @Override
        public void awaitNextPoll(long delayMs) throws InterruptedException {
            if (delayMs >= LONG_WAIT_MS && longWaitStarted.getCount() > 0) {
                longWaitStartedAt = System.nanoTime();
                longWaitStarted.countDown();
            }
            try {
                delegate.awaitNextPoll(delayMs);
            } catch (InterruptedException e) {
                waitInterrupted.countDown();
                throw e;
            }
        }
    }
}
