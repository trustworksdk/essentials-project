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
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static ch.qos.logback.classic.Level.*;
import static dk.trustworks.essentials.components.queue.shardowned.SingleRowQueries.*;
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
     * Closed by try-with-resources and consumed anew: {@link Subscription#close()} ends the subscription as
     * {@link Subscription#unsubscribe()} does, so the next {@code consume()} is not a COMPETING consumer and takes the
     * caller's instance id again. {@code close()} used to only stop it, leaving it registered: the next {@code consume()}
     * warned of a COMPETING consumer and registered under {@code <id>-1}.
     */
    @Test
    void a_subscription_closed_and_consumed_anew_leaves_only_the_new_one_registered_under_the_callers_instance_id() throws Exception {
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE)) {
            Subscription closed;
            try (var subscription = queue.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults())) {
                awaitBothLanesHoldUnits(subscription);
                closed = subscription;
            }
            assertThat(closed.isStarted()).as("the closed subscription's lanes are stopped").isFalse();
            assertThat(queue.registeredConsumers()).as("the closed subscription's lanes left the queue").isEmpty();

            var live = queue.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults());

            assertThat(competingWarnings()).as("no closed subscription is left for the new one to compete with").isEmpty();
            assertThat(queue.registeredConsumers()).as("the live subscription's two lanes, and no others").hasSize(2);
            awaitBothLanesHoldUnits(live);
            assertThat(registeredInstances()).as("the live subscription took the caller's instance id again, not a new suffix")
                                             .containsExactly(INSTANCE_ID);
            assertThatThrownBy(closed::start).as("a closed subscription cannot be started again")
                                             .isInstanceOf(IllegalStateException.class)
                                             .hasMessageContaining("unsubscribed");
        }
    }

    /**
     * Closed, then the queue stopped and started: the queue's {@code start()} starts the subscription still registered and
     * not the closed one, so only the live handler is delivered to. {@link Subscription#close()} used to only stop the
     * subscription, which stayed registered, and the queue's {@code start()} after its {@code stop()} started it again,
     * delivering to a handler its caller had closed.
     */
    @Test
    void a_queue_start_after_its_stop_does_not_start_a_closed_subscription() throws Exception {
        var closedHandler = ConcurrentHashMap.<String>newKeySet();
        var delivered     = ConcurrentHashMap.<String>newKeySet();
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE)) {
            var closed = queue.consume((messageId, key, payload, payloadType) -> closedHandler.add(body(payload)), ConsumerOptions.defaults());
            awaitBothLanesHoldUnits(closed);
            var live = queue.consume((messageId, key, payload, payloadType) -> delivered.add(body(payload)), ConsumerOptions.defaults());

            closed.close();
            assertThat(queue.registeredConsumers()).as("the live subscription's two lanes, and no others").hasSize(2);

            queue.stop();
            queue.start();

            assertThat(closed.isStarted()).as("the queue's start() did not start the closed subscription").isFalse();
            assertThat(live.isStarted()).as("the queue's start() started the live subscription").isTrue();
            awaitBothLanesHoldUnits(live);

            var batch = new ArrayList<Message>();
            for (var index = 0; index < MESSAGES; index++) {
                batch.add(Message.of(("m-" + index).getBytes(StandardCharsets.UTF_8), 1));
            }
            queue.enqueue(batch);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(delivered).hasSize(MESSAGES));
            assertThat(closedHandler).as("no message reached the handler of the closed subscription").isEmpty();
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
     * unit leased under its instance id. A {@code consume()} while the key is in its handler registers under the next
     * instance id, with no COMPETING warning, and that id cannot take the kept unit while the old instance is still live,
     * so the key is not started again beside the handler still running it. Once the handler has returned, the id is free
     * again at once - not a lease TTL later, which both outlasts a handler that returns sooner and says nothing about one
     * that runs longer. The id used to be given back as soon as the lanes had stopped: the next {@code consume()}
     * registered under the same id, took the kept unit at once ({@code acquireLeaseGrant} accepts a row naming its own
     * owner) and delivered the key's message a second time while the first handler still ran it.
     * <p>
     * Also the membership row: the unordered lane stops first and used to delete the subscription's membership row, which
     * the ordered lane shares, although the ordered lane went on to keep a unit. With no row the kept unit's owner reads as
     * gone, so any other instance id could take it at once; the unordered lane now leaves the row to the ordered lane, which
     * leaves it to age out as it keeps the unit.
     */
    @Test
    void a_subscription_whose_stop_kept_an_ordered_unit_keeps_its_instance_id_out_of_use_while_the_key_is_in_its_handler() throws Exception {
        var leaseTtl        = Duration.ofSeconds(10);
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
                var firstLanes = queue.registeredConsumers();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                first.unsubscribe();
                var unsubscribedAt = System.nanoTime();
                assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: the stop kept the unit whose key is still in its handler")
                                                           .isEqualTo(1);
                assertThat(registeredInstances()).as("the kept unit keeps the subscription's instance registered, whichever lane stopped first")
                                                 .containsExactly(INSTANCE_ID);
                assertThat(infos()).as("the queue says once why the instance id stays out of use, and until when")
                                   .filteredOn(info -> info.contains("keeps the instance id '" + INSTANCE_ID + "'"))
                                   .singleElement()
                                   .satisfies(info -> assertThat(info).contains("of an unsubscribed subscription",
                                                                                "out of use until the handler of the key it kept an ordered unit for has returned")
                                                                      .doesNotContain("out of use for "));

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

                release.countDown();
                second.unsubscribe();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(firstLanes).as("premise: the key has left the handler its unit was kept for, and the unit is handed back")
                                                                     .noneMatch(ShardOwnedQueue::keptOrderedUnitNotHandedBack));
                // No lane of the unsubscribed subscription runs, and none ever will: the kept unit is handed back all the same,
                // its acknowledgement flushed, rather than left for whoever takes it next to charge and deliver again.
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(orderedMessagesLeft()).as("the handled message was acknowledged as the unit was handed back")
                                                                                .isZero());

                var third = consumeUnchecked(queue);
                assertThat(third.isStarted()).isTrue();
                assertThat(queue.registeredConsumers()).hasSize(2);
                // Registered under the caller's own id, the new subscription takes the unit that id kept as well as every
                // other; under another id it could not take that one while the old id's membership row is live.
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(orderedUnitsOwnedBy(INSTANCE_ID))
                                  .as("once the handler has returned the instance id is the caller's own again")
                                  .isEqualTo(ShardOwnedSchema.ORDERED_UNITS));
                assertThat(Duration.ofNanos(System.nanoTime() - unsubscribedAt)).as("premise: all of that within the lease TTL, so no TTL freed the id")
                                                                               .isLessThan(leaseTtl);
                assertThat(competingWarnings()).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_subscription_whose_stop_kept_an_ordered_unit_keeps_its_instance_id_out_of_use_while_the_key_is_in_its_handler}
     * with the database refusing to release the kept unit's lease row once its handler has returned: the hand-back as the
     * key leaves the handler fails, and the unsubscribed subscription's lanes never run again, so no heartbeat retries it.
     * The instance id stays out of use all the same - its row still names the id, and a pair registering under it would
     * take the unit back as from a crashed owner, charging an attempt and delivering again the message the handler had
     * handled - so a {@code consume()} meanwhile registers under the next id. A {@code consume()} once the database lets
     * the release through hands the unit back before it picks an id, and registers under the caller's own. The id used to
     * be freed once a hand-back had failed, and the next {@code consume()} took the unit back unclean.
     */
    @Test
    void a_subscription_whose_kept_unit_could_not_be_handed_back_keeps_its_instance_id_out_of_use_until_a_consume_hands_it_back() throws Exception {
        // Far beyond the test, so the unsubscribed instance's membership row cannot age out and let another id take the unit.
        var leaseTtl       = Duration.ofSeconds(20);
        var entered        = new CountDownLatch(1);
        var release        = new CountDownLatch(1);
        var deliveredAgain = ConcurrentHashMap.<String>newKeySet();
        MessageHandler recording = (messageId, key, payload, payloadType) -> {
            if (key != null) {
                deliveredAgain.add(key);
            }
        };
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                var first = queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, ConsumerOptions.defaults());
                var firstLanes = queue.registeredConsumers();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                first.unsubscribe();
                var unsubscribedAt = System.nanoTime();
                var kept           = keptOrderedFence(INSTANCE_ID);
                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "ordered", kept.shard(), true);
                try {
                    release.countDown();
                    Awaitility.await().atMost(Duration.ofSeconds(5))
                              .untilAsserted(() -> assertThat(laneWarnings()).as("premise: the hand-back as the key left its handler failed")
                                                                             .anyMatch(warning -> warning.contains("could not hand back ordered unit")));
                    assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, kept.shard()))
                            .as("premise: refused by the database, as the injection meant").isPositive();
                    assertThat(firstLanes).as("a failed hand-back is not a hand-back").anyMatch(ShardOwnedQueue::keptOrderedUnitNotHandedBack);

                    var second = queue.consume(recording, ConsumerOptions.defaults());
                    assertThat(second.isStarted()).isTrue();
                    assertThat(registeredInstances()).as("the instance id is still out of use: the new subscription registered under the next one")
                                                     .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
                    Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                              .untilAsserted(() -> assertThat(fenceOf(kept.shard())).as("nobody took the unit not handed back")
                                                                                    .isEqualTo(kept.fence()));
                    second.unsubscribe();
                } finally {
                    LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, new short[]{QUEUE_ID}, "ordered", kept.shard(), false);
                }

                var third = queue.consume(recording, ConsumerOptions.defaults());
                assertThat(third.isStarted()).isTrue();
                assertThat(registeredInstances()).as("the consume() handed the unit back before it picked an instance id, and took the caller's own")
                                                 .containsExactly(INSTANCE_ID);
                assertThat(firstLanes).noneMatch(ShardOwnedQueue::keptOrderedUnitNotHandedBack);
                Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("the caller's own id takes every unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                    assertThat(fenceOf(kept.shard())).as("the handed-back unit taken under a newer fence").isGreaterThan(kept.fence());
                });
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertThat(deliveredAgain).as("the message the kept handler handled is not delivered again").isEmpty();
                    assertThat(orderedMessagesLeft()).as("its acknowledgement was flushed").isZero();
                });
                assertThat(Duration.ofNanos(System.nanoTime() - unsubscribedAt)).as("premise: all of that within the lease TTL, so no TTL freed the id")
                                                                               .isLessThan(leaseTtl);
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * A {@code stop()} of the queue whose ordered lane kept a unit - a key still in its handler past {@code shedGrace} -
     * and the queue's {@code start()} while that handler still runs: the restarted ordered lane registers under the same
     * instance id and takes every other unit, but leaves the kept one alone until the handler has returned, so the key is
     * not delivered again beside it. Once it has returned the unit is handed back - its acknowledgement flushed, its lease
     * row released - and a heartbeat takes it, under a newer fence, charging nothing and delivering nothing again. The
     * restart used to take the kept unit at once ({@code acquireLeaseGrant} accepts a row naming its own owner) and deliver
     * the key's message a second time while the first handler still ran it; and, once the handler had returned, it took the
     * unit back with the row still naming the instance, as an unclean takeover, and delivered the handled message again.
     */
    @Test
    void a_queue_restarted_while_a_key_is_in_the_handler_its_stop_kept_an_ordered_unit_for_leaves_that_unit_alone() throws Exception {
        restartWhileAKeptKeyIsInItsHandler((queue, subscription) -> {
            queue.stop();
            return queue::start;
        });
    }

    /**
     * {@link #a_queue_restarted_while_a_key_is_in_the_handler_its_stop_kept_an_ordered_unit_for_leaves_that_unit_alone}
     * for a {@link Subscription#stop()} and {@link Subscription#start()}: the same lanes restart under the same instance
     * id, and the same guard applies.
     */
    @Test
    void a_subscription_restarted_while_a_key_is_in_the_handler_its_stop_kept_an_ordered_unit_for_leaves_that_unit_alone() throws Exception {
        restartWhileAKeptKeyIsInItsHandler((queue, subscription) -> {
            subscription.stop();
            return subscription::start;
        });
    }

    /**
     * A stop and restart of what {@code stopAndThenRestart} names, with an ordered handler holding key ACC-1 past
     * {@code shedGrace}: the stop keeps that key's unit, and the restart - well inside the lease TTL, so nothing has lapsed
     * - must leave it alone until the handler has returned, then take it under a newer fence once it has been handed back,
     * with the handled message neither delivered again nor left unacknowledged.
     *
     * @param stopAndThenRestart stops the queue or the subscription, and returns what restarts it
     */
    private void restartWhileAKeptKeyIsInItsHandler(BiFunction<PostgresqlMessageQueue, Subscription, Runnable> stopAndThenRestart) throws Exception {
        // A heartbeat every second, so the restarted lane's acquire loop runs several times while the handler is held.
        var leaseTtl = Duration.ofSeconds(3);
        var entries  = new AtomicInteger();
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (key != null && entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, ConsumerOptions.defaults());
                var lanes = queue.registeredConsumers();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                var restart = stopAndThenRestart.apply(queue, subscription);
                assertThat(subscription.isStarted()).as("premise: stopped").isFalse();
                assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: the stop kept the unit whose key is still in its handler")
                                                           .isEqualTo(1);
                assertThat(lanes).as("premise: a lane knows the key is still in the handler of the unit it kept")
                                 .anyMatch(ShardOwnedQueue::keptOrderedUnitNotHandedBack);
                var kept = keptOrderedFence(INSTANCE_ID);

                restart.run();
                assertThat(subscription.isStarted()).as("restarted").isTrue();
                assertThat(registeredInstances()).as("under the same instance id").containsExactly(INSTANCE_ID);
                Awaitility.await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> {
                              assertThat(entries).as("the key in the old handler is not delivered again beside it").hasValue(1);
                              assertThat(fenceOf(kept.shard())).as("the kept unit is not taken while its key is in the handler")
                                                               .isEqualTo(kept.fence());
                              assertThat(subscription.orderedUnitsHeld()).as("every ordered unit but the kept one")
                                                                         .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);
                          });

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> {
                              assertThat(fenceOf(kept.shard())).as("taken once the handler has returned, under a newer fence")
                                                               .isGreaterThan(kept.fence());
                              assertThat(subscription.orderedUnitsHeld()).as("every ordered unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                          });
                // Handed back cleanly before it was taken again, so the new owner charged no attempt and redelivered nothing.
                Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> {
                              assertThat(entries).as("the message the kept handler handled is not delivered again").hasValue(1);
                              assertThat(orderedMessagesLeft()).as("its acknowledgement was flushed as the unit was handed back").isZero();
                          });
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_queue_restarted_while_a_key_is_in_the_handler_its_stop_kept_an_ordered_unit_for_leaves_that_unit_alone}
     * with a handler that does not return within the queue's {@code stuckHandlerTimeout}. Until the bound the restarted
     * queue leaves the kept unit alone, and once the handler has been there longer than {@code leaseTtl} says so in one
     * WARN, however many heartbeats skip the unit meanwhile. Past the bound it stops waiting: a heartbeat takes the unit
     * over under a new fence and delivers the key's message again while the first handler still runs it - the trade the
     * bound makes - and that handler's acknowledgement, once it returns, is refused. Throughout,
     * {@code ShardOwnerMetrics.keptUnitsInHandler} reads one while that handler runs and zero once it has returned. Without
     * the bound the restarted queue waited for the handler for as long as it ran, and nothing but a DEBUG line said so.
     */
    @Test
    void a_queue_restarted_while_a_key_outlasts_the_stuck_handler_timeout_in_the_handler_its_stop_kept_takes_the_unit_over() throws Exception {
        // A heartbeat every second; the held-up WARN once the handler has been there 3 s, the takeover once it has been 6 s.
        var leaseTtl            = Duration.ofSeconds(3);
        var stuckHandlerTimeout = Duration.ofSeconds(6);
        var entries             = new AtomicInteger();
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var settings            = settingsWith(Duration.ofMillis(300), leaseTtl).withStuckHandlerTimeout(stuckHandlerTimeout);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settings)) {
            try {
                var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (key != null && entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, ConsumerOptions.defaults());
                var lanes   = queue.registeredConsumers();
                var metrics = lanes.getFirst().metrics();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var enteredAt = System.nanoTime();
                assertThat(metrics.keptUnitsInHandler()).as("no unit is kept while the queue runs").isZero();

                queue.stop();
                assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: the stop kept the unit whose key is still in its handler")
                                                           .isEqualTo(1);
                var kept = keptOrderedFence(INSTANCE_ID);
                assertThat(metrics.keptUnitsInHandler()).as("the kept unit whose key is in its handler").isEqualTo(1);
                assertThat(metrics.snapshot()).containsEntry("keptUnitsInHandler", 1);

                queue.start();
                assertThat(subscription.isStarted()).as("restarted").isTrue();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(subscription.orderedUnitsHeld()).as("every ordered unit but the kept one")
                                                                                          .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1));
                // Until the bound: the unit is left alone, and held up past leaseTtl it is reported, once.
                var heldUp = "holds ordered unit " + kept.shard() + " on queue " + QUEUE_ID + " unserved";
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(laneWarnings()).as("the unit held up past leaseTtl is reported")
                                                                         .anyMatch(warning -> warning.contains(heldUp)));
                var reportedAt = System.nanoTime();
                assertThat(Duration.ofNanos(reportedAt - enteredAt)).as("premise: reported before the bound").isLessThan(stuckHandlerTimeout);
                assertThat(laneWarnings()).filteredOn(warning -> warning.contains(heldUp))
                                          .singleElement()
                                          .satisfies(warning -> assertThat(warning).contains("[ACC-1]", "longer than the 3000 ms leaseTtl",
                                                                                             "stuckHandlerTimeout (PT6S)"));
                assertThat(entries).as("the key in the old handler is not delivered again before the bound").hasValue(1);
                assertThat(fenceOf(kept.shard())).as("the kept unit is not taken before the bound").isEqualTo(kept.fence());

                // Past the bound: taken over under a new fence, and the key delivered again beside the handler still running.
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> {
                              assertThat(fenceOf(kept.shard())).as("taken over under a newer fence").isGreaterThan(kept.fence());
                              assertThat(subscription.orderedUnitsHeld()).as("every ordered unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                              assertThat(entries).as("the key's message delivered again while the first handler runs").hasValue(2);
                          });
                assertThat(Duration.ofNanos(System.nanoTime() - enteredAt)).as("not before the bound").isGreaterThanOrEqualTo(stuckHandlerTimeout);
                assertThat(laneWarnings()).as("the takeover is announced once")
                                          .filteredOn(warning -> warning.contains("stops waiting for ordered unit " + kept.shard()))
                                          .singleElement()
                                          .satisfies(warning -> assertThat(warning).contains("[ACC-1]", "stuckHandlerTimeout (PT6S)"));
                assertThat(laneWarnings()).as("the held-up WARN is rate limited: one, though several heartbeats skipped the unit")
                                          .filteredOn(warning -> warning.contains(heldUp))
                                          .hasSize(1);
                assertThat(metrics.keptUnitsInHandler()).as("the first handler still runs").isEqualTo(1);

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(metrics.keptUnitsInHandler()).as("the first handler has returned").isZero());
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(orderedMessagesLeft()).as("the new owner acknowledged the message").isZero());
                assertThat(subscription.orderedUnitsHeld()).as("the new owner keeps the unit: the old one's release under its stale fence changed nothing")
                                                           .isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                assertThat(fenceOf(kept.shard())).isGreaterThan(kept.fence());
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_subscription_whose_stop_kept_an_ordered_unit_keeps_its_instance_id_out_of_use_while_the_key_is_in_its_handler}
     * with a handler that does not return within the queue's {@code stuckHandlerTimeout}: until the bound a
     * {@code consume()} registers under the next instance id, and past it the instance id is free again, so the next
     * {@code consume()} registers under the caller's own id and takes the kept unit over under a new fence, delivering the
     * key's message again while the first handler still runs it. Without the bound the id stayed out of use for as long as
     * that handler ran.
     */
    @Test
    void a_subscription_whose_kept_unit_outlasts_the_stuck_handler_timeout_frees_its_instance_id_for_a_subscription_that_takes_the_unit_over() throws Exception {
        // Far beyond the test, so the unsubscribed instance's membership row cannot age out and let another id take the unit.
        var leaseTtl            = Duration.ofSeconds(20);
        var stuckHandlerTimeout = Duration.ofSeconds(3);
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var deliveredAgain      = ConcurrentHashMap.<String>newKeySet();
        MessageHandler recording = (messageId, key, payload, payloadType) -> {
            if (key != null) {
                deliveredAgain.add(key);
            }
        };
        var settings = settingsWith(Duration.ofMillis(300), leaseTtl).withStuckHandlerTimeout(stuckHandlerTimeout);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settings)) {
            try {
                var first = queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, ConsumerOptions.defaults());
                var firstLanes = queue.registeredConsumers();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var enteredAt = System.nanoTime();

                first.unsubscribe();
                var kept = keptOrderedFence(INSTANCE_ID);
                assertThat(firstLanes.getFirst().metrics().keptUnitsInHandler()).as("the kept unit whose key is in its handler").isEqualTo(1);

                var second = queue.consume(recording, ConsumerOptions.defaults());
                assertThat(Duration.ofNanos(System.nanoTime() - enteredAt)).as("premise: before the bound").isLessThan(stuckHandlerTimeout);
                assertThat(registeredInstances()).as("before the bound the instance id is out of use")
                                                 .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
                second.unsubscribe();

                var pastTheBound = stuckHandlerTimeout.minus(Duration.ofNanos(System.nanoTime() - enteredAt));
                if (pastTheBound.isPositive()) {
                    Thread.sleep(pastTheBound.toMillis() + 100);
                }
                var third = queue.consume(recording, ConsumerOptions.defaults());
                assertThat(third.isStarted()).isTrue();
                assertThat(registeredInstances()).as("past the bound the caller's own instance id is free again")
                                                 .containsExactly(INSTANCE_ID);
                assertThat(laneWarnings()).as("the lane says it stops waiting, once")
                                          .filteredOn(warning -> warning.contains("stops waiting for ordered unit " + kept.shard()))
                                          .hasSize(1);
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("the caller's own id takes every unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                    assertThat(fenceOf(kept.shard())).as("the kept unit taken over under a newer fence").isGreaterThan(kept.fence());
                    assertThat(deliveredAgain).as("the key's message delivered again while the first handler runs").containsExactly("ACC-1");
                });
                assertThat(firstLanes.getFirst().metrics().keptUnitsInHandler()).as("the first handler still runs").isEqualTo(1);

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(firstLanes.getFirst().metrics().keptUnitsInHandler()).isZero());
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(orderedMessagesLeft()).as("the new owner acknowledged the message").isZero());
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_queue_restarted_while_a_key_outlasts_the_stuck_handler_timeout_in_the_handler_its_stop_kept_takes_the_unit_over}
     * and then a second instance: the restarted queue took the kept unit over once, and from then on it is any other unit
     * to it, though the stuck handler still runs. The second instance halves the fair share, the restarted queue sheds
     * the unit it took over last like the rest of its excess, and the unit stays with the second instance. Asked for again
     * on every tick - the kept owner holds its row in memory until its handler returns - the unit was taken back past the
     * fair share on the very tick that released it, then shed and taken back again for as long as the handler hung,
     * while the second instance stayed one unit below its share.
     */
    @Test
    void a_unit_taken_over_past_the_stuck_handler_timeout_is_taken_over_once_and_then_shed_like_any_other() throws Exception {
        var leaseTtl            = Duration.ofSeconds(3);
        var stuckHandlerTimeout = Duration.ofSeconds(3);
        var entries             = new AtomicInteger();
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var settings            = settingsWith(Duration.ofMillis(300), leaseTtl).withStuckHandlerTimeout(stuckHandlerTimeout);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settings);
             var other = newQueue("other", settings)) {
            try {
                queue.consume((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler for the whole test; a later one returns at once.
                    if (key != null && entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(90));
                    }
                }, ConsumerOptions.defaults());
                var metrics = queue.registeredConsumers().getFirst().metrics();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                queue.stop();
                var kept = keptOrderedFence(INSTANCE_ID);
                queue.start();
                Awaitility.await().atMost(Duration.ofSeconds(15))
                          .untilAsserted(() -> {
                              assertThat(fenceOf(kept.shard())).as("premise: taken over past the bound").isGreaterThan(kept.fence());
                              assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: every ordered unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                              assertThat(entries).as("premise: the key's message delivered again").hasValue(2);
                          });

                other.consume((messageId, key, payload, payloadType) -> { }, ConsumerOptions.defaults());
                var half = ShardOwnedSchema.ORDERED_UNITS / 2;
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> {
                              assertThat(ownerOf(kept.shard())).as("the unit taken over last is shed, and taken by the other instance").isEqualTo("other");
                              assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("the restarted queue sheds down to its share").isEqualTo(half);
                              assertThat(orderedUnitsOwnedBy("other")).as("the other instance takes its share").isEqualTo(half);
                          });
                var settled = fenceOf(kept.shard());
                Awaitility.await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(8))
                          .untilAsserted(() -> {
                              assertThat(fenceOf(kept.shard())).as("the unit is not taken back, again and again").isEqualTo(settled);
                              assertThat(ownerOf(kept.shard())).isEqualTo("other");
                              assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).isEqualTo(half);
                          });
                assertThat(metrics.keptUnitsInHandler()).as("premise: the stuck handler ran throughout").isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_queue_restarted_while_a_key_is_in_the_handler_its_stop_kept_an_ordered_unit_for_leaves_that_unit_alone},
     * with the operator releasing the kept unit through {@link MessageQueue#releaseKeptOrderedUnit} while its handler is
     * still held, long before {@code stuckHandlerTimeout}: the release reports what it released, the restarted queue takes
     * the unit under a newer fence and delivers the key's message again beside the handler still running - the trade the
     * operator asked for - and a unit no stop kept, asked for, is left exactly as it was. A later stop while that handler
     * still runs deregisters the instance: nothing waits for the released unit's handler any more, so the stop does not
     * stay registered for it. Before the operation existed nothing an operator could call freed the unit before the
     * handler returned, the instance's liveness lapsed, or the bound fired.
     */
    @Test
    void a_kept_unit_released_on_request_while_its_key_is_in_the_handler_is_taken_under_a_newer_fence_and_no_other_unit_is_touched() throws Exception {
        // A heartbeat every second; the bound stays at its 15-minute default, far beyond the test.
        var leaseTtl = Duration.ofSeconds(3);
        var entries  = new AtomicInteger();
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler; a later one returns at once.
                    if (key != null && entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                var metrics = queue.registeredConsumers().getFirst().metrics();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unitOfTheKey = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                assertThat(queue.releaseKeptOrderedUnit(unitOfTheKey)).as("nothing is kept while the queue runs").isEmpty();

                queue.stop();
                var kept = keptOrderedFence(INSTANCE_ID);
                assertThat(kept.shard()).as("premise: the stop kept the unit of the key in its handler").isEqualTo(unitOfTheKey);
                queue.start();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(subscription.orderedUnitsHeld()).as("premise: the restart leaves the kept unit alone")
                                                                                          .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1));

                var notKept      = (kept.shard() + 1) % ShardOwnedSchema.ORDERED_UNITS;
                var notKeptFence = fenceOf(notKept);
                assertThat(queue.releaseKeptOrderedUnit(notKept)).as("a unit no stop kept is not released").isEmpty();
                assertThat(ownerOf(notKept)).as("and keeps its owner").isEqualTo(INSTANCE_ID);
                assertThat(fenceOf(notKept)).as("and its fence").isEqualTo(notKeptFence);

                var releases = queue.releaseKeptOrderedUnit(kept.shard());
                assertThat(releases).as("the release says what it released").singleElement().satisfies(released -> {
                    assertThat(released.unit()).isEqualTo(kept.shard());
                    assertThat(released.instanceId()).isEqualTo(INSTANCE_ID);
                    assertThat(released.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                    assertThat(released.keysInHandler()).containsExactly("ACC-1");
                    assertThat(released.longestInHandler()).isPositive();
                });
                assertThat(laneWarnings()).as("one WARN names the unit, its key and what the release costs")
                                          .filteredOn(warning -> warning.contains("released ordered unit " + kept.shard() + " on queue " + QUEUE_ID + " on request"))
                                          .singleElement()
                                          .satisfies(warning -> assertThat(warning).contains("[ACC-1]", "delivers the messages in those handlers again"));
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> {
                              assertThat(fenceOf(kept.shard())).as("taken under a newer fence").isGreaterThan(kept.fence());
                              assertThat(subscription.orderedUnitsHeld()).as("every ordered unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                              assertThat(entries).as("the key's message delivered again while the first handler runs").hasValue(2);
                          });
                assertThat(metrics.keptUnitsInHandler()).as("the first handler still runs, and is still counted").isEqualTo(1);
                assertThat(queue.releaseKeptOrderedUnit(kept.shard())).as("released once: the unit is no longer kept").isEmpty();
                assertThat(fenceOf(notKept)).as("the unit no stop kept is untouched").isEqualTo(notKeptFence);

                // A later stop while the released unit's first handler still runs: nothing waits for that handler, so the
                // stop deregisters the instance rather than staying registered for the unit an earlier stop kept.
                queue.stop();
                assertThat(metrics.keptUnitsInHandler()).as("premise: the first handler still runs").isEqualTo(1);
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(registeredInstances()).as("the later stop deregistered the instance").isEmpty());
                assertThat(laneInfos()).as("it does not stay registered for the released unit")
                                   .noneMatch(info -> info.contains("stays registered on queue " + QUEUE_ID + ": an earlier stop kept"));

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(metrics.keptUnitsInHandler()).as("the first handler has returned").isZero());
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(orderedMessagesLeft()).as("the new owner acknowledged the message").isZero());
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * A unit a stop kept, whose lease row someone else has taken under a newer fence meanwhile - another instance once this
     * one's liveness lapsed, made here by writing the row directly - is not released when asked: the fenced release finds
     * the row no longer this instance's, says so ({@link KeptUnitRelease.Outcome#ALREADY_TAKEN}, naming the key in its
     * handler), and leaves that owner's row and fence as they were. Asked again, nothing is kept any more.
     */
    @Test
    void a_kept_unit_whose_row_was_taken_meanwhile_is_reported_already_taken_and_its_new_owner_is_left_alone() throws Exception {
        var leaseTtl = Duration.ofSeconds(3);
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                // Stopped, and not started again: no acquirer of this queue races the row written below.
                queue.stop();
                var kept = keptOrderedFence(INSTANCE_ID);
                takeOrderedUnit(kept.shard(), "other", kept.fence() + 1);

                var releases = Awaitility.await().atMost(Duration.ofSeconds(5))
                                         .until(() -> queue.releaseKeptOrderedUnit(kept.shard()),
                                                answer -> answer.stream().noneMatch(entry -> entry.outcome() == KeptUnitRelease.Outcome.BUSY));
                assertThat(releases).as("the release says the unit was taken meanwhile").singleElement().satisfies(answer -> {
                    assertThat(answer.unit()).isEqualTo(kept.shard());
                    assertThat(answer.instanceId()).isEqualTo(INSTANCE_ID);
                    assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.ALREADY_TAKEN);
                    assertThat(answer.keysInHandler()).containsExactly("ACC-1");
                });
                assertThat(ownerOf(kept.shard())).as("the new owner keeps the row").isEqualTo("other");
                assertThat(fenceOf(kept.shard())).as("under its fence").isEqualTo(kept.fence() + 1);
                assertThat(laneWarnings()).as("no release is reported")
                                          .noneMatch(warning -> warning.contains("released ordered unit " + kept.shard() + " on queue " + QUEUE_ID));
                assertThat(queue.releaseKeptOrderedUnit(kept.shard())).as("asked again: the unit is no longer kept").isEmpty();
                assertThat(ownerOf(kept.shard())).isEqualTo("other");
                assertThat(fenceOf(kept.shard())).isEqualTo(kept.fence() + 1);
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_queue_restarted_while_a_key_outlasts_the_stuck_handler_timeout_in_the_handler_its_stop_kept_takes_the_unit_over},
     * with the message delivered again hanging too, and the queue stopped once more: the queue now lists two kept entries
     * for the unit - the first stop's, whose row the restart took over and which still reads as holding it until a fenced
     * write of it is refused, ahead of the second stop's, which holds the row. A release on request reports the entry that
     * held the row, {@link KeptUnitRelease.Outcome#RELEASED}, and releases it. It stopped at the first entry, answered
     * {@link KeptUnitRelease.Outcome#ALREADY_TAKEN} - "nothing released" - and left the unit kept by the second stop.
     */
    @Test
    void a_unit_kept_again_after_a_takeover_past_the_stuck_handler_timeout_is_released_from_the_stop_that_holds_its_row() throws Exception {
        // A heartbeat every second; the takeover once the first handler has been there 3 s.
        var leaseTtl            = Duration.ofSeconds(3);
        var stuckHandlerTimeout = Duration.ofSeconds(3);
        var entries             = new AtomicInteger();
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var settings            = settingsWith(Duration.ofMillis(300), leaseTtl).withStuckHandlerTimeout(stuckHandlerTimeout);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settings)) {
            try {
                queue.consume((messageId, key, payload, payloadType) -> {
                    // Every delivery of the key holds its handler: the first, and the one the takeover delivers again.
                    if (key != null) {
                        entries.incrementAndGet();
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                queue.stop();
                var keptFirst = keptOrderedFence(INSTANCE_ID);
                queue.start();
                Awaitility.await().atMost(Duration.ofSeconds(15))
                          .untilAsserted(() -> {
                              assertThat(fenceOf(keptFirst.shard())).as("premise: taken over past the bound").isGreaterThan(keptFirst.fence());
                              assertThat(entries).as("premise: the key's message delivered again, and held too").hasValue(2);
                          });

                queue.stop();
                var keptAgain = keptOrderedFence(INSTANCE_ID);
                assertThat(keptAgain.shard()).as("premise: the second stop kept the same unit").isEqualTo(keptFirst.shard());
                assertThat(keptAgain.fence()).as("premise: under the takeover's newer fence").isGreaterThan(keptFirst.fence());

                var releases = Awaitility.await().atMost(Duration.ofSeconds(5))
                                         .until(() -> queue.releaseKeptOrderedUnit(keptAgain.shard()),
                                                answer -> answer.stream().noneMatch(entry -> entry.outcome() == KeptUnitRelease.Outcome.BUSY));
                assertThat(releases).as("the entry that held the row is released").singleElement().satisfies(answer -> {
                    assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                    assertThat(answer.keysInHandler()).containsExactly("ACC-1");
                });
                assertThat(ownerOf(keptAgain.shard())).as("the row is released").isNull();
                assertThat(fenceOf(keptAgain.shard())).as("a release does not bump the fence").isEqualTo(keptAgain.fence());
                assertThat(queue.releaseKeptOrderedUnit(keptAgain.shard())).as("asked again: the unit is no longer kept").isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_subscription_whose_stop_kept_an_ordered_unit_keeps_its_instance_id_out_of_use_while_the_key_is_in_its_handler},
     * with the operator releasing the kept unit through {@link MessageQueue#releaseKeptOrderedUnit} while its handler is
     * still held: the queue asks the unsubscribed subscription's lanes too, as it still keeps their instance id out of
     * use, and once the unit is released nothing waits for that handler any more - the next {@code consume()} registers
     * under the caller's own instance id again and takes the unit under a newer fence. A release that left the unit
     * waiting for its handler would keep the id out of use until the handler returned.
     */
    @Test
    void a_kept_unit_released_on_request_frees_the_instance_id_its_unsubscribed_subscription_kept_out_of_use() throws Exception {
        // Far beyond the test, so the unsubscribed instance's membership row cannot age out and let another id take the unit.
        var leaseTtl       = Duration.ofSeconds(20);
        var entered        = new CountDownLatch(1);
        var release        = new CountDownLatch(1);
        var deliveredAgain = ConcurrentHashMap.<String>newKeySet();
        MessageHandler recording = (messageId, key, payload, payloadType) -> {
            if (key != null) {
                deliveredAgain.add(key);
            }
        };
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                var first = queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                var firstLanes = queue.registeredConsumers();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                first.unsubscribe();
                var unsubscribedAt = System.nanoTime();
                var kept           = keptOrderedFence(INSTANCE_ID);
                var second         = queue.consume(recording, ConsumerOptions.defaults());
                assertThat(registeredInstances()).as("premise: the kept unit keeps the instance id out of use")
                                                 .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
                second.unsubscribe();

                assertThat(queue.releaseKeptOrderedUnit(kept.shard())).as("the unsubscribed subscription's lane releases the unit it kept")
                                                                      .singleElement()
                                                                      .satisfies(released -> {
                                                                          assertThat(released.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                                                                          assertThat(released.instanceId()).isEqualTo(INSTANCE_ID);
                                                                          assertThat(released.keysInHandler()).containsExactly("ACC-1");
                                                                      });
                assertThat(firstLanes.getFirst().metrics().keptUnitsInHandler()).as("premise: the first handler still runs").isEqualTo(1);

                var third = queue.consume(recording, ConsumerOptions.defaults());
                assertThat(third.isStarted()).isTrue();
                assertThat(registeredInstances()).as("released, the unit no longer keeps the caller's own instance id out of use")
                                                 .containsExactly(INSTANCE_ID);
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("the caller's own id takes every unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                    assertThat(fenceOf(kept.shard())).as("the released unit taken under a newer fence").isGreaterThan(kept.fence());
                    assertThat(deliveredAgain).as("the key's message delivered again while the first handler runs").containsExactly("ACC-1");
                });
                assertThat(Duration.ofNanos(System.nanoTime() - unsubscribedAt)).as("premise: all of that within the lease TTL, so no TTL freed the unit")
                                                                               .isLessThan(leaseTtl);

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(orderedMessagesLeft()).as("the new owner acknowledged the message").isZero());
            } finally {
                release.countDown();
            }
        }
    }

    private record UnitFence(int shard, long fence) {
    }

    private String ownerOf(int orderedUnit) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT owner FROM " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " WHERE queue_id = ? AND lane = 'ordered' AND shard = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setInt(2, orderedUnit);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    /**
     * Point an ordered unit's lease row at {@code owner} under {@code fence}, as an instance taking it would.
     */
    private void takeOrderedUnit(int orderedUnit, String owner, long fence) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("UPDATE " + ShardOwnedSchema.LEASE_TABLE
                                                                 + " SET owner = ?, fence = ? WHERE queue_id = ? AND lane = 'ordered' AND shard = ?")) {
            statement.setString(1, owner);
            statement.setLong(2, fence);
            statement.setShort(3, QUEUE_ID);
            statement.setInt(4, orderedUnit);
            assertThat(statement.executeUpdate()).as("premise: the unit's lease row was taken").isEqualTo(1);
        }
    }

    private UnitFence keptOrderedFence(String instanceId) throws SQLException {
        return queryForRow(dataSource, "Reading the ordered unit kept under " + instanceId,
                           "SELECT shard, fence FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'ordered' AND owner = ?",
                           resultSet -> new UnitFence(resultSet.getInt(1), resultSet.getLong(2)),
                           QUEUE_ID, instanceId);
    }

    private long fenceOf(int orderedUnit) throws SQLException {
        return queryForLong(dataSource, "Reading the fence of ordered unit " + orderedUnit,
                            "SELECT fence FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'ordered' AND shard = ?",
                            QUEUE_ID, orderedUnit);
    }

    /**
     * A {@code consume()} whose ordered lane's start fails once that lane has handed a key to the handler - a
     * {@link QueueObserver} throwing for one of its unit acquisitions, which a start tells last, after its units are
     * served - stops both lanes, and the ordered lane's stop keeps the unit whose key is still in its handler past
     * {@code shedGrace}. The failed {@code consume()} then holds its subscription index as an unsubscribe does, until the
     * key has left its handler: a {@code consume()} retried before then registers under the next instance id, which cannot
     * take the kept unit while the old instance is live. It used to give the index back at once, so the retry registered under the same
     * instance id, took the kept unit at once ({@code acquireLeaseGrant} accepts a row naming its own owner) and delivered
     * the key a second time while the first handler still ran it.
     */
    @Test
    void a_failed_consume_whose_ordered_lane_kept_a_unit_keeps_its_instance_id_out_of_use_while_the_key_is_in_its_handler() throws Exception {
        var leaseTtl            = Duration.ofSeconds(5);
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var deliveredAgain      = ConcurrentHashMap.<String>newKeySet();
        var acquisitionsTold    = new AtomicInteger();
        var failTheOrderedStart = new AtomicBoolean(true);
        var keyInHandlerAtFail  = new AtomicBoolean();
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    // The unordered lane starts first and tells its SHARD_COUNT acquisitions; the next is the ordered lane's
                    // first, told once its units are served.
                    if (acquired && failTheOrderedStart.get() && acquisitionsTold.incrementAndGet() == SHARD_COUNT + 1) {
                        try {
                            keyInHandlerAtFail.set(entered.await(20, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IllegalStateException("The observer refuses the ordered lane's first acquisition");
                    }
                }
            });
            try {
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThatThrownBy(() -> queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, ConsumerOptions.defaults())).as("premise: the ordered lane's start failed")
                                               .isInstanceOf(IllegalStateException.class)
                                               .hasMessageContaining("refuses the ordered lane's first acquisition");
                failTheOrderedStart.set(false);
                var failedAt = System.nanoTime();
                assertThat(keyInHandlerAtFail).as("premise: the key was in its handler when the ordered lane's start failed").isTrue();
                assertThat(queue.registeredConsumers()).as("premise: the failed consume() registered nothing").isEmpty();
                assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: the stop kept the unit whose key is still in its handler")
                                                           .isEqualTo(1);

                var retried = queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        deliveredAgain.add(key);
                    }
                }, ConsumerOptions.defaults());
                assertThat(retried.isStarted()).isTrue();
                Awaitility.await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(3))
                          .untilAsserted(() -> {
                              assertThat(deliveredAgain).as("the key in the old handler is not started again beside it").isEmpty();
                              assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("the kept unit still names the old instance id").isEqualTo(1);
                          });
                assertThat(registeredInstances()).as("the retried consume() registered under the next instance id")
                                                 .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
                assertThat(Duration.ofNanos(System.nanoTime() - failedAt)).as("premise: all of that within the lease TTL")
                                                                         .isLessThan(leaseTtl);
                assertThat(infos()).as("the queue says once why the instance id stays out of use, and until when")
                                   .filteredOn(info -> info.contains("keeps the instance id '" + INSTANCE_ID + "'"))
                                   .singleElement()
                                   .satisfies(info -> assertThat(info).contains("of a failed consume()",
                                                                                "out of use until the handler of the key it kept an ordered unit for has returned")
                                                                      .doesNotContain("out of use for "));
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * An unsubscribe whose lane's stop gave up waiting for its hand-back to the database - here the deregistration of the
     * instance id, blocked behind a row lock a test transaction holds on its membership row, as behind a database that does
     * not answer - keeps the instance id out of use until that hand-back has finished: the abandoned thread deletes the
     * id's membership row once it gets through, and a pair registering under the id before then could lose its row to it.
     * The next {@code consume()} meanwhile registers under the next instance id, and once the hand-back has finished the
     * id is free again - however long after a lease TTL that is, which is why the hold is not a lease TTL. It used to be
     * given back as soon as the lanes' stops had returned: the next {@code consume()} registered under the same id, here
     * waiting behind the same lock as the abandoned deregistration.
     */
    @Test
    void an_unsubscribe_whose_stop_gave_up_on_its_hand_back_keeps_its_instance_id_out_of_use_until_the_hand_back_finished() throws Exception {
        // A heartbeat every 20 s: none upserts the locked row while the test runs.
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), Duration.ofSeconds(60)));
             var locking = dataSource.getConnection()) {
            var first = consumeUnchecked(queue);
            awaitBothLanesHoldUnits(first);
            var lanes = queue.registeredConsumers();
            assertThat(lanes).as("premise: the first subscription's two lanes").hasSize(2);

            locking.setAutoCommit(false);
            try {
                assertThat(lockMembershipRowOf(locking, INSTANCE_ID)).as("premise: the test holds the subscription's membership row").isTrue();
                first.unsubscribe();
                assertThat(laneWarnings()).as("premise: a lane's stop gave up waiting for its hand-back")
                                          .anyMatch(warning -> warning.contains("gave up handing back queue"));
                assertThat(lanes).as("premise: that hand-back is still running").anyMatch(ShardOwnedQueue::handBackStillRunning);
                assertThat(registeredInstances()).as("premise: it has not deregistered the instance id yet").containsExactly(INSTANCE_ID);

                var next = CompletableFuture.supplyAsync(() -> consumeUnchecked(queue)).get(15, TimeUnit.SECONDS);
                assertThat(next.isStarted()).as("the next consume() did not wait behind the abandoned deregistration").isTrue();
                assertThat(registeredInstances()).as("the next consume() registered under the next instance id")
                                                 .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
                assertThat(infos()).as("the queue says once why the instance id stays out of use, and until when")
                                   .filteredOn(info -> info.contains("keeps the instance id '" + INSTANCE_ID + "'"))
                                   .singleElement()
                                   .satisfies(info -> assertThat(info).contains("of an unsubscribed subscription",
                                                                                "out of use until the hand-back of its lanes to the database has finished")
                                                                      .doesNotContain("out of use for "));
            } finally {
                locking.rollback();
            }

            Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(lanes).as("the abandoned hand-back finished").noneMatch(ShardOwnedQueue::handBackStillRunning);
                assertThat(registeredInstances()).as("it deregistered the old instance id, and left the next subscription's")
                                                 .containsExactly(INSTANCE_ID + "-1");
            });
            var third = consumeUnchecked(queue);
            assertThat(third.isStarted()).isTrue();
            assertThat(registeredInstances()).as("once the hand-back has finished the instance id is free again")
                                             .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
        }
    }

    /**
     * A subscription whose unordered lane could not release its shards as it stopped - a database error - and whose
     * ordered lane then stopped cleanly leaves no membership row: the ordered lane, stopping last, deletes it, so the
     * unreleased shards are free to another instance at once - all of them, to the only live instance. The ordered lane's
     * stop used to keep the row while a lease row of the other lane named the instance, which those unreleased shards did:
     * nobody served them, and the other instance, counting the stopped one as live, was held to a fair share of half the
     * shards and could take none of them until the row aged past the lease TTL.
     */
    @Test
    void a_subscription_whose_unordered_release_failed_leaves_no_membership_row_once_its_ordered_lane_stopped_cleanly() throws Exception {
        // A lease TTL far beyond the test, so nothing below is taken because the stopped instance aged out.
        var leaseTtl = Duration.ofSeconds(60);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            var first = consumeUnchecked(queue);
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(unorderedShardsOwnedBy(INSTANCE_ID)).as("premise: the only instance holds every shard")
                                                                                         .isEqualTo(SHARD_COUNT));
            awaitBothLanesHoldUnits(first);

            failUnorderedLeaseReleasesBy(INSTANCE_ID, true);
            try {
                first.unsubscribe();
                assertThat(laneWarnings()).as("premise: the unordered lane's release failed")
                                          .anyMatch(warning -> warning.contains("could not release its unordered shards on stop"));
                assertThat(unorderedShardsOwnedBy(INSTANCE_ID)).as("premise: every unordered shard still names the stopped instance")
                                                               .isEqualTo(SHARD_COUNT);
                assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: the ordered lane released its units").isZero();
                assertThat(registeredInstances()).as("the ordered lane, stopping last, deleted the subscription's membership row")
                                                 .isEmpty();
            } finally {
                failUnorderedLeaseReleasesBy(INSTANCE_ID, false);
            }

            try (var other = PostgresqlMessageQueue.builder()
                                                   .setDataSource(dataSource)
                                                   .setQueueId(QUEUE_ID)
                                                   .setShardCount(SHARD_COUNT)
                                                   .setInstanceId("survivor")
                                                   .setSettings(settingsWith(Duration.ofMillis(300), leaseTtl))
                                                   .build()) {
                var survivor = consumeUnchecked(other);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(survivor.unorderedShardsHeld())
                                  .as("another instance takes every shard the stopped one could not release, well within the lease TTL")
                                  .isEqualTo(SHARD_COUNT));
                assertThat(unorderedShardsOwnedBy(INSTANCE_ID)).isZero();
            }
        }
    }

    /**
     * Make every release of an unordered lease row held by {@code instanceId} fail, as a database error would, or stop
     * doing so. A trigger rather than a revoked grant: the test's role owns the table, and an owner keeps every privilege.
     * Another instance taking such a row over sets an owner, which the trigger lets through.
     */
    private void failUnorderedLeaseReleasesBy(String instanceId, boolean fail) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (fail) {
                statement.execute("CREATE OR REPLACE FUNCTION fail_unordered_lease_release_by() RETURNS trigger AS $$ BEGIN "
                                          + "IF NEW.lane = 'unordered' AND NEW.owner IS NULL AND OLD.owner = '" + instanceId + "' THEN "
                                          + "RAISE EXCEPTION 'simulated failure releasing unordered shard %', OLD.shard; END IF; "
                                          + "RETURN NEW; END $$ LANGUAGE plpgsql");
                statement.execute("CREATE TRIGGER fail_unordered_lease_release_by BEFORE UPDATE ON " + ShardOwnedSchema.LEASE_TABLE
                                          + " FOR EACH ROW EXECUTE FUNCTION fail_unordered_lease_release_by()");
            } else {
                statement.execute("DROP TRIGGER IF EXISTS fail_unordered_lease_release_by ON " + ShardOwnedSchema.LEASE_TABLE);
            }
        }
    }

    private int unorderedShardsOwnedBy(String instanceId) throws SQLException {
        return queryForInt(dataSource, "Counting the unordered lease rows held by " + instanceId,
                           "SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'unordered' AND owner = ?",
                           QUEUE_ID, instanceId);
    }

    private boolean lockMembershipRowOf(Connection connection, String instanceId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM " + ShardOwnedSchema.INSTANCE_TABLE
                                                                 + " WHERE queue_id = ? AND instance_id = ? FOR UPDATE")) {
            statement.setShort(1, QUEUE_ID);
            statement.setString(2, instanceId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
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

    /**
     * Another instance of the queue, as another process would run it: its own instance id.
     */
    private PostgresqlMessageQueue newQueue(String instanceId, ShardOwnerSettings settings) {
        return PostgresqlMessageQueue.builder()
                                     .setDataSource(dataSource)
                                     .setQueueId(QUEUE_ID)
                                     .setShardCount(SHARD_COUNT)
                                     .setInstanceId(instanceId)
                                     .setSettings(settings)
                                     .setLifecycleHook(PostgresqlMessageQueue.LifecycleHook.NONE)
                                     .build();
    }

    private static ShardOwnerSettings settingsWith(Duration shedGrace, Duration leaseTtl) {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(), Duration.ofMillis(1), Duration.ofMillis(2),
                                      Duration.ofMillis(300), Duration.ofMillis(100), 1_000, 8,
                                      Duration.ofMillis(50), Duration.ofSeconds(30), 2, shedGrace, leaseTtl, Duration.ofSeconds(60),
                                      ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT);
    }

    private int orderedMessagesLeft() throws SQLException {
        return queryForInt(dataSource, "Counting the ordered messages left in queue " + QUEUE_ID,
                           "SELECT count(*) FROM " + ShardOwnedSchema.ORDERED_TABLE + " WHERE queue_id = ?", QUEUE_ID);
    }

    private int orderedUnitsOwnedBy(String instanceId) throws SQLException {
        return queryForInt(dataSource, "Counting the ordered lease rows held by " + instanceId,
                           "SELECT count(*) FROM " + ShardOwnedSchema.LEASE_TABLE + " WHERE queue_id = ? AND lane = 'ordered' AND owner = ?",
                           QUEUE_ID, instanceId);
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

    private List<String> laneWarnings() {
        // The appender adds under its own monitor (AppenderBase.doAppend), from the stops' and the heartbeat's threads.
        synchronized (laneLogged) {
            return laneLogged.list.stream()
                                  .filter(event -> event.getLevel() == WARN)
                                  .map(ILoggingEvent::getFormattedMessage)
                                  .toList();
        }
    }

    private List<String> infos() {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == INFO)
                          .map(ILoggingEvent::getFormattedMessage)
                          .toList();
    }

    private List<String> laneInfos() {
        // The appender adds under its own monitor (AppenderBase.doAppend), from the stops' and the heartbeat's threads.
        synchronized (laneLogged) {
            return laneLogged.list.stream()
                                  .filter(event -> event.getLevel() == INFO)
                                  .map(ILoggingEvent::getFormattedMessage)
                                  .toList();
        }
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
        awaitQuietly(latch, Duration.ofSeconds(30));
    }

    private static void awaitQuietly(CountDownLatch latch, Duration atMost) {
        try {
            latch.await(atMost.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
