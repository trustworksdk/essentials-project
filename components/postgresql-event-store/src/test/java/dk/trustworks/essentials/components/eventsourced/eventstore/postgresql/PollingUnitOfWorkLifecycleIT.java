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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import com.zaxxer.hikari.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardSingleTenantConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Regression tests for the {@link UnitOfWork} a polling event stream opens for each poll.
 * <p>
 * An idle, caught-up subscriber checks on every 100th empty poll whether anything new was persisted
 * ({@code SELECT MAX(global_order)}), and when nothing was, it skips the poll. That skip used to return without
 * committing or rolling back the unit of work it had opened. The next poll on the same thread picked the unit of work
 * up again and committed it, so in steady state nothing was lost; but a subscription disposed in between - an
 * unsubscribe, a stop, or an exclusive subscriber losing its fenced lock - left the transaction open for the life of
 * the process: a pooled connection {@code idle in transaction}, holding a lock on the event table that blocks
 * {@code DROP}, {@code TRUNCATE}, {@code ALTER TABLE} and {@code VACUUM FULL}. Each test disposes the subscription from
 * inside that exact branch, so they fail deterministically on the leak rather than depending on timing.
 */
@Testcontainers
class PollingUnitOfWorkLifecycleIT {
    private static final AggregateType ORDERS         = AggregateType.of("Orders");
    private static final String        ORDERS_TABLE   = "orders_events";
    private static final Duration      FAST_POLLING   = Duration.ofMillis(1);
    private static final Duration      EXPECTED_WITHIN = Duration.ofSeconds(10);

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("event-store")
            .withUsername("test-user")
            .withPassword("secret-password");

    private HikariDataSource                                                        dataSource;
    private Jdbi                                                                    jdbi;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>                       unitOfWorkFactory;
    private PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private DisposeWhenPollIsSkipped                                                observer;

    @BeforeEach
    void setup() {
        // A pooled DataSource, as in production: the pool keeps every connection reachable, so a leaked one stays open.
        // With unpooled connections a leak can go unnoticed - once the polling thread ends, the connection becomes
        // unreachable and the driver closes it when it is garbage collected.
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        hikariConfig.setUsername(postgreSQLContainer.getUsername());
        hikariConfig.setPassword(postgreSQLContainer.getPassword());
        dataSource = new HikariDataSource(hikariConfig);
        jdbi = Jdbi.create(dataSource);
        jdbi.installPlugin(new PostgresPlugin());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       (aggregateId, configuration, event, eventOrder) -> {
                                                                                           throw new UnsupportedOperationException("These tests never persist events");
                                                                                       },
                                                                                       standardSingleTenantConfiguration(aggregateType -> aggregateType + "_events",
                                                                                                                         EventStreamTableColumnNames.defaultColumnNames(),
                                                                                                                         EssentialsJSONEventSerializers.create(),
                                                                                                                         IdentifierColumnType.UUID,
                                                                                                                         JSONColumnType.JSONB));
        observer = new DisposeWhenPollIsSkipped();
        eventStore = new PostgresqlEventStore<>(unitOfWorkFactory,
                                                persistenceStrategy,
                                                Optional.empty(),
                                                eventStore -> new PostgresqlEventStreamGapHandler<>(eventStore, unitOfWorkFactory),
                                                observer);
        eventStore.addAggregateEventStreamConfiguration(ORDERS, AggregateIdSerializer.serializerFor(OrderId.class));

        // A missing table would make the MAX query fail and take the rollback path, so the skip branch these tests
        // are about would never run and they would pass without proving anything.
        Boolean eventTableExists = jdbi.withHandle(handle -> handle.createQuery("SELECT to_regclass(:table) IS NOT NULL")
                                                                   .bind("table", ORDERS_TABLE)
                                                                   .mapTo(Boolean.class)
                                                                   .one());
        assertThat(eventTableExists).as("event table %s exists", ORDERS_TABLE).isTrue();
    }

    @AfterEach
    void cleanup() {
        observer.disposeSubscription();
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void pollEvents_disposed_right_after_a_skipped_poll_leaves_no_transaction_open() throws InterruptedException {
        observer.subscribe(() -> eventStore.pollEvents(ORDERS,
                                                       GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                       Optional.of(10),
                                                       Optional.of(FAST_POLLING),
                                                       Optional.empty(),
                                                       Optional.of(SubscriberId.of("poll-events")),
                                                       Optional.empty()));

        assertNoTransactionIsLeftOpenAfterTheSkippedPoll();
    }

    @Test
    void unboundedPollForEvents_disposed_right_after_a_skipped_poll_leaves_no_transaction_open() throws InterruptedException {
        observer.subscribe(() -> eventStore.unboundedPollForEvents(ORDERS,
                                                                   GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                   Optional.of(10),
                                                                   Optional.of(FAST_POLLING),
                                                                   Optional.empty(),
                                                                   Optional.of(SubscriberId.of("unbounded-poll"))));

        assertNoTransactionIsLeftOpenAfterTheSkippedPoll();
    }

    /**
     * The first poll of {@link EventStore#unboundedPollForEvents} runs on the subscribing thread. When that thread is
     * already inside a unit of work, the poll joins it - and used to commit it, ending the caller's transaction behind
     * its back. A poll may only end a unit of work it started itself.
     */
    @Test
    void unboundedPollForEvents_does_not_end_a_unit_of_work_it_joined() {
        var callersUnitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();

        observer.subscribe(() -> eventStore.unboundedPollForEvents(ORDERS,
                                                                   GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                   Optional.of(10),
                                                                   Optional.of(Duration.ofHours(1)),
                                                                   Optional.empty(),
                                                                   Optional.of(SubscriberId.of("joined-poll"))));

        assertThat(callersUnitOfWork.status()).isEqualTo(UnitOfWorkStatus.Started);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).containsSame(callersUnitOfWork);
    }

    private void assertNoTransactionIsLeftOpenAfterTheSkippedPoll() throws InterruptedException {
        assertThat(observer.skippedPollDisposedTheSubscription.await(EXPECTED_WITHIN.toSeconds(), TimeUnit.SECONDS))
                .as("the poll reached the 'no new events persisted' branch")
                .isTrue();

        await().atMost(EXPECTED_WITHIN)
               .untilAsserted(() -> assertThat(sessionsIdleInTransaction())
                       .as("sessions left 'idle in transaction' after the subscription was disposed")
                       .isZero());

        // The practical consequence of a leaked transaction: DDL on the event table blocks behind its lock.
        jdbi.useHandle(handle -> {
            handle.execute("SET lock_timeout = '2s'");
            handle.execute("TRUNCATE " + ORDERS_TABLE);
        });
    }

    private long sessionsIdleInTransaction() {
        return jdbi.withHandle(handle -> handle.createQuery("""
                                                             SELECT count(*) FROM pg_stat_activity
                                                             WHERE datname = current_database()
                                                               AND state = 'idle in transaction'
                                                               AND pid <> pg_backend_pid()""")
                                               .mapTo(Long.class)
                                               .one());
    }

    /**
     * Disposes the subscription from inside the poll, at the moment the poll has resolved a batch size of 0 - the
     * branch that skips the poll. That is the window in which the unit of work used to be left open.
     */
    private static final class DisposeWhenPollIsSkipped extends EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver {
        private final AtomicReference<Disposable> subscription                        = new AtomicReference<>();
        private final CountDownLatch              skippedPollDisposedTheSubscription = new CountDownLatch(1);

        void subscribe(Supplier<Flux<?>> events) {
            subscription.set(events.get().subscribe());
        }

        void disposeSubscription() {
            var current = subscription.get();
            if (current != null) {
                current.dispose();
            }
        }

        @Override
        public void resolvedBatchSizeForEventStorePoll(SubscriberId subscriberId, AggregateType aggregateType, long defaultBatchFetchSize,
                                                       long remainingDemandForEvents, long lastBatchSizeForEventStorePoll,
                                                       int consecutiveNoPersistedEventsReturned, long nextFromInclusiveGlobalOrder,
                                                       long batchSizeForThisEventStorePoll, Duration resolveBatchSizeDuration) {
            var current = subscription.get();
            if (batchSizeForThisEventStorePoll == 0 && current != null && skippedPollDisposedTheSubscription.getCount() > 0) {
                current.dispose();
                skippedPollDisposedTheSubscription.countDown();
            }
        }
    }
}
