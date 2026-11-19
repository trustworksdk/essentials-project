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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnerSettings;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueues;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link EventLogPartitionedViewProcessor} on the shard-owned queue engine sharing the page's JDBI unit of work factory:
 * every event queued exactly once even when a page fails after its enqueue and is redelivered (must-fail arm: enqueue
 * outside the page transaction); the queued messages' key, metadata and cause; a rebuild that clears the view, purges the
 * queue (dead letters included) and replays (must-fail arm: the rebuild leaves the queued messages).
 */
class EventLogPartitionedViewProcessorIT extends AbstractProcessorIT {
    private static final long X    = 37;
    private static final String NAME = "partitioned";

    private RegisteredMessageQueues queues;
    private ShardOwnedDurableQueues durableQueues;

    @BeforeEach
    void setUpQueues() throws SQLException {
        ShardOwnedSchema.recreate(pool);
        ShardOwnedSchema.registerQueue(pool, dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(NAME + ":queue"), 2);
        queues = new RegisteredMessageQueues(pool, "it-instance");
        durableQueues = ShardOwnedDurableQueues.builder()
                                               .setQueues(queues)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setDataSource(pool)
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void stopQueues() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (queues != null) {
            queues.close();
        }
    }

    /** Counts each handled amount (= global order of an {@link #appendEvents(long)} event) in the table {@code applied} */
    class View extends EventLogPartitionedViewProcessor {
        final Map<Integer, String>          keys     = new ConcurrentHashMap<>();
        final Map<Integer, String>          causedBy = new ConcurrentHashMap<>();
        final Map<Integer, MessageMetaData> metaData = new ConcurrentHashMap<>();
        volatile boolean                    failing;
        final AtomicBoolean                 rebuilt  = new AtomicBoolean();

        View(DurableQueues queues, Set<ProcessorMutant> mutants) {
            this(queues, source(SourceKind.JDBI, Set.of()), mutants);
        }

        View(DurableQueues queues, PageTransactionSource pages, Set<ProcessorMutant> mutants) {
            super(NAME, List.of(ORDERS), new EventLogProcessorDependencies(eventLog, serializer, pages), queues, unitOfWorkFactory);
            seams(AbstractProcessorIT.seams(mutants, Set.of()));
        }

        @Override
        protected int forwardPageSize() {
            return 50;
        }

        @Override
        protected Optional<ErrorSettings> errorSettings() {
            return Optional.of(errors());
        }

        /** No redelivery: a failing message is dead-lettered at once */
        @Override
        protected RedeliveryPolicy durableQueueRedeliveryPolicy() {
            return RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 0);
        }

        @Override
        protected void onRebuild(ProjectionContext context) {
            context.handle().execute("DELETE FROM applied");
            rebuilt.set(true);
        }

        @MessageHandler
        void on(Added added, OrderedMessage message) {
            if (failing) {
                throw new IllegalStateException("failing " + added.amount());
            }
            ((HandleAwareUnitOfWork) messageUnitOfWork()).handle()
                                                          .execute("INSERT INTO applied VALUES (?, 1) ON CONFLICT (global_order) DO UPDATE SET n = applied.n + 1", (long) added.amount());
            keys.put(added.amount(), message.getKey());
            CausationContext.current().ifPresent(cause -> causedBy.put(added.amount(), cause.toString()));
            metaData.put(added.amount(), message.getMetaData());
        }

        long queued() {
            return durableQueues.getTotalMessagesQueuedFor(queueName());
        }

        long deadLetters() {
            return durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName());
        }
    }

    /** Queues noting the enqueue of a batch holding amount {@link #X} */
    private DurableQueues notingEnqueueOfX(AtomicBoolean enqueuedX) {
        return proxy(DurableQueues.class, (proxy, method, args) -> {
            var result = invoke(method, durableQueues, args);
            if (method.getName().equals("queueMessages") && args[1] instanceof List<?> messages
                    && messages.stream().anyMatch(m -> m instanceof Message message && message.getPayload() instanceof Added added && added.amount() == X)) {
                enqueuedX.set(true);
            }
            return result;
        });
    }

    /**
     * Page transactions whose commit fails once, rolled back, for the first page that enqueued amount {@link #X}: the page
     * fails after its enqueue and is redelivered. Before failing it gives messages that escaped the page transaction time to
     * be handled (they are, under the mutant; a redelivered page's re-enqueue of a message still queued would be absorbed)
     */
    private PageTransactionSource failingOnceAfterEnqueueOfX(AtomicBoolean enqueuedX, AtomicBoolean failed) {
        var real = source(SourceKind.JDBI, Set.of());
        return () -> {
            var page = real.begin();
            return new PageTransactionSource.OpenPage() {
                @Override
                public java.sql.Connection connection() {
                    return page.connection();
                }

                @Override
                public <T> T resource(Class<T> type) {
                    return page.resource(type);
                }

                @Override
                public void commit() throws SQLException {
                    if (enqueuedX.getAndSet(false) && failed.compareAndSet(false, true)) {
                        try {
                            awaitTrue("an escaped message handled", Duration.ofMillis(1500), () -> applied().containsKey(X));
                        } catch (AssertionError nothingEscaped) {
                            // the enqueue is the page's own
                        }
                        page.rollback();
                        throw new SQLException("fails once after the enqueue of " + X);
                    }
                    page.commit();
                }

                @Override
                public void rollback() {
                    page.rollback();
                }
            };
        };
    }

    @Test
    void every_event_is_queued_exactly_once_when_a_page_fails_after_its_enqueue() throws Exception {
        exactlyOnceArm(Set.of());
    }

    @Test
    void must_fail_PARTITIONED_ENQUEUE_OUTSIDE_PAGE_TRANSACTION() {
        assertThatThrownBy(() -> exactlyOnceArm(Set.of(ProcessorMutant.PARTITIONED_ENQUEUE_OUTSIDE_PAGE_TRANSACTION))).isInstanceOf(AssertionError.class);
    }

    private void exactlyOnceArm(Set<ProcessorMutant> mutants) throws Exception {
        appendEvents(-1);
        var enqueuedX = new AtomicBoolean();
        var failed    = new AtomicBoolean();
        var view      = new View(notingEnqueueOfX(enqueuedX), failingOnceAfterEnqueueOfX(enqueuedX, failed), mutants);
        view.start();
        try {
            var key = view.projections().getFirst().key();
            awaitCaughtUp(key);
            awaitTrue("every event handled and the queue drained", Duration.ofSeconds(30), () -> applied().size() == EVENTS && view.queued() == 0);
            assertThat(failed).as("the page failed after its enqueue").isTrue();
            assertThat(applied()).allSatisfy((n, times) -> assertThat(times).as("handled once: " + n).isEqualTo(1));
            assertThat(parked(key)).isEmpty();
            assertThat(view.keys.get(1)).as("the handlers see the plain aggregate id").isEqualTo(orderOf(1));
            assertThat(view.metaData.get(1)).containsEntry(EventLogPartitionedViewProcessor.AGGREGATE_TYPE_METADATA_KEY, ORDERS)
                                            .containsKey(EventLogPartitionedViewProcessor.EVENT_ID_METADATA_KEY);
            assertThat(view.causedBy.get(1)).isEqualTo(view.metaData.get(1).get(EventLogPartitionedViewProcessor.EVENT_ID_METADATA_KEY));
        } finally {
            view.stop();
        }
    }

    @Test
    void a_rebuild_clears_the_view_purges_the_queue_and_replays() throws Exception {
        rebuildArm(Set.of());
    }

    @Test
    void must_fail_PARTITIONED_REBUILD_LEAVES_QUEUED_MESSAGES() {
        assertThatThrownBy(() -> rebuildArm(Set.of(ProcessorMutant.PARTITIONED_REBUILD_LEAVES_QUEUED_MESSAGES))).isInstanceOf(AssertionError.class);
    }

    private void rebuildArm(Set<ProcessorMutant> mutants) throws Exception {
        appendEvents(-1);
        var view = new View(durableQueues, mutants);
        view.failing = true;
        view.start();
        try {
            var key = view.projections().getFirst().key();
            awaitCaughtUp(key);
            awaitTrue("every message dead-lettered", Duration.ofSeconds(30), () -> view.deadLetters() == EVENTS);
            execute("INSERT INTO applied VALUES (999, 1)");

            assertThat(view.rebuild()).isTrue();
            assertThat(view.rebuilt).isTrue();
            assertThat(view.isStarted()).isTrue();
            assertThat(view.deadLetters()).as("the rebuild purged the dead letters").isZero();

            view.failing = false;
            awaitTrue("the replay handled every event", Duration.ofSeconds(30), () -> applied().size() == EVENTS && view.queued() == 0);
            assertThat(applied()).doesNotContainKey(999L)
                                 .allSatisfy((n, times) -> assertThat(times).as("handled once since the rebuild: " + n).isEqualTo(1));
        } finally {
            view.stop();
        }
    }

    @Test
    void start_refuses_queues_that_keep_key_order_per_instance_only() {
        var perInstance = proxy(DurableQueues.class, (proxy, method, args) -> method.getName().equals("keepsKeyOrderAcrossInstances") ? false : invoke(method, durableQueues, args));
        var view        = new View(perInstance, Set.of());
        assertThatThrownBy(view::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("key in order across instances");
        assertThat(view.isStarted()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Builds each engine queue on demand with a one-second lease, as the adapter's own ITs do */
    private static final class RegisteredMessageQueues implements dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues, AutoCloseable {
        private final javax.sql.DataSource dataSource;
        private final String               instanceId;
        private final Map<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName, PostgresqlMessageQueue> built = new LinkedHashMap<>();

        RegisteredMessageQueues(javax.sql.DataSource dataSource, String instanceId) {
            this.dataSource = dataSource;
            this.instanceId = instanceId;
        }

        @Override
        public synchronized List<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName> queueNames() throws SQLException {
            return ShardOwnedSchema.queueNames(dataSource);
        }

        @Override
        public synchronized Optional<dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue> findQueue(
                dk.trustworks.essentials.components.queue.shardowned.spi.QueueName queueName) throws SQLException {
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
