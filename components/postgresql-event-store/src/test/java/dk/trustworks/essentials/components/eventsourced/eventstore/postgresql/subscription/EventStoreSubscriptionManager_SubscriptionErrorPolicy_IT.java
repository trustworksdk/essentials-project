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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what each {@link SubscriptionErrorPolicy} does with an event whose asynchronous handler throws: {@code SKIP}
 * keeps the pre-policy behaviour, {@code RETRY_N_THEN_SKIP} retries N times and then skips, {@code STOP} does not
 * advance past the failed event - including across a restart of the subscription manager.
 * <p>
 * Each test appends three events (global orders 1, 2 and 3) to its own aggregate type and fails the handler on #2.
 */
@Testcontainers
class EventStoreSubscriptionManager_SubscriptionErrorPolicy_IT {
    private static final long       FAILING_EVENT   = 2;
    private static final AtomicLong AGGREGATE_TYPES = new AtomicLong();

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4").withDatabaseName("event-store")
                                                                                                                .withUsername("test-user")
                                                                                                                .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private AggregateType                                                           aggregateType;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private DurableSubscriptionRepository                                           durableSubscriptionRepository;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private SubscriberId                                                            subscriberId;

    @BeforeEach
    void setup() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                           postgreSQLContainer.getUsername(),
                           postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());

        // A fresh aggregate type (and so a fresh event table) per test, so every test sees global orders 1, 2 and 3
        aggregateType = AggregateType.of("PolicyOrders" + AGGREGATE_TYPES.incrementAndGet());
        subscriberId = SubscriberId.of("PolicySubscriber-" + aggregateType);
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                                                                                                      IdentifierColumnType.UUID,
                                                                                                                                                                                      JSONColumnType.JSONB));
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory, persistenceStrategy);
        eventStore.addAggregateEventStreamConfiguration(aggregateType, OrderId.class);
        durableSubscriptionRepository = new PostgresqlDurableSubscriptionRepository(jdbi, eventStore);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        if (eventStoreSubscriptionManager != null) {
            eventStoreSubscriptionManager.stop();
        }
    }

    // ------------------------------------------------------------------------------------------------------------ SKIP

    @Test
    void by_default_a_failing_event_is_skipped_and_the_subscription_continues() {
        eventStoreSubscriptionManager = startSubscriptionManager(null);
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        subscribe(handled, attempts, () -> true);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        // The resume point advances past the skipped event, so it is never redelivered
        awaitDurableResumePoint(4);
        assertThat(((DefaultEventStoreSubscriptionManager) eventStoreSubscriptionManager).getSubscriptionErrorPolicy()).isEqualTo(SubscriptionErrorPolicy.skip());
    }

    @Test
    void an_explicit_skip_policy_behaves_like_the_default() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.skip());
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        subscribe(handled, attempts, () -> true);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        awaitDurableResumePoint(4);
    }

    // ------------------------------------------------------------------------------------------------ RETRY_N_THEN_SKIP

    @Test
    void retry_n_then_skip_retries_n_times_and_then_skips_the_event() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.retryThenSkip(3, Duration.ofMillis(20), Duration.ofMillis(50)));
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        subscribe(handled, attempts, () -> true);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 3L));
        // 1 attempt + 3 retries, then skipped
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(4);
        assertThat(attempts.get(3L).get()).isEqualTo(1);
        awaitDurableResumePoint(4);
    }

    @Test
    void retry_n_then_skip_delivers_an_event_that_succeeds_on_a_retry_before_the_next_event() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.retryThenSkip(3, Duration.ofMillis(50), Duration.ofMillis(100)));
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        // Fails the first two attempts of #2, succeeds on the second retry
        subscribe(handled, attempts, () -> attempts.get(FAILING_EVENT).get() <= 2);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(3);
        awaitDurableResumePoint(4);
    }

    // ------------------------------------------------------------------------------------------------------------ STOP

    @Test
    void stop_does_not_advance_past_the_failing_event_and_resumes_at_it_after_a_restart() throws InterruptedException {
        var failing = new AtomicBoolean(true);
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop());
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        subscribe(handled, attempts, failing::get);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> {
                      assertThat(handled).containsExactly(1L);
                      assertThat(attempts.get(FAILING_EVENT)).isNotNull();
                  });
        // Give a skipping subscriber ample time to move on - this one must not
        Thread.sleep(1500);
        assertThat(handled).containsExactly(1L);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        assertThat(attempts).doesNotContainKey(3L);
        assertThat(eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, aggregateType)).hasValue(GlobalEventOrder.of(FAILING_EVENT));
        awaitDurableResumePoint(FAILING_EVENT);

        // Restart while the cause persists: the event is redelivered - not skipped - and the subscription stops at it again
        eventStoreSubscriptionManager.stop();
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop());
        subscribe(handled, attempts, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(2));
        Thread.sleep(1000);
        assertThat(handled).containsExactly(1L);
        assertThat(attempts).doesNotContainKey(3L);
        awaitDurableResumePoint(FAILING_EVENT);

        // Restart after the cause is fixed: the subscription resumes at the failed event and continues
        failing.set(false);
        eventStoreSubscriptionManager.stop();
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop());
        subscribe(handled, attempts, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        awaitDurableResumePoint(4);
    }

    // ------------------------------------------------------------------------------------------------------- Batched

    @Test
    void batched_retry_n_then_skip_retries_the_batch_n_times_and_then_skips_it() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.retryThenSkip(2, Duration.ofMillis(20), Duration.ofMillis(50)));
        var batchAttempts = new AtomicInteger();
        var handled       = new CopyOnWriteArrayList<Long>();
        appendThreeEvents();

        batchSubscribe(batchAttempts, handled, () -> true);

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(batchAttempts.get()).isEqualTo(3));
        awaitDurableResumePoint(4);
        assertThat(batchAttempts.get()).isEqualTo(3);
        assertThat(handled).isEmpty();
    }

    @Test
    void batched_stop_does_not_advance_past_the_failing_batch_and_resumes_at_it_after_a_restart() throws InterruptedException {
        var failing = new AtomicBoolean(true);
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop());
        var batchAttempts = new AtomicInteger();
        var handled       = new CopyOnWriteArrayList<Long>();
        appendThreeEvents();

        batchSubscribe(batchAttempts, handled, failing::get);

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(batchAttempts.get()).isEqualTo(1));
        Thread.sleep(1500);
        assertThat(batchAttempts.get()).isEqualTo(1);
        assertThat(handled).isEmpty();
        assertThat(eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, aggregateType)).hasValue(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER);
        awaitDurableResumePoint(1);

        failing.set(false);
        eventStoreSubscriptionManager.stop();
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop());
        batchSubscribe(batchAttempts, handled, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        awaitDurableResumePoint(4);
    }

    // ------------------------------------------------------------------------------------------------------- Helpers

    private EventStoreSubscriptionManager startSubscriptionManager(SubscriptionErrorPolicy subscriptionErrorPolicy) {
        var builder = EventStoreSubscriptionManager.builder()
                                                   .setEventStore(eventStore)
                                                   .setEventStorePollingBatchSize(10)
                                                   .setEventStorePollingInterval(Duration.ofMillis(50))
                                                   .setFencedLockManager(PostgresqlFencedLockManager.builder()
                                                                                                    .setJdbi(jdbi)
                                                                                                    .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                                                    .setLockManagerInstanceId("Node1")
                                                                                                    .setLockTimeOut(Duration.ofSeconds(3))
                                                                                                    .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                                                                    .build())
                                                   .setSnapshotResumePointsEvery(Duration.ofMillis(200))
                                                   .setDurableSubscriptionRepository(durableSubscriptionRepository);
        if (subscriptionErrorPolicy != null) {
            builder.setSubscriptionErrorPolicy(subscriptionErrorPolicy);
        }
        var manager = builder.build();
        manager.start();
        return manager;
    }

    /**
     * @param handled   global orders handled successfully, in handling order
     * @param attempts  handling attempts per global order
     * @param failEvent whether an attempt at #2 fails, asked after the attempt has been counted
     */
    private void subscribe(List<Long> handled, Map<Long, AtomicInteger> attempts, java.util.function.BooleanSupplier failEvent) {
        eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(subscriberId,
                                                                               aggregateType,
                                                                               GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                               Optional.empty(),
                                                                               (PersistedEventHandler) event -> {
                                                                                   var globalOrder = event.globalEventOrder().longValue();
                                                                                   attempts.computeIfAbsent(globalOrder, order -> new AtomicInteger()).incrementAndGet();
                                                                                   if (globalOrder == FAILING_EVENT && failEvent.getAsBoolean()) {
                                                                                       throw new IllegalStateException("Intentional failure handling event #" + globalOrder);
                                                                                   }
                                                                                   handled.add(globalOrder);
                                                                               });
    }

    private void batchSubscribe(AtomicInteger batchAttempts, List<Long> handled, java.util.function.BooleanSupplier failBatchContainingFailingEvent) {
        eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                    aggregateType,
                                                                                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                    Optional.empty(),
                                                                                    10,
                                                                                    Duration.ofMillis(200),
                                                                                    events -> {
                                                                                        var globalOrders = events.stream().map(e -> e.globalEventOrder().longValue()).toList();
                                                                                        if (globalOrders.contains(FAILING_EVENT)) {
                                                                                            batchAttempts.incrementAndGet();
                                                                                            if (failBatchContainingFailingEvent.getAsBoolean()) {
                                                                                                throw new IllegalStateException("Intentional failure handling batch " + globalOrders);
                                                                                            }
                                                                                        }
                                                                                        handled.addAll(globalOrders);
                                                                                        return events.size();
                                                                                    });
    }

    private void appendThreeEvents() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (int i = 0; i < 3; i++) {
                var orderId = OrderId.random();
                eventStore.appendToStream(aggregateType, orderId, List.of(new OrderEvent.OrderAccepted(orderId)));
            }
        });
    }

    private void awaitDurableResumePoint(long expectedResumeFromAndIncluding) {
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableSubscriptionRepository.getResumePoint(subscriberId, aggregateType))
                          .hasValueSatisfying(resumePoint -> assertThat(resumePoint.getResumeFromAndIncluding().longValue()).isEqualTo(expectedResumeFromAndIncluding)));
    }

    private static class TestPersistableEventMapper implements PersistableEventMapper {
        private final CorrelationId correlationId   = CorrelationId.random();
        private final EventId       causedByEventId = EventId.random();

        @Override
        public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration aggregateEventStreamConfiguration, Object event, EventOrder eventOrder) {
            return PersistableEvent.from(EventId.random(),
                                         aggregateEventStreamConfiguration.aggregateType,
                                         aggregateId,
                                         EventTypeOrName.with(event.getClass()),
                                         event,
                                         eventOrder,
                                         EventRevision.of(1),
                                         EventMetaData.of("Key1", "Value1"),
                                         OffsetDateTime.now(),
                                         causedByEventId,
                                         correlationId,
                                         null);
        }
    }
}
