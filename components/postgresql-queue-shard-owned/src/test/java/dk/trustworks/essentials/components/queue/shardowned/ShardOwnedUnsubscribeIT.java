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

package dk.trustworks.essentials.components.queue.shardowned;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static ch.qos.logback.classic.Level.*;
import static org.assertj.core.api.Assertions.*;

/**
 * {@link Subscription#unsubscribe()} takes a subscription out of its {@link PostgresqlMessageQueue}, which
 * {@link Subscription#stop()} does not: a stopped subscription stays registered, and the queue's {@code start()} starts it
 * again. A caller that let go of its subscription and consumed anew - the {@code DurableQueues} adapter's consumer on
 * every cancel or stop and start - used to leave one stopped pair of lanes registered per round: the next
 * {@code consume()} warned of a COMPETING consumer under the next instance id suffix, and a {@code start()} of the queue
 * after its {@code stop()} started every abandoned pair again, delivering to handlers nobody held.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedUnsubscribeIT {

    private static final short  QUEUE_ID    = 1;
    private static final int    SHARD_COUNT = 4;
    private static final int    MESSAGES    = 20;
    private static final String INSTANCE_ID = "unsubscribing";

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource                dataSource;
    private ListAppender<ILoggingEvent>     logged;
    private ch.qos.logback.classic.Logger   queueLogger;
    private ListAppender<ILoggingEvent>     laneLogged;
    private ch.qos.logback.classic.Logger   laneLogger;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);

        logged = new ListAppender<>();
        logged.start();
        queueLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlMessageQueue.class);
        queueLogger.addAppender(logged);

        laneLogged = new ListAppender<>();
        laneLogged.start();
        laneLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedQueue.class);
        laneLogger.addAppender(laneLogged);
    }

    @AfterEach
    void tearDown() {
        if (queueLogger != null) {
            queueLogger.detachAppender(logged);
        }
        if (laneLogger != null) {
            laneLogger.detachAppender(laneLogged);
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Unsubscribed and consumed anew, twice: no COMPETING warning, one subscription registered under the caller's own
     * instance id, and a {@code stop()} and {@code start()} of the queue deliver to the live handler alone.
     */
    @Test
    void a_subscription_unsubscribed_and_consumed_anew_leaves_one_registered_and_a_queue_restart_starts_only_that_one() throws Exception {
        var abandonedHandlers = ConcurrentHashMap.<String>newKeySet();
        var delivered         = ConcurrentHashMap.<String>newKeySet();
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE)) {
            var abandoned = new ArrayList<Subscription>();
            for (var round = 0; round < 2; round++) {
                var subscription = queue.consume((messageId, key, payload, payloadType) -> abandonedHandlers.add(body(payload)),
                                                 ConsumerOptions.defaults());
                awaitBothLanesHoldUnits(subscription);
                subscription.unsubscribe();
                assertThat(subscription.isStarted()).as("the unsubscribed subscription's lanes are stopped").isFalse();
                assertThat(queue.registeredConsumers()).as("the unsubscribed subscription's lanes left the queue").isEmpty();
                abandoned.add(subscription);
            }
            var live = queue.consume((messageId, key, payload, payloadType) -> delivered.add(body(payload)), ConsumerOptions.defaults());

            assertThat(competingWarnings()).as("no subscription is left for the new one to compete with").isEmpty();
            assertThat(queue.registeredConsumers()).as("the live subscription's two lanes, and no others").hasSize(2);
            awaitBothLanesHoldUnits(live);
            assertThat(registeredInstances()).as("the live subscription took the caller's instance id again, not a new suffix")
                                             .containsExactly(INSTANCE_ID);

            queue.stop();
            queue.start();

            assertThat(abandoned).as("the queue's start() started no unsubscribed subscription").noneMatch(Subscription::isStarted);
            assertThat(live.isStarted()).as("the queue's start() started the live subscription").isTrue();
            awaitBothLanesHoldUnits(live);
            assertThat(registeredInstances()).as("one instance, the live subscription's").containsExactly(INSTANCE_ID);

            var batch = new ArrayList<Message>();
            for (var index = 0; index < MESSAGES; index++) {
                batch.add(Message.of(("m-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            queue.enqueue(batch);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(delivered).hasSize(MESSAGES));
            assertThat(abandonedHandlers).as("no message reached a handler whose subscription was unsubscribed").isEmpty();

            assertThatThrownBy(() -> abandoned.getFirst().start()).as("an unsubscribed subscription cannot be started again")
                                                               .isInstanceOf(IllegalStateException.class)
                                                               .hasMessageContaining("unsubscribed");
            assertThatCode(() -> abandoned.getFirst().unsubscribe()).as("unsubscribing again does nothing").doesNotThrowAnyException();
            assertThat(queue.registeredConsumers()).hasSize(2);
        }
    }

    /**
     * An unsubscribe that does not get the queue's lifecycle lock - a {@code start()} of the queue holds it past the 2 s
     * budget - returns within that budget and leaves taking the lanes out to that start. That start goes over the
     * registered consumers, the unsubscribed subscription's lanes still among them, and starts neither of those lanes: it
     * used to start them, so after {@code unsubscribe()} had returned they registered the instance again and leased every
     * unit, delivering to a handler the caller had let go of until the start let go of the lock. Once the start returns,
     * the unsubscribed subscription is neither registered nor started, the other one is, and the instance id the
     * unsubscribed one registered under is free for the next {@code consume()}. The queue's {@code LifecycleHook} holds the
     * start once it has taken the lock.
     */
    @Test
    void an_unsubscribe_that_does_not_get_the_lock_leaves_taking_the_lanes_out_to_the_holder() throws Exception {
        var holdTheStart    = new AtomicBoolean();
        var startHeld       = new CountDownLatch(1);
        var releaseTheStart = new CountDownLatch(1);
        try (var queue = newQueue(new PostgresqlMessageQueue.LifecycleHook() {
            @Override
            public void afterLockTaken(String operation) {
                if ("start".equals(operation) && holdTheStart.compareAndSet(true, false)) {
                    startHeld.countDown();
                    awaitQuietly(releaseTheStart);
                }
            }
        })) {
            var unsubscribed = consumeUnchecked(queue);
            var kept         = consumeUnchecked(queue);
            awaitBothLanesHoldUnits(unsubscribed);
            // A competing subscription holds units only once a rebalance has moved some to it; started is all this needs.
            assertThat(kept.isStarted()).as("premise: the second subscription started").isTrue();
            queue.stop();
            // Registered in consume() order, two lanes per subscription sharing one metrics: the first two are the
            // unsubscribed subscription's.
            var unsubscribedMetrics = queue.registeredConsumers().getFirst().metrics();
            assertThat(queue.registeredConsumers().get(1).metrics()).as("premise: the first two lanes are one subscription's")
                                                                   .isSameAs(unsubscribedMetrics);

            holdTheStart.set(true);
            long acquiredWhenUnsubscribed;
            int  startsWhenUnsubscribed;
            var  starting = CompletableFuture.runAsync(queue::start);
            try {
                assertThat(startHeld.await(10, TimeUnit.SECONDS)).as("premise: the queue's start() holds the lock").isTrue();

                var calledAt = System.nanoTime();
                unsubscribed.unsubscribe();
                assertThat(Duration.ofNanos(System.nanoTime() - calledAt))
                        .as("the unsubscribe waits for the lock no longer than the 2 s budget, and a margin")
                        .isLessThan(ShardOwnedQueue.SHUTDOWN_BUDGET.plusMillis(1_500));
                assertThat(warnings()).as("the unsubscribe says it left taking the lanes out to the holder")
                                      .anyMatch(warning -> warning.contains("to unsubscribe one of its subscriptions"));
                assertThat(queue.registeredConsumers()).as("premise: the holder has not taken the lanes out yet").hasSize(4);
                acquiredWhenUnsubscribed = unsubscribedMetrics.shardsAcquired.sum();
                startsWhenUnsubscribed = laneStartsOf(INSTANCE_ID).size();
            } finally {
                releaseTheStart.countDown();
            }
            starting.get(30, TimeUnit.SECONDS);

            assertThat(laneStartsOf(INSTANCE_ID)).as("the holder started neither lane of the unsubscribed subscription")
                                                 .hasSize(startsWhenUnsubscribed);
            assertThat(unsubscribedMetrics.shardsAcquired.sum()).as("no unit acquired for the unsubscribed subscription after unsubscribe() returned")
                                                                .isEqualTo(acquiredWhenUnsubscribed);
            assertThat(queue.registeredConsumers()).as("the holder took the unsubscribed subscription's lanes out").hasSize(2);
            assertThat(unsubscribed.isStarted()).as("the unsubscribed subscription's lanes are stopped").isFalse();
            assertThat(kept.isStarted()).as("the other subscription was started").isTrue();
            assertThat(queue.isStarted()).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                    () -> assertThat(registeredInstances()).as("only the kept subscription's instance id is registered")
                                                           .containsExactly(INSTANCE_ID + "-1"));

            var next = consumeUnchecked(queue);
            assertThat(next.isStarted()).as("premise: the next subscription started").isTrue();
            assertThat(registeredInstances()).as("the next consume() took the unsubscribed subscription's instance id")
                                             .containsExactlyInAnyOrder(INSTANCE_ID, INSTANCE_ID + "-1");
        }
    }

    /**
     * An unsubscribe called by a {@link QueueObserver} on the thread of a {@code start()} of the queue - told of the first
     * acquisition of another subscription's lane - hands taking the lanes out to that start, which then goes over the
     * registered consumers, the unsubscribed subscription's lanes still among them, starts neither of those lanes, and
     * takes them out before it lets go of the lock. It used to start them after the unsubscribe had returned, and stop them
     * again only as it let go.
     */
    @Test
    void an_unsubscribe_on_the_thread_of_a_queue_start_is_finished_by_that_start() throws Exception {
        var target                   = new AtomicReference<Subscription>();
        var unsubscribedMetrics      = new AtomicReference<ShardOwnerMetrics>();
        var acquiredWhenUnsubscribed = new AtomicLong(-1);
        var startsWhenUnsubscribed   = new AtomicInteger(-1);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE)) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    var subscription = target.getAndSet(null);
                    if (acquired && subscription != null) {
                        subscription.unsubscribe();
                        acquiredWhenUnsubscribed.set(unsubscribedMetrics.get().shardsAcquired.sum());
                        startsWhenUnsubscribed.set(laneStartsOf(INSTANCE_ID + "-1").size());
                    }
                }
            });
            var kept         = consumeUnchecked(queue);
            var unsubscribed = consumeUnchecked(queue);
            awaitBothLanesHoldUnits(kept);
            assertThat(unsubscribed.isStarted()).as("premise: the second subscription started").isTrue();
            queue.stop();
            // Registered in consume() order, two lanes per subscription sharing one metrics: the last two are the
            // unsubscribed subscription's.
            unsubscribedMetrics.set(queue.registeredConsumers().get(2).metrics());
            assertThat(queue.registeredConsumers().get(3).metrics()).as("premise: the last two lanes are one subscription's")
                                                                   .isSameAs(unsubscribedMetrics.get());

            // The kept subscription's lanes come first in the registered consumers, so the first acquisition the observer
            // is told of is the kept unordered lane's, before the start reaches the unsubscribed subscription's lanes.
            target.set(unsubscribed);
            queue.start();

            assertThat(target.get()).as("premise: the observer unsubscribed during the start").isNull();
            assertThat(startsWhenUnsubscribed.get()).as("premise: the observer counted the lane starts once it had unsubscribed")
                                                    .isNotNegative();
            assertThat(laneStartsOf(INSTANCE_ID + "-1")).as("the start started neither lane of the unsubscribed subscription")
                                                        .hasSize(startsWhenUnsubscribed.get());
            assertThat(unsubscribedMetrics.get().shardsAcquired.sum()).as("no unit acquired for the unsubscribed subscription after unsubscribe() returned")
                                                                      .isEqualTo(acquiredWhenUnsubscribed.get());
            assertThat(queue.registeredConsumers()).as("the start took the unsubscribed subscription's lanes out").hasSize(2);
            assertThat(unsubscribed.isStarted()).as("the unsubscribed subscription's lanes are stopped").isFalse();
            assertThat(kept.isStarted()).isTrue();
            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(
                    () -> assertThat(registeredInstances()).as("only the kept subscription's instance id is registered")
                                                           .containsExactly(INSTANCE_ID));
        }
    }

    /**
     * A subscription whose stop kept an ordered unit - a key still in its handler past {@code shedGrace} - leaves that
     * unit leased under its instance id until the instance's liveness lapses, a lease TTL later. A {@code consume()}
     * before then registers under the next instance id, with no COMPETING warning, and that id cannot take the kept unit
     * while the old instance is still live, so the key is not started again beside the handler still running it. Once a
     * lease TTL has passed, the id is free again. It used to be given back as soon as the lanes had stopped: the next
     * {@code consume()} registered under the same id, took the kept unit at once ({@code acquireLeaseGrant} accepts a row
     * naming its own owner) and delivered the key's message a second time while the first handler still ran it.
     * <p>
     * Also the membership row: the unordered lane stops first and used to delete the subscription's membership row, which
     * the ordered lane shares, although the ordered lane went on to keep a unit. With no row the kept unit's owner reads as
     * gone, so any other instance id could take it at once; the row now stays while a lease row names the instance.
     */
    @Test
    void a_subscription_whose_stop_kept_an_ordered_unit_keeps_its_instance_id_out_of_use_for_a_lease_ttl() throws Exception {
        var leaseTtl        = Duration.ofSeconds(5);
        var entered         = new CountDownLatch(1);
        var release         = new CountDownLatch(1);
        var deliveredAgain  = ConcurrentHashMap.<String>newKeySet();
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                var first = queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, ConsumerOptions.defaults());
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                first.unsubscribe();
                var unsubscribedAt = System.nanoTime();
                assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: the stop kept the unit whose key is still in its handler")
                                                           .isEqualTo(1);
                assertThat(registeredInstances()).as("the kept unit keeps the subscription's instance registered, whichever lane stopped first")
                                                 .containsExactly(INSTANCE_ID);
                assertThat(infos()).as("the queue says why the instance id stays out of use")
                                   .anyMatch(info -> info.contains("keeps the instance id '" + INSTANCE_ID + "'"));

                var second = queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        deliveredAgain.add(key);
                    }
                }, ConsumerOptions.defaults());
                assertThat(competingWarnings()).as("no subscription is left for the new one to compete with").isEmpty();
                assertThat(queue.registeredConsumers()).as("the new subscription's two lanes, and no others").hasSize(2);
                assertThat(second.isStarted()).isTrue();
                Awaitility.await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(3))
                          .untilAsserted(() -> {
                              assertThat(deliveredAgain).as("the key in the old handler is not started again beside it").isEmpty();
                              assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("the kept unit still names the old instance id").isEqualTo(1);
                          });
                assertThat(registeredInstances()).as("the new subscription registered under the next instance id")
                                                 .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
                assertThat(Duration.ofNanos(System.nanoTime() - unsubscribedAt)).as("premise: all of that within the lease TTL")
                                                                               .isLessThan(leaseTtl);

                release.countDown();
                second.unsubscribe();
                var lapsedAt = unsubscribedAt + leaseTtl.toNanos();
                Awaitility.await().atMost(leaseTtl.plusSeconds(1)).until(() -> System.nanoTime() - lapsedAt > 0);

                var third = consumeUnchecked(queue);
                assertThat(third.isStarted()).isTrue();
                assertThat(queue.registeredConsumers()).hasSize(2);
                assertThat(registeredInstances()).as("a lease TTL later the instance id is the caller's own again")
                                                 .containsExactly(INSTANCE_ID);
                assertThat(competingWarnings()).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    private PostgresqlMessageQueue newQueue(PostgresqlMessageQueue.LifecycleHook lifecycleHook) {
        return newQueue(lifecycleHook, ShardOwnerSettings.defaults());
    }

    private PostgresqlMessageQueue newQueue(PostgresqlMessageQueue.LifecycleHook lifecycleHook, ShardOwnerSettings settings) {
        return PostgresqlMessageQueue.builder()
                                     .setDataSource(dataSource)
                                     .setQueueId(QUEUE_ID)
                                     .setShardCount(SHARD_COUNT)
                                     .setInstanceId(INSTANCE_ID)
                                     .setSettings(settings)
                                     .setLifecycleHook(lifecycleHook)
                                     .build();
    }

    private static ShardOwnerSettings settingsWith(Duration shedGrace, Duration leaseTtl) {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(), Duration.ofMillis(1), Duration.ofMillis(2),
                                      Duration.ofMillis(300), Duration.ofMillis(100), 1_000, 8,
                                      Duration.ofMillis(50), Duration.ofSeconds(30), 2, shedGrace, leaseTtl, Duration.ofSeconds(60));
    }

    private int orderedUnitsOwnedBy(String instanceId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = 'ordered' AND owner = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("a count(*) returns one row").isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private static Subscription consumeUnchecked(PostgresqlMessageQueue queue) {
        try {
            return queue.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitBothLanesHoldUnits(Subscription subscription) {
        Awaitility.await().atMost(Duration.ofSeconds(20))
                  .alias("both lanes of the subscription hold units")
                  .until(() -> subscription.unorderedShardsHeld() > 0 && subscription.orderedUnitsHeld() > 0);
    }

    private List<String> registeredInstances() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT instance_id FROM " + ShardOwnedSchema.INSTANCE_TABLE
                                                                 + " WHERE queue_id = ? ORDER BY instance_id")) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                var instances = new ArrayList<String>();
                while (resultSet.next()) {
                    instances.add(resultSet.getString(1));
                }
                return instances;
            }
        }
    }

    private List<String> warnings() {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == WARN)
                          .map(ILoggingEvent::getFormattedMessage)
                          .toList();
    }

    private List<String> infos() {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == INFO)
                          .map(ILoggingEvent::getFormattedMessage)
                          .toList();
    }

    /**
     * Every start of a lane registered under {@code instanceId} that completed: a lane's start logs which units it owns
     * once it has leased them, none included.
     */
    private List<String> laneStartsOf(String instanceId) {
        // The appender adds under its own monitor (AppenderBase.doAppend), from the start's and the heartbeat's threads.
        synchronized (laneLogged) {
            return laneLogged.list.stream()
                                  .filter(event -> event.getLevel() == INFO)
                                  .map(ILoggingEvent::getFormattedMessage)
                                  .filter(message -> message.startsWith("Instance " + instanceId + " owns "))
                                  .toList();
        }
    }

    private List<String> competingWarnings() {
        return warnings().stream().filter(warning -> warning.contains("COMPETING")).toList();
    }

    private static String body(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
