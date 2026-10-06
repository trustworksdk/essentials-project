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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.SubscriptionStoppedMicrometerMonitor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.measurement.MeasurementTaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what each {@link SubscriptionErrorPolicy} does with an event whose asynchronous handler throws: {@code SKIP}
 * keeps the pre-policy behaviour, {@code RETRY_N_THEN_SKIP} retries N times and then skips, {@code STOP} does not
 * advance past the failed event - including across a restart of the subscription manager - and is visible through
 * {@link EventStoreSubscription#isStoppedByErrorPolicy()} and the observer. Stopping the manager while a retry is backing
 * off must not skip the event (or batch) either, and a batched subscription backing off must not hold up another one.
 * <p>
 * Each test appends three events (global orders 1, 2 and 3) to its own aggregate type and fails the handler on #2.
 */
@Testcontainers
class EventStoreSubscriptionManager_SubscriptionErrorPolicy_IT {
    private static final long       FAILING_EVENT   = 2;
    private static final AtomicLong AGGREGATE_TYPES = new AtomicLong();

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:18.4").withDatabaseName("event-store")
                                                                                                                .withUsername("test-user")
                                                                                                                .withPassword("secret-password");

    private Jdbi                                                                    jdbi;
    private AggregateType                                                           aggregateType;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private DurableSubscriptionRepository                                           durableSubscriptionRepository;
    private EventStoreSubscriptionManager                                           eventStoreSubscriptionManager;
    private SubscriberId                                                            subscriberId;
    private SimpleMeterRegistry                                                     meterRegistry;
    private PostgresqlFencedLockManager                                             fencedLockManager;

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
        meterRegistry = new SimpleMeterRegistry();
        eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                         .setUnitOfWorkFactory(unitOfWorkFactory)
                                         .setPersistenceStrategy(persistenceStrategy)
                                         .setEventStoreSubscriptionObserver(new MeasurementEventStoreSubscriptionObserver(MeasurementTaker.none(), null, meterRegistry))
                                         .build();
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

    // --------------------------------------------------------------------------------------------------------- Default

    /**
     * The default policy - {@code RETRY_N_THEN_STOP} with auto-resume - end to end, at its real delays: 1 attempt and 3
     * retries, a stop, and a resume by itself 10 s later at the failed event, which then succeeds. Nothing is skipped
     */
    @Test
    void by_default_a_failing_event_is_retried_then_stopped_at_and_resumed_by_itself() {
        eventStoreSubscriptionManager = startSubscriptionManager(null);
        assertThat(((DefaultEventStoreSubscriptionManager) eventStoreSubscriptionManager).getSubscriptionErrorPolicy()).isEqualTo(SubscriptionErrorPolicy.defaultPolicy());
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        // Fails the first attempt and every retry, succeeds once resumed
        var subscription = subscribe(handled, attempts, () -> attempts.get(FAILING_EVENT).get() <= 1 + SubscriptionErrorPolicy.DEFAULT_MAX_RETRIES);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1 + SubscriptionErrorPolicy.DEFAULT_MAX_RETRIES);
        assertThat(handled).containsExactly(1L);
        awaitStoppedByErrorPolicyCount(1);

        Awaitility.waitAtMost(SubscriptionErrorPolicy.AutoResume.DEFAULT_INITIAL_DELAY.plusSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(2 + SubscriptionErrorPolicy.DEFAULT_MAX_RETRIES);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        awaitDurableResumePoint(4);
        assertThat(skippedAfterAutoResumesCount()).isZero();
    }

    // ------------------------------------------------------------------------------------------------------------ SKIP

    @Test
    void an_explicit_skip_policy_skips_the_failing_event_and_continues() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.skip());
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        var subscription = subscribe(handled, attempts, () -> true);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        // The resume point advances past the skipped event, so it is never redelivered
        awaitDurableResumePoint(4);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        assertThat(stoppedByErrorPolicyCount()).isZero();
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

    @Test
    void stopping_the_subscription_during_a_retry_backoff_does_not_skip_the_event() {
        var failing = new AtomicBoolean(true);
        // A retry budget of about 9 seconds, far more than the test takes to stop the manager in the middle of it
        var policy = SubscriptionErrorPolicy.retryThenSkip(5, Duration.ofSeconds(1), Duration.ofSeconds(2));
        eventStoreSubscriptionManager = startSubscriptionManager(policy);
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        subscribe(handled, attempts, failing::get);

        appendThreeEvents();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(attempts.get(FAILING_EVENT)).isNotNull());

        // Stops (disposes) the subscription while #2 sleeps in its backoff - this interrupts the backoff
        eventStoreSubscriptionManager.stop();
        eventStoreSubscriptionManager = null;

        // Abandoned, not given up on: not skipped, not counted as failed, and the durable resume point stays at #2
        assertThat(attempts.get(FAILING_EVENT).get()).isLessThan(1 + policy.maxRetries());
        assertThat(handled).containsExactly(1L);
        assertThat(attempts).doesNotContainKey(3L);
        assertThat(handleEventFailedCount()).isZero();
        awaitDurableResumePoint(FAILING_EVENT);

        // A restarted manager handles #2 again, and then continues
        var attemptsBeforeRestart = attempts.get(FAILING_EVENT).get();
        failing.set(false);
        eventStoreSubscriptionManager = startSubscriptionManager(policy);
        subscribe(handled, attempts, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(attemptsBeforeRestart + 1);
        awaitDurableResumePoint(4);
    }

    // ------------------------------------------------------------------------------------------------------------ STOP

    @Test
    void stop_does_not_advance_past_the_failing_event_and_resumes_at_it_after_a_restart() throws InterruptedException {
        var failing = new AtomicBoolean(true);
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop().withoutAutoResume());
        var handled  = new CopyOnWriteArrayList<Long>();
        var attempts = new ConcurrentHashMap<Long, AtomicInteger>();
        var subscription = subscribe(handled, attempts, failing::get);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> {
                      assertThat(handled).containsExactly(1L);
                      assertThat(attempts.get(FAILING_EVENT)).isNotNull();
                  });
        // The stop is visible outside the subscriber - while the subscription stays active, as it keeps running (and any lock)
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
        assertThat(subscription.isActive()).isTrue();
        awaitStoppedByErrorPolicyCount(1);
        // Give a skipping subscriber ample time to move on - this one must not
        Thread.sleep(1500);
        assertThat(handled).containsExactly(1L);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        assertThat(attempts).doesNotContainKey(3L);
        assertThat(eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, aggregateType)).hasValue(GlobalEventOrder.of(FAILING_EVENT));
        awaitDurableResumePoint(FAILING_EVENT);

        // Restart while the cause persists: the event is redelivered - not skipped - and the subscription stops at it again
        eventStoreSubscriptionManager.stop();
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop().withoutAutoResume());
        var restartedSubscription = subscribe(handled, attempts, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(2));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(restartedSubscription::isStoppedByErrorPolicy);
        awaitStoppedByErrorPolicyCount(2);
        Thread.sleep(1000);
        assertThat(handled).containsExactly(1L);
        assertThat(attempts).doesNotContainKey(3L);
        awaitDurableResumePoint(FAILING_EVENT);

        // Restart after the cause is fixed: the subscription resumes at the failed event and continues
        failing.set(false);
        eventStoreSubscriptionManager.stop();
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop().withoutAutoResume());
        var fixedSubscription = subscribe(handled, attempts, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        awaitDurableResumePoint(4);
        assertThat(fixedSubscription.isStoppedByErrorPolicy()).isFalse();
        awaitStoppedByErrorPolicyCount(2);
    }

    // ------------------------------------------------------------------------------------------- RETRY_N_THEN_STOP + resume

    @Test
    void retry_n_then_stop_retries_n_times_then_stops_and_a_resume_continues_at_the_failed_event() throws InterruptedException {
        var failing = new AtomicBoolean(true);
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.retryThenStop(2, Duration.ofMillis(20), Duration.ofMillis(50)).withoutAutoResume());
        var handled      = new CopyOnWriteArrayList<Long>();
        var attempts     = new ConcurrentHashMap<Long, AtomicInteger>();
        var subscription = subscribe(handled, attempts, failing::get);

        appendThreeEvents();

        // 1 attempt + 2 retries, then stopped - not skipped
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(3);
        assertThat(subscription.isActive()).isTrue();
        awaitStoppedByErrorPolicyCount(1);
        Thread.sleep(1000);
        assertThat(handled).containsExactly(1L);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(3);
        assertThat(attempts).doesNotContainKey(3L);
        awaitDurableResumePoint(FAILING_EVENT);

        // Resumed while the cause persists: the failed event is retried per the policy again, and the subscription stops at it again
        assertThat(subscription.resumeIfStoppedByErrorPolicy()).isTrue();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(6));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
        awaitStoppedByErrorPolicyCount(2);
        assertThat(handled).containsExactly(1L);
        awaitDurableResumePoint(FAILING_EVENT);

        // Resumed after the cause is fixed: the failed event is handled once, then the subscription continues
        failing.set(false);
        assertThat(eventStoreSubscriptionManager.resumeSubscriptionIfStoppedByErrorPolicy(subscriberId, aggregateType)).isTrue();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(7);
        assertThat(attempts.get(1L).get()).isEqualTo(1);
        assertThat(attempts.get(3L).get()).isEqualTo(1);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        assertThat(subscription.isActive()).isTrue();
        awaitDurableResumePoint(4);
        awaitStoppedByErrorPolicyCount(2);

        // No longer stopped: a resume is a no-op
        assertThat(subscription.resumeIfStoppedByErrorPolicy()).isFalse();
    }

    @Test
    void resuming_a_subscription_that_is_not_stopped_does_nothing_and_returns_false() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.skip());
        var handled      = new CopyOnWriteArrayList<Long>();
        var attempts     = new ConcurrentHashMap<Long, AtomicInteger>();
        var subscription = subscribe(handled, attempts, () -> true);

        appendThreeEvents();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 3L));
        awaitDurableResumePoint(4);

        assertThat(subscription.resumeIfStoppedByErrorPolicy()).isFalse();
        assertThat(eventStoreSubscriptionManager.resumeSubscriptionIfStoppedByErrorPolicy(subscriberId, aggregateType)).isFalse();
        assertThat(eventStoreSubscriptionManager.resumeSubscriptionIfStoppedByErrorPolicy(SubscriberId.of("Unknown"), aggregateType)).isFalse();
        // Nothing was redelivered or restarted
        assertThat(attempts.get(1L).get()).isEqualTo(1);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        assertThat(subscription.isActive()).isTrue();
        awaitDurableResumePoint(4);
    }

    @Test
    void an_exclusive_subscription_stopped_by_its_error_policy_is_resumed_without_releasing_its_fenced_lock() throws InterruptedException {
        var failing = new AtomicBoolean(true);
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop().withoutAutoResume());
        var handled      = new CopyOnWriteArrayList<Long>();
        var attempts     = new ConcurrentHashMap<Long, AtomicInteger>();
        var subscription = exclusivelySubscribe(handled, attempts, failing::get);
        var lockName     = ((ExclusiveSubscription) subscription).lockName();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isActive);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        awaitDurableResumePoint(FAILING_EVENT);
        var lockTokenWhileStopped = fencedLockManager.lookupLock(lockName).orElseThrow().getCurrentToken();
        assertThat(fencedLockManager.isLockedByThisLockManagerInstance(lockName)).isTrue();

        failing.set(false);
        assertThat(subscription.resumeIfStoppedByErrorPolicy()).isTrue();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(2);
        assertThat(attempts.get(3L).get()).isEqualTo(1);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        awaitDurableResumePoint(4);
        // The lock was held throughout - neither released nor re-acquired
        assertThat(subscription.isActive()).isTrue();
        assertThat(fencedLockManager.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(fencedLockManager.lookupLock(lockName).orElseThrow().getCurrentToken()).isEqualTo(lockTokenWhileStopped);
        assertThat(subscription.resumeIfStoppedByErrorPolicy()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------ Auto-resume

    @Test
    void a_stopped_subscription_resumes_by_itself_at_the_failed_event_until_it_succeeds() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.retryThenStop(1, Duration.ofMillis(10), Duration.ofMillis(10))
                                                                                        .withAutoResume(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofMillis(200), Duration.ofMillis(400))));
        var handled      = new CopyOnWriteArrayList<Long>();
        var attempts     = new ConcurrentHashMap<Long, AtomicInteger>();
        // Attempt + retry fail three times over (= 3 stops); the attempt after the third resume succeeds
        var subscription = subscribe(handled, attempts, () -> attempts.get(FAILING_EVENT).get() <= 6);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(7);
        assertThat(attempts.get(3L).get()).isEqualTo(1);
        // Every stop is reported - the alertable signal stays intact
        awaitStoppedByErrorPolicyCount(3);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        assertThat(subscription.isActive()).isTrue();
        awaitDurableResumePoint(4);
        assertThat(skippedAfterAutoResumesCount()).isZero();
    }

    @Test
    void with_max_attempts_the_event_is_skipped_once_the_subscription_has_been_resumed_at_it_that_many_times() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop()
                                                                                        .withAutoResume(SubscriptionErrorPolicy.AutoResume.skippingAfter(2, Duration.ofMillis(100), Duration.ofMillis(100))));
        var handled      = new CopyOnWriteArrayList<Long>();
        var attempts     = new ConcurrentHashMap<Long, AtomicInteger>();
        var subscription = subscribe(handled, attempts, () -> true);

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 3L));
        // The first attempt and one after each of the 2 resumes - the third failure skips the event instead of stopping
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(3);
        awaitStoppedByErrorPolicyCount(2);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(skippedAfterAutoResumesCount()).isEqualTo(1));
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        // Skipped: the resume point moves past the event, which is not redelivered
        awaitDurableResumePoint(4);
    }

    @Test
    void an_exclusive_subscription_resumes_by_itself_without_releasing_its_fenced_lock() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop()
                                                                                        .withAutoResume(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofMillis(200), Duration.ofMillis(200))));
        var handled      = new CopyOnWriteArrayList<Long>();
        var attempts     = new ConcurrentHashMap<Long, AtomicInteger>();
        var subscription = exclusivelySubscribe(handled, attempts, () -> attempts.get(FAILING_EVENT).get() <= 2);
        var lockName     = ((ExclusiveSubscription) subscription).lockName();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isActive);
        var lockToken = fencedLockManager.lookupLock(lockName).orElseThrow().getCurrentToken();

        appendThreeEvents();

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(3);
        awaitStoppedByErrorPolicyCount(2);
        awaitDurableResumePoint(4);
        // The lock was held throughout - neither released nor re-acquired
        assertThat(fencedLockManager.isLockedByThisLockManagerInstance(lockName)).isTrue();
        assertThat(fencedLockManager.lookupLock(lockName).orElseThrow().getCurrentToken()).isEqualTo(lockToken);
    }

    @Test
    void a_batched_subscription_resumes_by_itself_at_the_failed_batch() {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.retryThenStop(1, Duration.ofMillis(10), Duration.ofMillis(10))
                                                                                        .withAutoResume(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofMillis(200), Duration.ofMillis(200))));
        var batchAttempts = new AtomicInteger();
        var handled       = new CopyOnWriteArrayList<Long>();
        appendThreeEvents();

        // Attempt + retry fail, the attempt after the resume succeeds
        var subscription = batchSubscribe(batchAttempts, handled, () -> batchAttempts.get() <= 2);

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(batchAttempts.get()).isEqualTo(3);
        awaitStoppedByErrorPolicyCount(1);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        awaitDurableResumePoint(4);
    }

    /**
     * The stopped gauge is what alerts are written against, typically with a {@code for:} duration. A resumed subscription
     * retries the poison event for a moment before it stops again - the gauge must not drop to 0 in that moment, or every
     * automatic resume resets the alert's timer and it never fires
     */
    @Test
    void the_stopped_gauge_stays_at_1_through_the_auto_resumes_of_a_poison_event_and_drops_to_0_once_it_is_handled() throws InterruptedException {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop()
                                                                                        .withAutoResume(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofMillis(200), Duration.ofMillis(200))));
        var handled      = new CopyOnWriteArrayList<Long>();
        var attempts     = new ConcurrentHashMap<Long, AtomicInteger>();
        var fixed        = new AtomicBoolean();
        var subscription = subscribe(handled, attempts, () -> !fixed.get());
        new SubscriptionStoppedMicrometerMonitor(eventStoreSubscriptionManager, meterRegistry, null).monitor(subscriberId, aggregateType);
        var gauge = meterRegistry.find(SubscriptionStoppedMicrometerMonitor.SUBSCRIPTION_STOPPED_METRIC)
                                 .tag("subscriber_id", subscriberId.toString())
                                 .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isZero();

        appendThreeEvents();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> gauge.value() == 1);

        // Sample the gauge as fast as possible across several resumes at the poison event
        var samples   = new AtomicInteger();
        var dropsTo0  = new AtomicInteger();
        var sampling  = new AtomicBoolean(true);
        var sampler = Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                samples.incrementAndGet();
                if (gauge.value() == 0) {
                    dropsTo0.incrementAndGet();
                }
                Thread.onSpinWait();
            }
        });
        var stopsBefore = stoppedByErrorPolicyCount();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .until(() -> stoppedByErrorPolicyCount() >= stopsBefore + 3);
        sampling.set(false);
        sampler.join();
        assertThat(samples.get()).isPositive();
        assertThat(dropsTo0.get()).as("samples of 0 while resumed at the poison event").isZero();
        assertThat(handled).containsExactly(1L);

        // Fixed: the next resume handles the event, and the gauge drops to 0
        fixed.set(true);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> gauge.value() == 0);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        assertThat(subscription.isRecoveringFromErrorPolicyStop()).isFalse();
    }

    @Test
    void a_pending_auto_resume_is_cancelled_when_the_subscription_manager_stops() throws InterruptedException {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop()
                                                                                        .withAutoResume(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(1))));
        var handled      = new CopyOnWriteArrayList<Long>();
        var attempts     = new ConcurrentHashMap<Long, AtomicInteger>();
        var subscription = subscribe(handled, attempts, () -> true);

        appendThreeEvents();
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);

        eventStoreSubscriptionManager.stop();
        // Well past the resume delay: nothing resumed the stopped subscription
        Thread.sleep(2500);
        assertThat(attempts.get(FAILING_EVENT).get()).isEqualTo(1);
        assertThat(subscription.isStarted()).isFalse();
        assertThat(handled).containsExactly(1L);
        awaitDurableResumePoint(FAILING_EVENT);
    }

    // ------------------------------------------------------------------------------------------- Per-handler policy

    /**
     * One manager, policy {@code SKIP}: a projection whose handler asks for {@code STOP} stops at the failed event, while
     * a side-effect subscriber on the same manager and aggregate type, with no policy of its own, skips it. The batched
     * path honours a handler's policy the same way
     */
    @Test
    void a_handlers_own_policy_wins_over_the_managers_for_its_subscription_only() throws InterruptedException {
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.skip());
        var projectionId       = SubscriberId.of(subscriberId + "-Projection");
        var projectionHandled  = new CopyOnWriteArrayList<Long>();
        var projectionAttempts = new AtomicInteger();
        var projection = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(projectionId,
                                                                                                aggregateType,
                                                                                                GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                Optional.empty(),
                                                                                                new PersistedEventHandler() {
                                                                                                    @Override
                                                                                                    public void handle(PersistedEvent event) {
                                                                                                        var globalOrder = event.globalEventOrder().longValue();
                                                                                                        if (globalOrder == FAILING_EVENT) {
                                                                                                            projectionAttempts.incrementAndGet();
                                                                                                            throw new IllegalStateException("Intentional failure handling event #" + globalOrder);
                                                                                                        }
                                                                                                        projectionHandled.add(globalOrder);
                                                                                                    }

                                                                                                    @Override
                                                                                                    public Optional<SubscriptionErrorPolicy> subscriptionErrorPolicy() {
                                                                                                        return Optional.of(SubscriptionErrorPolicy.stop().withoutAutoResume());
                                                                                                    }
                                                                                                });
        var sideEffectHandled  = new CopyOnWriteArrayList<Long>();
        var sideEffectAttempts = new ConcurrentHashMap<Long, AtomicInteger>();
        var sideEffect = subscribe(sideEffectHandled, sideEffectAttempts, () -> true);
        var batchedProjectionId = SubscriberId.of(subscriberId + "-BatchedProjection");
        var batchedHandled      = new CopyOnWriteArrayList<Long>();
        var batched = eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(batchedProjectionId,
                                                                                                  aggregateType,
                                                                                                  GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                  Optional.empty(),
                                                                                                  10,
                                                                                                  Duration.ofMillis(200),
                                                                                                  new BatchedPersistedEventHandler() {
                                                                                                      @Override
                                                                                                      public int handleBatch(List<PersistedEvent> events) {
                                                                                                          var globalOrders = events.stream().map(e -> e.globalEventOrder().longValue()).toList();
                                                                                                          if (globalOrders.contains(FAILING_EVENT)) {
                                                                                                              throw new IllegalStateException("Intentional failure handling batch " + globalOrders);
                                                                                                          }
                                                                                                          batchedHandled.addAll(globalOrders);
                                                                                                          return events.size();
                                                                                                      }

                                                                                                      @Override
                                                                                                      public Optional<SubscriptionErrorPolicy> subscriptionErrorPolicy() {
                                                                                                          return Optional.of(SubscriptionErrorPolicy.stop().withoutAutoResume());
                                                                                                      }
                                                                                                  });

        appendThreeEvents();

        // The side-effect subscriber gets the manager's SKIP
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(sideEffectHandled).containsExactly(1L, 3L));
        assertThat(sideEffect.isStoppedByErrorPolicy()).isFalse();
        awaitDurableResumePoint(subscriberId, 4);
        // The projections get their own STOP
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(projection::isStoppedByErrorPolicy);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(batched::isStoppedByErrorPolicy);
        Thread.sleep(1000);
        assertThat(projectionHandled).containsExactly(1L);
        assertThat(projectionAttempts.get()).isEqualTo(1);
        assertThat(batchedHandled).isEmpty();
        awaitDurableResumePoint(projectionId, FAILING_EVENT);
        awaitDurableResumePoint(batchedProjectionId, 1);
    }

    // ------------------------------------------------------------------------------------------------------- Batched

    @Test
    void batched_stop_is_resumed_at_the_failed_batch() throws InterruptedException {
        var failing = new AtomicBoolean(true);
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.retryThenStop(1, Duration.ofMillis(20), Duration.ofMillis(50)).withoutAutoResume());
        var batchAttempts = new AtomicInteger();
        var handled       = new CopyOnWriteArrayList<Long>();
        appendThreeEvents();

        var subscription = batchSubscribe(batchAttempts, handled, failing::get);

        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
        // 1 attempt + 1 retry, then stopped
        assertThat(batchAttempts.get()).isEqualTo(2);
        Thread.sleep(500);
        assertThat(handled).isEmpty();
        awaitDurableResumePoint(1);

        failing.set(false);
        assertThat(subscription.resumeIfStoppedByErrorPolicy()).isTrue();
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(batchAttempts.get()).isEqualTo(3);
        assertThat(subscription.isStoppedByErrorPolicy()).isFalse();
        awaitDurableResumePoint(4);
    }

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
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop().withoutAutoResume());
        var batchAttempts = new AtomicInteger();
        var handled       = new CopyOnWriteArrayList<Long>();
        appendThreeEvents();

        var subscription = batchSubscribe(batchAttempts, handled, failing::get);

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(batchAttempts.get()).isEqualTo(1));
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(subscription::isStoppedByErrorPolicy);
        assertThat(subscription.isActive()).isTrue();
        awaitStoppedByErrorPolicyCount(1);
        Thread.sleep(1500);
        assertThat(batchAttempts.get()).isEqualTo(1);
        assertThat(handled).isEmpty();
        assertThat(eventStoreSubscriptionManager.getCurrentEventOrder(subscriberId, aggregateType)).hasValue(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER);
        awaitDurableResumePoint(1);

        failing.set(false);
        eventStoreSubscriptionManager.stop();
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.stop().withoutAutoResume());
        batchSubscribe(batchAttempts, handled, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        awaitDurableResumePoint(4);
    }

    @Test
    void batched_stopping_the_subscription_during_a_retry_backoff_does_not_skip_the_batch() {
        var failing = new AtomicBoolean(true);
        // A retry budget of about 9 seconds, far more than the test takes to stop the manager in the middle of it
        var policy = SubscriptionErrorPolicy.retryThenSkip(5, Duration.ofSeconds(1), Duration.ofSeconds(2));
        eventStoreSubscriptionManager = startSubscriptionManager(policy);
        var batchAttempts = new AtomicInteger();
        var handled       = new CopyOnWriteArrayList<Long>();
        appendThreeEvents();

        batchSubscribe(batchAttempts, handled, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(batchAttempts.get()).isGreaterThanOrEqualTo(1));

        // Stops (disposes) the subscription while the batch sleeps in its backoff - disposing the subscriber's
        // batchHandlerScheduler interrupts the backoff
        eventStoreSubscriptionManager.stop();
        eventStoreSubscriptionManager = null;

        // Abandoned, not given up on: not skipped, not reported as failed, and the durable resume point stays at the batch's first event
        assertThat(batchAttempts.get()).isLessThan(1 + policy.maxRetries());
        assertThat(handled).isEmpty();
        assertThat(handleEventFailedCount()).isZero();
        awaitDurableResumePoint(1);

        // A restarted manager handles the batch again, and then continues
        var attemptsBeforeRestart = batchAttempts.get();
        failing.set(false);
        eventStoreSubscriptionManager = startSubscriptionManager(policy);
        batchSubscribe(batchAttempts, handled, failing::get);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(handled).containsExactly(1L, 2L, 3L));
        assertThat(batchAttempts.get()).isEqualTo(attemptsBeforeRestart + 1);
        assertThat(handleEventFailedCount()).isZero();
        awaitDurableResumePoint(4);
    }

    /**
     * Each batched subscriber handles its batches - and sleeps out its {@link SubscriptionErrorPolicy.Mode#RETRY_N_THEN_SKIP}
     * backoffs - on a thread of its own. Were that thread shared (e.g. {@code Schedulers.single()}), the second
     * subscription's batch would queue behind the first one's backoff.
     */
    @Test
    void batched_subscription_in_its_retry_backoff_does_not_stall_another_batched_subscription() {
        var backoff = Duration.ofSeconds(10);
        eventStoreSubscriptionManager = startSubscriptionManager(SubscriptionErrorPolicy.retryThenSkip(1, backoff, backoff));
        var failingBatchAttempts = new AtomicInteger();
        var failingHandled       = new CopyOnWriteArrayList<Long>();
        appendThreeEvents();

        batchSubscribe(subscriberId, failingBatchAttempts, failingHandled, () -> true);
        // The first attempt has failed, so the batch is now sleeping out its 10 second backoff
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(failingBatchAttempts.get()).isEqualTo(1));

        // Subscribed only now, so its batch is handled while the first subscription's batch is in its backoff
        var otherSubscriberId  = SubscriberId.of(subscriberId + "-Other");
        var otherBatchAttempts = new AtomicInteger();
        var otherHandled       = new CopyOnWriteArrayList<Long>();
        batchSubscribe(otherSubscriberId, otherBatchAttempts, otherHandled, () -> false);

        Awaitility.waitAtMost(backoff.dividedBy(2))
                  .untilAsserted(() -> assertThat(otherHandled).containsExactly(1L, 2L, 3L));
        // Still in the backoff: the other subscription was not waiting for it to end
        assertThat(failingBatchAttempts.get()).isEqualTo(1);
        assertThat(failingHandled).isEmpty();
        assertThat(otherBatchAttempts.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------------------- Helpers

    private EventStoreSubscriptionManager startSubscriptionManager(SubscriptionErrorPolicy subscriptionErrorPolicy) {
        fencedLockManager = PostgresqlFencedLockManager.builder()
                                                       .setJdbi(jdbi)
                                                       .setUnitOfWorkFactory(unitOfWorkFactory)
                                                       .setLockManagerInstanceId("Node1")
                                                       .setLockTimeOut(Duration.ofSeconds(3))
                                                       .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                       .build();
        var builder = EventStoreSubscriptionManager.builder()
                                                   .setEventStore(eventStore)
                                                   .setEventStorePollingBatchSize(10)
                                                   .setEventStorePollingInterval(Duration.ofMillis(50))
                                                   .setFencedLockManager(fencedLockManager)
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
    private EventStoreSubscription subscribe(List<Long> handled, Map<Long, AtomicInteger> attempts, java.util.function.BooleanSupplier failEvent) {
        return eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(subscriberId,
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

    /**
     * As {@link #subscribe(List, Map, java.util.function.BooleanSupplier)}, but exclusively - governed by a fenced lock
     */
    private EventStoreSubscription exclusivelySubscribe(List<Long> handled, Map<Long, AtomicInteger> attempts, java.util.function.BooleanSupplier failEvent) {
        return eventStoreSubscriptionManager.exclusivelySubscribeToAggregateEventsAsynchronously(subscriberId,
                                                                                          aggregateType,
                                                                                          GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                          Optional.empty(),
                                                                                          new FencedLockAwareSubscriber() {
                                                                                              @Override
                                                                                              public void onLockAcquired(FencedLock fencedLock, SubscriptionResumePoint resumeFromAndIncluding) {
                                                                                              }

                                                                                              @Override
                                                                                              public void onLockReleased(FencedLock fencedLock) {
                                                                                              }
                                                                                          },
                                                                                          (PersistedEventHandler) event -> {
                                                                                              var globalOrder = event.globalEventOrder().longValue();
                                                                                              attempts.computeIfAbsent(globalOrder, order -> new AtomicInteger()).incrementAndGet();
                                                                                              if (globalOrder == FAILING_EVENT && failEvent.getAsBoolean()) {
                                                                                                  throw new IllegalStateException("Intentional failure handling event #" + globalOrder);
                                                                                              }
                                                                                              handled.add(globalOrder);
                                                                                          });
    }

    private EventStoreSubscription batchSubscribe(AtomicInteger batchAttempts, List<Long> handled, java.util.function.BooleanSupplier failBatchContainingFailingEvent) {
        return batchSubscribe(subscriberId, batchAttempts, handled, failBatchContainingFailingEvent);
    }

    /**
     * @param batchSubscriberId               the subscriber to subscribe as
     * @param batchAttempts                   handling attempts of a batch containing #2
     * @param handled                         global orders of the batches handled successfully, in handling order
     * @param failBatchContainingFailingEvent whether an attempt at a batch containing #2 fails, asked after the attempt has been counted
     */
    private EventStoreSubscription batchSubscribe(SubscriberId batchSubscriberId, AtomicInteger batchAttempts, List<Long> handled,
                                                  java.util.function.BooleanSupplier failBatchContainingFailingEvent) {
        return eventStoreSubscriptionManager.batchSubscribeToAggregateEventsAsynchronously(batchSubscriberId,
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

    private double stoppedByErrorPolicyCount() {
        var counter = meterRegistry.find(MeasurementEventStoreSubscriptionObserver.SUBSCRIPTION_STOPPED_BY_ERROR_POLICY_METRIC)
                                   .tag("subscriber_id", subscriberId.toString())
                                   .counter();
        return counter == null ? 0 : counter.count();
    }

    private void awaitStoppedByErrorPolicyCount(double expected) {
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(stoppedByErrorPolicyCount()).isEqualTo(expected));
    }

    private double skippedAfterAutoResumesCount() {
        var counter = meterRegistry.find(MeasurementEventStoreSubscriptionObserver.SUBSCRIPTION_SKIPPED_EVENT_AFTER_AUTO_RESUMES_METRIC)
                                   .tag("subscriber_id", subscriberId.toString())
                                   .counter();
        return counter == null ? 0 : counter.count();
    }

    private double handleEventFailedCount() {
        var counter = meterRegistry.find(MeasurementEventStoreSubscriptionObserver.HANDLE_EVENT_FAILED_METRIC)
                                   .tag("subscriber_id", subscriberId.toString())
                                   .counter();
        return counter == null ? 0 : counter.count();
    }

    private void awaitDurableResumePoint(long expectedResumeFromAndIncluding) {
        awaitDurableResumePoint(subscriberId, expectedResumeFromAndIncluding);
    }

    private void awaitDurableResumePoint(SubscriberId ofSubscriber, long expectedResumeFromAndIncluding) {
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableSubscriptionRepository.getResumePoint(ofSubscriber, aggregateType))
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
