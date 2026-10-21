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
     * with a handler that stays in its handler longer than {@code leaseTtl}, and well inside the queue's
     * {@code stuckHandlerTimeout}: the restarted queue leaves the kept unit alone, and says it is held up in one WARN,
     * however many heartbeats skip the unit meanwhile. Throughout, {@code ShardOwnerMetrics.keptUnitsInHandler} reads one
     * while that handler runs and zero once it has returned. Before the bound existed the restarted queue waited for the
     * handler for as long as it ran, and nothing but a DEBUG line said so. The bound is far beyond the WARN and the
     * heartbeats watched after it, so a stop, a restart or a heartbeat slowed down on a loaded host does not normally carry
     * the test past it, and a run that does get past it fails the premise assertion rather than passing vacuously; past the
     * bound is
     * {@link #a_queue_restarted_while_a_key_outlasts_the_stuck_handler_timeout_in_the_handler_its_stop_kept_takes_the_unit_over}.
     */
    @Test
    void a_queue_restarted_while_a_key_is_in_the_handler_its_stop_kept_longer_than_the_lease_ttl_reports_the_unit_held_up_once_and_leaves_it_alone() throws Exception {
        // A heartbeat every second; the held-up WARN once the handler has been there 3 s. The bound, 20 s, is well past the
        // WARN and the three seconds of heartbeats watched after it.
        var leaseTtl            = Duration.ofSeconds(3);
        var stuckHandlerTimeout = Duration.ofSeconds(20);
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
                // Held up past leaseTtl, the unit is reported.
                var heldUp = "holds ordered unit " + kept.shard() + " on queue " + QUEUE_ID + " unserved";
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(laneWarnings()).as("the unit held up past leaseTtl is reported")
                                                                         .anyMatch(warning -> warning.contains(heldUp)));
                assertThat(laneWarnings()).filteredOn(warning -> warning.contains(heldUp))
                                          .singleElement()
                                          .satisfies(warning -> assertThat(warning).contains("[ACC-1]", "longer than the 3000 ms leaseTtl",
                                                                                             "stuckHandlerTimeout (PT20S)",
                                                                                             // The call the operator copies: the instance id it
                                                                                             // must pass, and the unit.
                                                                                             "releaseKeptOrderedUnit(\"" + INSTANCE_ID + "\", " + kept.shard() + ")",
                                                                                             "ordered-units/" + kept.shard() + "/release-kept?instanceId=" + INSTANCE_ID));
                // Several heartbeats skip the unit meanwhile: still one WARN, the unit left alone, the key not delivered again.
                Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6))
                          .untilAsserted(() -> {
                              assertThat(laneWarnings()).as("the held-up WARN is rate limited: one, though several heartbeats skipped the unit")
                                                        .filteredOn(warning -> warning.contains(heldUp))
                                                        .hasSize(1);
                              assertThat(entries).as("the key in the old handler is not delivered again").hasValue(1);
                              assertThat(fenceOf(kept.shard())).as("the kept unit is not taken").isEqualTo(kept.fence());
                              assertThat(subscription.orderedUnitsHeld()).as("every ordered unit but the kept one")
                                                                         .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1);
                          });
                // Loud, not vacuous, should a run ever get this far only past the bound.
                assertThat(Duration.ofNanos(System.nanoTime() - enteredAt)).as("premise: all of the above before the bound")
                                                                           .isLessThan(stuckHandlerTimeout);
                assertThat(laneWarnings()).as("the queue does not stop waiting before the bound")
                                          .noneMatch(warning -> warning.contains("stops waiting for ordered unit " + kept.shard()));
                assertThat(metrics.keptUnitsInHandler()).as("the first handler still runs").isEqualTo(1);

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> {
                              assertThat(metrics.keptUnitsInHandler()).as("the first handler has returned").isZero();
                              assertThat(fenceOf(kept.shard())).as("taken once handed back, under a newer fence").isGreaterThan(kept.fence());
                              assertThat(subscription.orderedUnitsHeld()).as("every ordered unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                              assertThat(orderedMessagesLeft()).as("the handled message acknowledged").isZero();
                          });
                assertThat(entries).as("handed back, not taken over: the handled message is not delivered again").hasValue(1);
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_queue_restarted_while_a_key_is_in_the_handler_its_stop_kept_an_ordered_unit_for_leaves_that_unit_alone}
     * with a handler that does not return within the queue's {@code stuckHandlerTimeout}. Past the bound the restarted
     * queue stops waiting: it takes the unit over under a new fence - on a heartbeat, or as it starts when the restart
     * comes after the bound - and delivers the key's message again while the first handler still runs it - the trade the
     * bound makes - and that handler's acknowledgement, once it returns, is refused. Throughout,
     * {@code ShardOwnerMetrics.keptUnitsInHandler} reads one while that handler runs and zero once it has returned. Without
     * the bound the restarted queue waited for the handler for as long as it ran. That the unit is left alone before the
     * bound is
     * {@link #a_queue_restarted_while_a_key_is_in_the_handler_its_stop_kept_longer_than_the_lease_ttl_reports_the_unit_held_up_once_and_leaves_it_alone},
     * and a start past it
     * {@link #a_queue_restarted_after_the_stuck_handler_timeout_passed_while_it_was_stopped_takes_the_kept_unit_over_as_it_starts_and_its_next_stop_deregisters}.
     */
    @Test
    void a_queue_restarted_while_a_key_outlasts_the_stuck_handler_timeout_in_the_handler_its_stop_kept_takes_the_unit_over() throws Exception {
        // A heartbeat every second; the takeover once the handler has been there 3 s. Nothing is asserted before the bound,
        // so how long the stop and the restart take does not matter.
        var leaseTtl            = Duration.ofSeconds(3);
        var stuckHandlerTimeout = Duration.ofSeconds(3);
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

                queue.stop();
                assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: the stop kept the unit whose key is still in its handler")
                                                           .isEqualTo(1);
                var kept = keptOrderedFence(INSTANCE_ID);
                assertThat(metrics.keptUnitsInHandler()).as("the kept unit whose key is in its handler").isEqualTo(1);

                queue.start();
                assertThat(subscription.isStarted()).as("restarted").isTrue();
                // Past the bound: taken over under a new fence, and the key delivered again beside the handler still running.
                Awaitility.await().atMost(Duration.ofSeconds(15))
                          .untilAsserted(() -> {
                              assertThat(fenceOf(kept.shard())).as("taken over under a newer fence").isGreaterThan(kept.fence());
                              assertThat(subscription.orderedUnitsHeld()).as("every ordered unit").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                              assertThat(entries).as("the key's message delivered again while the first handler runs").hasValue(2);
                          });
                assertThat(Duration.ofNanos(System.nanoTime() - enteredAt)).as("not before the bound").isGreaterThanOrEqualTo(stuckHandlerTimeout);
                assertThat(laneWarnings()).as("the takeover is announced once")
                                          .filteredOn(warning -> warning.contains("stops waiting for ordered unit " + kept.shard()))
                                          .singleElement()
                                          .satisfies(warning -> assertThat(warning).contains("[ACC-1]", "stuckHandlerTimeout (PT3S)"));
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
     * {@link #a_queue_restarted_while_a_key_outlasts_the_stuck_handler_timeout_in_the_handler_its_stop_kept_takes_the_unit_over},
     * with the bound passing while the queue is stopped: nothing reviews the kept unit then, so the start reviews it
     * before it leases anything, stops waiting for it - one WARN - and takes it over under a new fence as it leases the
     * rest, so every ordered unit is held as soon as {@code start()} returns, before the run's first heartbeat. A later
     * stop while that first handler still hangs deregisters the instance: nothing waits for the unit given up on, so
     * neither it nor its handler keeps the instance registered.
     */
    @Test
    void a_queue_restarted_after_the_stuck_handler_timeout_passed_while_it_was_stopped_takes_the_kept_unit_over_as_it_starts_and_its_next_stop_deregisters() throws Exception {
        // The first heartbeat of the restarted run comes a third of the lease TTL after its start - about 6.7 s - long after
        // the assertions on the start; and the membership row cannot age out while the queue is stopped.
        var leaseTtl            = Duration.ofSeconds(20);
        var stuckHandlerTimeout = Duration.ofSeconds(2);
        var entries             = new AtomicInteger();
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var settings            = settingsWith(Duration.ofMillis(300), leaseTtl).withStuckHandlerTimeout(stuckHandlerTimeout);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settings)) {
            try {
                var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                    // The first delivery of the key holds its handler for the whole test; a later one returns at once.
                    if (key != null && entries.incrementAndGet() == 1) {
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                var metrics = queue.registeredConsumers().getFirst().metrics();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var enteredAt = System.nanoTime();

                queue.stop();
                var kept = keptOrderedFence(INSTANCE_ID);
                assertThat(orderedUnitsOwnedBy(INSTANCE_ID)).as("premise: the stop kept the unit whose key is still in its handler")
                                                           .isEqualTo(1);
                assertThat(registeredInstances()).as("premise: the stop stays registered for the kept unit").containsExactly(INSTANCE_ID);

                // Stopped, the queue runs nothing that would notice the bound pass: wait on the clock it is measured by. Past
                // it from the handler's entry, it is past it from the key's dispatch, which came first.
                Awaitility.await().atMost(stuckHandlerTimeout.plusSeconds(5))
                          .until(() -> Duration.ofNanos(System.nanoTime() - enteredAt).compareTo(stuckHandlerTimeout) > 0);
                var stopsWaiting = "stops waiting for ordered unit " + kept.shard();
                assertThat(laneWarnings()).as("premise: nothing gave the unit up while the queue was stopped")
                                          .noneMatch(warning -> warning.contains(stopsWaiting));
                assertThat(fenceOf(kept.shard())).as("premise: nor took it").isEqualTo(kept.fence());
                // A release on request for another instance id touches nothing, not even the review that would give the
                // unit up now: no heartbeat runs it for a stopped queue, so done for that request it would be a side effect.
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID + "-other", kept.shard()).releases()).as("asked for another instance id, nothing is released")
                                                                                                       .isEmpty();
                assertThat(laneWarnings()).as("nor is the unit given up").noneMatch(warning -> warning.contains(stopsWaiting));
                assertThat(fenceOf(kept.shard())).as("nor taken").isEqualTo(kept.fence());

                queue.start();
                // As soon as start() returns: the start took the unit over, not a heartbeat.
                assertThat(fenceOf(kept.shard())).as("the start takes the kept unit over under a newer fence").isGreaterThan(kept.fence());
                assertThat(ownerOf(kept.shard())).as("the start, not another instance, took it").isEqualTo(INSTANCE_ID);
                assertThat(subscription.orderedUnitsHeld()).as("every ordered unit, the kept one included").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                assertThat(laneWarnings()).as("the start says it stops waiting, once")
                                          .filteredOn(warning -> warning.contains(stopsWaiting))
                                          .singleElement()
                                          .satisfies(warning -> assertThat(warning).contains("[ACC-1]", "stuckHandlerTimeout (PT2S)"));
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> {
                              assertThat(entries).as("the key's message delivered again while the first handler runs").hasValue(2);
                              assertThat(orderedMessagesLeft()).as("the new owner acknowledged it").isZero();
                          });

                // Stopped again while the first handler still hangs: nothing waits for the unit given up on.
                queue.stop();
                assertThat(metrics.keptUnitsInHandler()).as("premise: the first handler still runs").isEqualTo(1);
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(registeredInstances()).as("the later stop deregistered the instance").isEmpty());
                assertThat(laneInfos()).as("it does not stay registered for the unit given up on")
                                       .noneMatch(info -> info.contains("stays registered on queue " + QUEUE_ID));
                assertThat(laneWarnings()).as("given up once, however many reviews ran since")
                                          .filteredOn(warning -> warning.contains(stopsWaiting))
                                          .hasSize(1);

                release.countDown();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(metrics.keptUnitsInHandler()).as("the first handler has returned").isZero());
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
        // Long enough that the unsubscribe and the second consume() - a few hundred milliseconds on a quiet host - land
        // before it on a loaded one too.
        var stuckHandlerTimeout = Duration.ofSeconds(8);
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

                // No lane of the first subscription runs, so nothing logs or changes as the bound passes - the next
                // consume() reviews the kept unit - and asked here, the review would be the test's, not that consume()'s.
                // So wait on the clock the bound is measured by: past it from the handler's entry, it is past it from the
                // key's dispatch, which came first.
                Awaitility.await().atMost(stuckHandlerTimeout.plusSeconds(5))
                          .until(() -> Duration.ofNanos(System.nanoTime() - enteredAt).compareTo(stuckHandlerTimeout) > 0);
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
     * {@link #a_subscription_whose_kept_unit_outlasts_the_stuck_handler_timeout_frees_its_instance_id_for_a_subscription_that_takes_the_unit_over}
     * with the ordered lane's stop flush hung as well - its budget thread held as it asks for a connection, as on a
     * connection that does not answer - and still hung once the bound has passed. A stop that kept a unit never deletes the
     * instance's membership row, so its hand-back does not hold the instance id: past the bound the kept unit is given up,
     * and the next {@code consume()} registers under the caller's own id and takes the unit over while that hand-back still
     * hangs. The id used to stay out of use for as long as the flush hung, every {@code consume()} meanwhile registering
     * under another instance id.
     */
    @Test
    void an_unsubscribed_subscription_whose_stop_flush_hangs_frees_its_instance_id_once_its_kept_unit_is_given_up() throws Exception {
        // Far beyond the test, so the unsubscribed instance's membership row cannot age out and let another id take the unit.
        var leaseTtl            = Duration.ofSeconds(20);
        var stuckHandlerTimeout = Duration.ofSeconds(8);
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var deliveredAgain      = ConcurrentHashMap.<String>newKeySet();
        MessageHandler recording = (messageId, key, payload, payloadType) -> {
            if (key != null) {
                deliveredAgain.add(key);
            }
        };
        // A grace of 3 s: the ordered lane's stop drains that long before its budget thread starts, time enough for the test
        // to hold that thread, and not the unordered lane's, which hands back first.
        var settings = settingsWith(Duration.ofSeconds(3), leaseTtl).withStuckHandlerTimeout(stuckHandlerTimeout);
        var holding  = new ShardOwnedLifecycleIT.FlushHoldingDataSource(dataSource, ShardOwnedLifecycleIT.FlushHoldingDataSource.STOP_BUDGET_THREAD);
        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(holding)
                                               .setQueueId(QUEUE_ID)
                                               .setShardCount(SHARD_COUNT)
                                               .setInstanceId(INSTANCE_ID)
                                               .setSettings(settings)
                                               .setLifecycleHook(PostgresqlMessageQueue.LifecycleHook.NONE)
                                               .build()) {
            try {
                var first = queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                }, ConsumerOptions.defaults());
                var lanes = queue.registeredConsumers();
                assertThat(lanes).as("premise: the first subscription's two lanes").hasSize(2);
                var unordered = lanes.get(0);
                var ordered   = lanes.get(1);
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var enteredAt = System.nanoTime();

                var unsubscribing = CompletableFuture.runAsync(first::unsubscribe);
                // The unordered lane stops first; once it has handed back and the ordered lane's stop is draining, hold the
                // next stop budget thread to ask for a connection: the ordered lane's.
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .until(() -> !unordered.isStarted() && !ordered.isStarted() && !unordered.handBackStillRunning());
                holding.holdConnections();
                unsubscribing.get(20, TimeUnit.SECONDS);
                // A unit still waiting for its stop's flush is given up once kept longer than the bound, timed from when the
                // stop kept it, which came before the unsubscribe returned.
                var unsubscribedAt = System.nanoTime();
                assertThat(holding.awaitConnectionHeld(Duration.ofSeconds(10))).as("premise: the ordered lane's stop flush hangs").isTrue();
                assertThat(ordered.handBackStillRunning()).as("premise: its stop gave up waiting for that flush").isTrue();
                assertThat(ordered.keptOrderedUnitAwaited()).as("premise: that stop kept the busy unit").isTrue();
                assertThat(ordered.handBackMayStillDeregister()).as("a hand-back whose stop kept a unit never deregisters").isFalse();
                var keptUnit  = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var keptFence = fenceOf(keptUnit);
                assertThat(infos()).as("the hold names the kept unit alone, not the hung hand-back")
                                   .filteredOn(info -> info.contains("keeps the instance id '" + INSTANCE_ID + "'"))
                                   .singleElement()
                                   .satisfies(info -> assertThat(info).contains("it kept an ordered unit whose key was still in a handler")
                                                                      .doesNotContain("hand-back of its lanes to the database"));

                var second = queue.consume(recording, ConsumerOptions.defaults());
                assertThat(Duration.ofNanos(System.nanoTime() - enteredAt)).as("premise: before the bound").isLessThan(stuckHandlerTimeout);
                assertThat(registeredInstances()).as("before the bound the instance id is out of use")
                                                 .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
                second.unsubscribe();

                // Past the bound from when the stop kept the unit; the next consume() reviews the kept unit and gives it up.
                Awaitility.await().atMost(stuckHandlerTimeout.plusSeconds(5))
                          .until(() -> Duration.ofNanos(System.nanoTime() - unsubscribedAt).compareTo(stuckHandlerTimeout) > 0);
                var third = queue.consume(recording, ConsumerOptions.defaults());
                assertThat(third.isStarted()).isTrue();
                assertThat(ordered.handBackStillRunning()).as("premise: the stop's flush still hangs").isTrue();
                assertThat(laneWarnings()).as("premise: the lane gave the unit up, its stop's flush still running")
                                          .anyMatch(warning -> warning.contains("stops waiting for ordered unit " + keptUnit)
                                                  && warning.contains("still not finished flushing"));
                assertThat(registeredInstances()).as("past the bound the caller's own instance id is free again, the flush hung or not")
                                                 .containsExactly(INSTANCE_ID);
                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(fenceOf(keptUnit)).as("the kept unit taken over under a newer fence").isGreaterThan(keptFence);
                    assertThat(deliveredAgain).as("the key's message delivered again while the first handler runs").containsExactly("ACC-1");
                });
            } finally {
                release.countDown();
                holding.letConnect();
                holding.letAcknowledge();
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
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, unitOfTheKey).releases()).as("nothing is kept while the queue runs").isEmpty();
                // The SPI refuses a request naming no instance id itself, not only the admin API in front of it.
                assertThatThrownBy(() -> queue.releaseKeptOrderedUnit(" ", unitOfTheKey)).as("a blank instance id is refused")
                                                                                       .isInstanceOf(IllegalArgumentException.class)
                                                                                       .hasMessageContaining("No instanceId provided");
                assertThatThrownBy(() -> queue.releaseKeptOrderedUnit(null, unitOfTheKey)).as("no instance id is refused")
                                                                                        .isInstanceOf(IllegalArgumentException.class)
                                                                                        .hasMessageContaining("No instanceId provided");

                queue.stop();
                var kept = keptOrderedFence(INSTANCE_ID);
                assertThat(kept.shard()).as("premise: the stop kept the unit of the key in its handler").isEqualTo(unitOfTheKey);
                queue.start();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(subscription.orderedUnitsHeld()).as("premise: the restart leaves the kept unit alone")
                                                                                          .isEqualTo(ShardOwnedSchema.ORDERED_UNITS - 1));

                var notKept      = (kept.shard() + 1) % ShardOwnedSchema.ORDERED_UNITS;
                var notKeptFence = fenceOf(notKept);
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, notKept).releases()).as("a unit no stop kept is not released").isEmpty();
                assertThat(ownerOf(notKept)).as("and keeps its owner").isEqualTo(INSTANCE_ID);
                assertThat(fenceOf(notKept)).as("and its fence").isEqualTo(notKeptFence);

                // Asked until queue.stop()'s budget thread has flushed the kept unit: that stop goes on without the thread
                // after SHUTDOWN_BUDGET, and until it has flushed the release answers BUSY.
                var releases = Awaitility.await().atMost(Duration.ofSeconds(5))
                                         .until(() -> queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases(),
                                                answer -> answer.stream().noneMatch(entry -> entry.outcome() == KeptUnitRelease.Outcome.BUSY));
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
                // The release charged the delivery in the held handler as an attempt; the restarted run, finding the row
                // released, charged nothing.
                assertThat(metrics.takeoverAttemptBumps.sum()).as("the release charged the message in the held handler once").isEqualTo(1);
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases()).as("released once: the unit is no longer kept").isEmpty();
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
     * A message whose handler hangs on every delivery reaches the dead-letter table once its unit has been released on
     * request ({@link MessageQueue#releaseKeptOrderedUnit}) as often as its {@code maxAttempts}: each release charges the
     * delivery in the hung handler as an attempt, under the fence the unit was kept under, and the release that brings it to
     * the limit dead-letters it, so the key is not delivered again. Before, the release charged nothing, nor did the next
     * owner, finding the row released, and a hung handler records no attempt: the message was handed on with its count
     * unchanged and delivered again after every release, never dead-lettered, each cycle leaving one more handler hung. The
     * shed and the dead-pump hand-back past {@code stuckHandlerTimeout} charge the same way
     * ({@code ShardOwnedOrderedRebalanceIT}, {@code ShardOwnedDeadPumpHandBackIT}).
     */
    @Test
    void a_message_whose_handler_hangs_on_every_delivery_is_dead_lettered_once_releases_on_request_have_charged_its_attempts() throws Exception {
        // A heartbeat every second; the bound stays at its 15-minute default, far beyond the test.
        var leaseTtl = Duration.ofSeconds(3);
        var entries  = new AtomicInteger();
        var entered  = new Semaphore(0);
        var release  = new CountDownLatch(1);
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                    // Every delivery of the key hangs, until the test ends.
                    if (key != null) {
                        entries.incrementAndGet();
                        entered.release();
                        awaitQuietly(release, Duration.ofSeconds(90));
                    }
                }, new ConsumerOptions(8, Integer.MAX_VALUE, 2, RetryBackoff.fixed(Duration.ofMillis(100))));
                var metrics = queue.registeredConsumers().getFirst().metrics();
                queue.enqueue(Message.ordered("hangs".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                var unitOfTheKey = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);

                for (var delivery = 1; delivery <= 2; delivery++) {
                    assertThat(entered.tryAcquire(20, TimeUnit.SECONDS)).as("premise: delivery " + delivery + " is in its handler").isTrue();
                    queue.stop();
                    assertThat(keptOrderedFence(INSTANCE_ID).shard()).as("premise: the stop kept the unit of the key in its handler")
                                                                      .isEqualTo(unitOfTheKey);
                    queue.start();
                    // Asked until queue.stop()'s budget thread has flushed the kept unit: until then the release answers BUSY.
                    var releases = Awaitility.await().atMost(Duration.ofSeconds(5))
                                             .until(() -> queue.releaseKeptOrderedUnit(INSTANCE_ID, unitOfTheKey).releases(),
                                                    answer -> answer.stream().noneMatch(entry -> entry.outcome() == KeptUnitRelease.Outcome.BUSY));
                    assertThat(releases).as("release " + delivery).singleElement().satisfies(released -> {
                        assertThat(released.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                        assertThat(released.keysInHandler()).containsExactly("ACC-1");
                    });
                    assertThat(metrics.takeoverAttemptBumps.sum()).as("each release charges the delivery in the hung handler once")
                                                                  .isEqualTo(delivery);
                }

                Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(queue.deadLetters(0, 10)).as("dead-lettered by the release that brought it to maxAttempts").singleElement().satisfies(deadLetter -> {
                        assertThat(deadLetter.key()).isEqualTo("ACC-1");
                        assertThat(deadLetter.attempts()).isEqualTo(2);
                        assertThat(deadLetter.lastError()).isEqualTo(ShardOwnedStorage.EXHAUSTED_IN_A_RELEASED_HANDLER);
                        assertThat(deadLetter.neverDelivered()).isFalse();
                    });
                    assertThat(orderedMessagesLeft()).as("nothing left to deliver").isZero();
                    assertThat(subscription.orderedUnitsHeld()).as("the released unit is taken again").isEqualTo(ShardOwnedSchema.ORDERED_UNITS);
                });
                Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(entries).as("not delivered a third time").hasValue(2));
                assertThat(metrics.deadLettered.sum()).as("counted once").isEqualTo(1);
                assertThat(laneWarnings()).as("the second release names the dead letter")
                                          .filteredOn(warning -> warning.contains("released ordered unit " + unitOfTheKey + " on queue " + QUEUE_ID + " on request"))
                                          .hasSize(2)
                                          .last()
                                          .satisfies(warning -> assertThat(warning).contains("1 message(s) in those handlers were charged an attempt",
                                                                                             "1 dead-lettered at their attempt limit [ACC-1]"));
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
                                         .until(() -> queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases(),
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
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases()).as("asked again: the unit is no longer kept").isEmpty();
                assertThat(ownerOf(kept.shard())).isEqualTo("other");
                assertThat(fenceOf(kept.shard())).isEqualTo(kept.fence() + 1);
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * A kept unit whose handler returns while a release on request is under way - after the release was asked for, before
     * it flushes the unit's acknowledgements: that flush acknowledges the handler's message, so nobody delivers it again,
     * and the release says so - {@link KeptUnitRelease.Outcome#RELEASED} naming no key, the INFO of an ordinary hand-back,
     * no WARN. It read the keys in a handler before it claimed the unit and flushed, and reported the returned key as one
     * whose message the next owner delivers again, possibly in two handlers at once, when it would not.
     */
    @Test
    void a_kept_unit_whose_handler_returns_before_the_release_on_request_flushes_is_reported_with_no_key_in_a_handler() throws Exception {
        var leaseTtl     = Duration.ofSeconds(3);
        var entries      = new AtomicInteger();
        var entered      = new CountDownLatch(1);
        var release      = new CountDownLatch(1);
        var onConnection = new RunBeforeConnectionDataSource(dataSource);
        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(onConnection)
                                               .setQueueId(QUEUE_ID)
                                               .setShardCount(SHARD_COUNT)
                                               .setInstanceId(INSTANCE_ID)
                                               .setSettings(settingsWith(Duration.ofMillis(300), leaseTtl))
                                               .setLifecycleHook(PostgresqlMessageQueue.LifecycleHook.NONE)
                                               .build()) {
            try {
                queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entries.incrementAndGet();
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                var metrics = queue.registeredConsumers().getFirst().metrics();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                // Stopped, and not started again: nothing but the release below acknowledges the message.
                queue.stop();
                var kept = keptOrderedFence(INSTANCE_ID);
                assertThat(orderedMessagesLeft()).as("premise: the message in the handler is not acknowledged").isEqualTo(1);

                // The release's first connection - its flush's, under its claim - waits for the handler to return first.
                var inReleaseFlush = new AtomicBoolean();
                onConnection.runOnce(Thread.currentThread(), () -> {
                    inReleaseFlush.set(inTheFlushOfAReleaseOnRequest());
                    release.countDown();
                    Awaitility.await().atMost(Duration.ofSeconds(10))
                              .untilAsserted(() -> assertThat(metrics.keptUnitsInHandler()).as("premise: the handler has returned").isZero());
                });
                var releases = Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(5))
                                         .until(() -> queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases(),
                                                answer -> answer.stream().noneMatch(entry -> entry.outcome() == KeptUnitRelease.Outcome.BUSY));
                assertThat(inReleaseFlush).as("premise: the handler returned inside the release's flush, under its claim").isTrue();
                assertThat(releases).as("the release names no key in a handler").singleElement().satisfies(answer -> {
                    assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                    assertThat(answer.keysInHandler()).isEmpty();
                    assertThat(answer.longestInHandler()).isZero();
                });
                assertThat(orderedMessagesLeft()).as("the release's flush acknowledged the handled message").isZero();
                assertThat(ownerOf(kept.shard())).as("the row is released").isNull();
                assertThat(laneWarnings()).as("no WARN of a message delivered again")
                                          .noneMatch(warning -> warning.contains("released ordered unit " + kept.shard() + " on queue " + QUEUE_ID));
                assertThat(laneInfos()).as("the INFO of an ordinary hand-back")
                                       .anyMatch(info -> info.contains("handed back ordered unit " + kept.shard() + " on queue " + QUEUE_ID + " on request"));
                assertThat(entries).as("delivered once").hasValue(1);
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * A kept unit with a key whose handler returns while the release on request's flush is writing - after that flush took
     * its batch of acknowledgements, so the handler's acknowledgement is not in it: the release names that key with the one
     * still hung, and the WARN says both their messages are delivered again, which they are, as neither is acknowledged.
     * The release read the keys after its flush, so that key went unnamed: the answer and the log said its message was
     * not delivered again, and the next owner delivered it all the same. A third key's handler, returned before the
     * release, had its acknowledgement flushed by it and is not named.
     */
    @Test
    void a_kept_unit_whose_handler_returns_while_the_release_on_request_flushes_names_that_key_whose_acknowledgement_it_did_not_flush() throws Exception {
        var leaseTtl      = Duration.ofSeconds(3);
        var hung          = "ACC-1";
        var unit          = ShardOwnedSchema.unitForKey(hung, ShardOwnedSchema.ORDERED_UNITS);
        var sameUnit      = java.util.stream.IntStream.range(2, 100_000).mapToObj(i -> "ACC-" + i)
                                                      .filter(key -> ShardOwnedSchema.unitForKey(key, ShardOwnedSchema.ORDERED_UNITS) == unit)
                                                      .limit(2)
                                                      .toList();
        var returnedBefore = sameUnit.get(0);
        var returnsDuring  = sameUnit.get(1);
        var releases       = Map.of(hung, new CountDownLatch(1), returnedBefore, new CountDownLatch(1), returnsDuring, new CountDownLatch(1));
        var entered        = new CountDownLatch(3);
        var onConnection   = new RunBeforeConnectionDataSource(dataSource);
        try (var queue = PostgresqlMessageQueue.builder()
                                               .setDataSource(onConnection)
                                               .setQueueId(QUEUE_ID)
                                               .setShardCount(SHARD_COUNT)
                                               .setInstanceId(INSTANCE_ID)
                                               .setSettings(settingsWith(Duration.ofMillis(300), leaseTtl))
                                               .setLifecycleHook(PostgresqlMessageQueue.LifecycleHook.NONE)
                                               .build()) {
            try {
                queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(releases.get(key), Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                for (var key : List.of(hung, returnedBefore, returnsDuring)) {
                    queue.enqueue(Message.ordered(key.getBytes(StandardCharsets.UTF_8), 1, key, 0));
                }
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the three keys of one unit are in their handlers").isTrue();

                // Stopped, and not started again: nothing but the release below acknowledges a message.
                queue.stop();
                var kept = keptOrderedFence(INSTANCE_ID);
                assertThat(kept.shard()).as("premise: the stop kept the unit of the keys in their handlers").isEqualTo(unit);
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(queue.registeredConsumers()).as("premise: the stop's flush has returned")
                                                                                      .noneMatch(ShardOwnedQueue::handBackStillRunning));
                // Returned after the stop's flush: its acknowledgement waits for the release's flush.
                releases.get(returnedBefore).countDown();
                Awaitility.await().atMost(Duration.ofSeconds(10))
                          .untilAsserted(() -> assertThat(keysInHandlerOfKeptUnit(queue, unit)).as("premise: one handler has returned").isEqualTo(2));
                assertThat(orderedMessagesLeft()).as("premise: no message is acknowledged yet").isEqualTo(3);

                // The release's flush writes only once it has taken its batch: the handler returns while that write runs.
                var inReleaseFlush = new AtomicBoolean();
                onConnection.runOnceBeforeStatement(Thread.currentThread(), "DELETE FROM " + ShardOwnedSchema.ORDERED_TABLE, () -> {
                    inReleaseFlush.set(inTheFlushOfAReleaseOnRequest());
                    releases.get(returnsDuring).countDown();
                    Awaitility.await().atMost(Duration.ofSeconds(10))
                              .untilAsserted(() -> assertThat(keysInHandlerOfKeptUnit(queue, unit)).as("premise: the handler has returned").isEqualTo(1));
                });
                var answer = Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(5))
                                       .until(() -> queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases(),
                                              entries -> entries.stream().noneMatch(entry -> entry.outcome() == KeptUnitRelease.Outcome.BUSY));
                assertThat(inReleaseFlush).as("premise: the handler returned inside the release's flush, under its claim").isTrue();
                assertThat(answer).as("the release names every key whose acknowledgement it did not flush").singleElement().satisfies(released -> {
                    assertThat(released.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                    assertThat(released.keysInHandler()).containsExactlyInAnyOrder(hung, returnsDuring);
                });
                assertThat(laneWarnings()).as("the WARN names both keys")
                                          .filteredOn(warning -> warning.contains("released ordered unit " + kept.shard() + " on queue " + QUEUE_ID + " on request"))
                                          .singleElement()
                                          .satisfies(warning -> assertThat(warning).contains(hung, returnsDuring).doesNotContain(returnedBefore));
                assertThat(orderedMessagesLeft()).as("only the message of the key returned before the release is acknowledged").isEqualTo(2);
                assertThat(ownerOf(kept.shard())).as("the row is released").isNull();
            } finally {
                releases.values().forEach(CountDownLatch::countDown);
            }
        }
    }

    /**
     * How many keys of the owners of {@code unit} that a stop of {@code queue} kept are in a handler.
     */
    @SuppressWarnings("unchecked")
    private static int keysInHandlerOfKeptUnit(PostgresqlMessageQueue queue, int unit) throws ReflectiveOperationException {
        var field = ShardOwnedQueue.class.getDeclaredField("keptOrderedOwners");
        field.setAccessible(true);
        var inHandler = 0;
        for (var lane : queue.registeredConsumers()) {
            for (var kept : (List<ShardOwnedQueue.KeptUnit>) field.get(lane)) {
                if (kept.owner().shard() == unit) {
                    inHandler += kept.owner().inFlight();
                }
            }
        }
        return inHandler;
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
                                         .until(() -> queue.releaseKeptOrderedUnit(INSTANCE_ID, keptAgain.shard()).releases(),
                                                answer -> answer.stream().noneMatch(entry -> entry.outcome() == KeptUnitRelease.Outcome.BUSY));
                assertThat(releases).as("the entry that held the row is released").singleElement().satisfies(answer -> {
                    assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                    assertThat(answer.keysInHandler()).containsExactly("ACC-1");
                });
                assertThat(ownerOf(keptAgain.shard())).as("the row is released").isNull();
                assertThat(fenceOf(keptAgain.shard())).as("a release does not bump the fence").isEqualTo(keptAgain.fence());
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, keptAgain.shard()).releases()).as("asked again: the unit is no longer kept").isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * A release on request whose release of the kept unit's lease row the database refuses: with no lane of the queue
     * releasing anything, the request fails with that {@link SQLException}, and the unit is left as it was - kept, its row
     * still naming the instance under the fence it was kept under, no release reported. Asked again once the database lets
     * the release through, it is released. A queue that swallowed the failure answered an empty list, as from an instance
     * that keeps no such unit, for a unit that was still kept; a lane that dropped its kept entry on the failure left the
     * row naming the instance with nothing left to release it on request. The message in the handler is charged one
     * attempt, by the first request, whose charge committed before its release was refused, and not again by the second.
     */
    @Test
    void a_kept_unit_whose_release_on_request_the_database_refuses_is_left_kept_and_the_request_fails() throws Exception {
        // Far beyond the test, so the stopped instance's membership row cannot age out and let another id take the unit.
        var leaseTtl = Duration.ofSeconds(20);
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        var queueIds = new short[]{QUEUE_ID};
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                var lanes = queue.registeredConsumers();
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                // Stopped, and not started again: nothing but the requests below releases the unit.
                queue.stop();
                var kept = keptOrderedFence(INSTANCE_ID);
                // queue.stop() goes on without its budget thread after SHUTDOWN_BUDGET, and until that thread has flushed the
                // kept unit a release on request answers BUSY rather than attempting the release refused below.
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(lanes).as("premise: queue.stop()'s budget thread has flushed the kept unit")
                                                                .noneMatch(ShardOwnedQueue::handBackStillRunning));
                LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, queueIds, "ordered", kept.shard(), true);
                try {
                    assertThatThrownBy(() -> queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases())
                            .as("the request fails with the lane's failure, as it is")
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("simulated failure releasing")
                            .satisfies(failure -> assertThat(failure.getSuppressed()).as("nothing added to it").isEmpty());
                    assertThat(LeaseReleaseRefusals.releasesRefused(dataSource, QUEUE_ID, kept.shard()))
                            .as("premise: the release was attempted and refused by the database").isEqualTo(1);
                } finally {
                    LeaseReleaseRefusals.failLeaseReleasesOf(dataSource, queueIds, "ordered", kept.shard(), false);
                }
                // The charge before the release committed on its own connection, before the release was refused.
                assertThat(lanes.getFirst().metrics().takeoverAttemptBumps.sum()).as("premise: the refused request charged the message in the handler")
                                                                                   .isEqualTo(1);
                assertThat(storedAttemptsOfOrderedKey("ACC-1")).as("premise: the charge is stored").isEqualTo(1);
                assertThat(ownerOf(kept.shard())).as("the row still names the instance").isEqualTo(INSTANCE_ID);
                assertThat(fenceOf(kept.shard())).as("under the fence it was kept under").isEqualTo(kept.fence());
                assertThat(lanes).as("the unit is still kept").anyMatch(ShardOwnedQueue::keptOrderedUnitNotHandedBack);
                assertThat(laneWarnings()).as("no release is reported")
                                          .noneMatch(warning -> warning.contains("released ordered unit " + kept.shard() + " on queue " + QUEUE_ID));

                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases()).as("asked again, with the database letting it through, the unit is released")
                                                                      .singleElement()
                                                                      .satisfies(released -> {
                                                                          assertThat(released.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                                                                          assertThat(released.instanceId()).isEqualTo(INSTANCE_ID);
                                                                          assertThat(released.keysInHandler()).containsExactly("ACC-1");
                                                                      });
                assertThat(ownerOf(kept.shard())).as("the row is released").isNull();
                // The retried release charges nothing again: the charge is made once per owner. Charged on every request, the
                // message would reach the dead-letter table after fewer hangs than maxAttempts.
                assertThat(lanes.getFirst().metrics().takeoverAttemptBumps.sum()).as("the retried release did not charge again").isEqualTo(1);
                assertThat(storedAttemptsOfOrderedKey("ACC-1")).as("the message, never acknowledged, is stored with the one charge").isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * The stored attempt count of the ordered message of {@code key} on this queue: the test enqueues one per key.
     */
    private int storedAttemptsOfOrderedKey(String key) throws SQLException {
        return queryForInt(dataSource, "Reading the attempts of the ordered message of key " + key,
                           "SELECT attempts FROM " + ShardOwnedSchema.ORDERED_TABLE + " WHERE queue_id = ? AND msg_key = ?", QUEUE_ID, key);
    }

    /**
     * Two subscriptions of the queue keeping the same ordered unit - an unsubscribed one, whose membership row aged out so
     * that the next subscription took the unit over under a newer fence, and that next one, stopped while the key's message
     * delivered again is in its handler too - and a release on request naming one instance id. Only the subscription
     * running under that id is touched: asked for the unsubscribed one's id, the request finds that one's row taken and
     * leaves the unit the other kept, its row and its fence alone; asked for the other's, it releases that one. Both
     * answers name the instance ids this process has for the queue. Before the request named an instance id, asking for
     * the unit the operator had diagnosed under one subscription released it under whichever subscription of this process
     * had kept a unit of that number.
     */
    @Test
    void a_release_on_request_touches_only_the_subscription_running_under_the_instance_id_asked_for() throws Exception {
        // A heartbeat every second, and the unsubscribed instance's membership row aged out 3 s after its last one.
        var leaseTtl = Duration.ofSeconds(3);
        var entries  = new AtomicInteger();
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        MessageHandler holding = (messageId, key, payload, payloadType) -> {
            // Every delivery of the key holds its handler: the first, and the one the takeover delivers again.
            if (key != null) {
                entries.incrementAndGet();
                entered.countDown();
                awaitQuietly(release, Duration.ofSeconds(60));
            }
        };
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                var first = queue.consume(holding, ConsumerOptions.defaults());
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();

                first.unsubscribe();
                var keptFirst = keptOrderedFence(INSTANCE_ID);
                var second    = queue.consume(holding, ConsumerOptions.defaults());
                assertThat(registeredInstances()).as("premise: the kept unit keeps the instance id out of use")
                                                 .contains(INSTANCE_ID + "-1");
                Awaitility.await().atMost(Duration.ofSeconds(20))
                          .untilAsserted(() -> {
                              assertThat(ownerOf(keptFirst.shard())).as("premise: the next subscription took the unit over").isEqualTo(INSTANCE_ID + "-1");
                              assertThat(fenceOf(keptFirst.shard())).as("premise: under a newer fence").isGreaterThan(keptFirst.fence());
                              assertThat(entries).as("premise: the key's message delivered again, and held too").hasValue(2);
                          });

                second.stop();
                var keptAgain = keptOrderedFence(INSTANCE_ID + "-1");
                assertThat(keptAgain.shard()).as("premise: the second subscription's stop kept the same unit").isEqualTo(keptFirst.shard());
                // second.stop() goes on without its budget thread after SHUTDOWN_BUDGET, and until that thread has flushed the
                // kept unit the second subscription answers BUSY. This waits on the second subscription's lanes only:
                // registeredConsumers() no longer lists first's.
                var secondLanes = queue.registeredConsumers();
                Awaitility.await().atMost(Duration.ofSeconds(5))
                          .untilAsserted(() -> assertThat(secondLanes).as("premise: second.stop()'s budget thread has flushed the kept unit")
                                                                      .noneMatch(ShardOwnedQueue::handBackStillRunning));
                // The second subscription's stop WARN names the suffixed id it runs under, the one the operator must pass.
                assertThat(laneWarnings()).as("the stop that kept the unit names its own instance id in the release call")
                                          .filteredOn(warning -> warning.startsWith("Instance " + INSTANCE_ID + "-1 is keeping "))
                                          .singleElement()
                                          .satisfies(warning -> assertThat(warning).contains("releaseKeptOrderedUnit(\"" + INSTANCE_ID + "-1\", unit)",
                                                                                             "release-kept?instanceId=" + INSTANCE_ID + "-1,"));

                var askedForTheFirst = queue.releaseKeptOrderedUnit(INSTANCE_ID, keptAgain.shard());
                assertThat(askedForTheFirst.answeredBy()).as("the answer names the instance ids this process has for the queue")
                                                         .containsExactly(INSTANCE_ID, INSTANCE_ID + "-1");
                assertThat(askedForTheFirst.releases()).as("only the subscription under the id asked for answers: its row was taken")
                                                       .singleElement()
                                                       .satisfies(answer -> {
                                                           assertThat(answer.outcome()).isEqualTo(KeptUnitRelease.Outcome.ALREADY_TAKEN);
                                                           assertThat(answer.instanceId()).isEqualTo(INSTANCE_ID);
                                                       });
                assertThat(ownerOf(keptAgain.shard())).as("the unit the other subscription kept keeps its row").isEqualTo(INSTANCE_ID + "-1");
                assertThat(fenceOf(keptAgain.shard())).as("and its fence").isEqualTo(keptAgain.fence());
                assertThat(secondLanes).as("and is still kept").anyMatch(ShardOwnedQueue::keptOrderedUnitNotHandedBack);
                assertThat(laneWarnings()).as("no release is reported")
                                          .noneMatch(warning -> warning.contains("released ordered unit " + keptAgain.shard() + " on queue " + QUEUE_ID));

                var askedForTheSecond = queue.releaseKeptOrderedUnit(INSTANCE_ID + "-1", keptAgain.shard());
                assertThat(askedForTheSecond.releases()).as("asked for the other subscription's id, the unit it kept is released")
                                                        .singleElement()
                                                        .satisfies(released -> {
                                                            assertThat(released.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                                                            assertThat(released.instanceId()).isEqualTo(INSTANCE_ID + "-1");
                                                            assertThat(released.keysInHandler()).containsExactly("ACC-1");
                                                        });
                assertThat(ownerOf(keptAgain.shard())).as("the row is released").isNull();

                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, keptAgain.shard()).releases()).as("asked again: nothing kept under the first id").isEmpty();
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID + "-1", keptAgain.shard()).releases()).as("nor under the second").isEmpty();
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

                // Asked until first.unsubscribe()'s budget thread has flushed the kept unit: the stop of first's lanes goes on
                // without that thread after SHUTDOWN_BUDGET, and until it has flushed the release answers BUSY.
                var releases = Awaitility.await().atMost(Duration.ofSeconds(5))
                                         .until(() -> queue.releaseKeptOrderedUnit(INSTANCE_ID, kept.shard()).releases(),
                                                answer -> answer.stream().noneMatch(entry -> entry.outcome() == KeptUnitRelease.Outcome.BUSY));
                assertThat(releases).as("the unsubscribed subscription's lane releases the unit it kept")
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

    /**
     * A release on request made while an unsubscribe is still stopping the subscription's lanes - here held where the
     * ordered lane's stop logs the WARN that it is keeping the unit, which names {@code releaseKeptOrderedUnit} - reaches
     * those lanes and releases the unit. The unsubscribe has taken the lanes out of the queue's registered consumers by
     * then, and they reach the held indexes only once both stops have returned, up to the shutdown budget later; the queue
     * asked those two alone, so the request came back empty, as from an instance that kept no such unit.
     */
    @Test
    void a_kept_unit_released_on_request_while_an_unsubscribe_is_still_stopping_its_lanes_is_released_by_those_lanes() throws Exception {
        // Far beyond the test, so the unsubscribed instance's membership row cannot age out and let another id take the unit.
        var leaseTtl = Duration.ofSeconds(20);
        var entered  = new CountDownLatch(1);
        var release  = new CountDownLatch(1);
        var holding  = holdAtTheWarningThatAUnitIsKept();
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            try {
                var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                    if (key != null) {
                        entered.countDown();
                        awaitQuietly(release, Duration.ofSeconds(60));
                    }
                }, ConsumerOptions.defaults());
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                assertThat(entered.await(20, TimeUnit.SECONDS)).as("premise: the key is in its handler").isTrue();
                var unit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);

                var unsubscribing = CompletableFuture.runAsync(subscription::unsubscribe);
                List<KeptUnitRelease> releases;
                try {
                    assertThat(holding.awaitHeld(Duration.ofSeconds(20))).as("premise: the ordered lane's stop kept the unit and says so").isTrue();
                    assertThat(queue.registeredConsumers()).as("premise: the unsubscribe has taken the lanes out of the queue").isEmpty();
                    assertThat(unsubscribing).as("premise: the unsubscribe is still stopping the lanes").isNotDone();
                    releases = queue.releaseKeptOrderedUnit(INSTANCE_ID, unit).releases();
                } finally {
                    holding.letGo();
                }
                assertThat(releases).as("the lanes still being stopped are asked, and release the unit they kept")
                                    .singleElement()
                                    .satisfies(released -> {
                                        assertThat(released.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                                        assertThat(released.instanceId()).isEqualTo(INSTANCE_ID);
                                        assertThat(released.keysInHandler()).containsExactly("ACC-1");
                                    });
                assertThat(ownerOf(unit)).as("the row is released").isNull();
                unsubscribing.get(15, TimeUnit.SECONDS);
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, unit).releases()).as("released once: the unit is no longer kept").isEmpty();
            } finally {
                holding.letGo();
                laneLogger.detachAppender(holding);
                release.countDown();
            }
        }
    }

    /**
     * {@link #a_kept_unit_released_on_request_while_an_unsubscribe_is_still_stopping_its_lanes_is_released_by_those_lanes}
     * for a {@code consume()} that fails once its ordered lane has handed the key to the handler: its undo stops both lanes,
     * the ordered lane's stop keeps the unit, and a release on request made then reaches the pair, which is in neither the
     * registered consumers nor the held indexes until {@code consume()} has given up on it.
     */
    @Test
    void a_kept_unit_released_on_request_while_a_failed_consume_is_still_stopping_its_lanes_is_released_by_those_lanes() throws Exception {
        var leaseTtl            = Duration.ofSeconds(20);
        var entered             = new CountDownLatch(1);
        var release             = new CountDownLatch(1);
        var acquisitionsTold    = new AtomicInteger();
        var failTheOrderedStart = new AtomicBoolean(true);
        var holding             = holdAtTheWarningThatAUnitIsKept();
        try (var queue = newQueue(PostgresqlMessageQueue.LifecycleHook.NONE, settingsWith(Duration.ofMillis(300), leaseTtl))) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void shardOwnershipChanged(int shard, boolean acquired) {
                    // The unordered lane starts first and tells its SHARD_COUNT acquisitions; the next is the ordered lane's
                    // first, told once its units are served.
                    if (acquired && failTheOrderedStart.get() && acquisitionsTold.incrementAndGet() == SHARD_COUNT + 1) {
                        awaitQuietly(entered, Duration.ofSeconds(20));
                        throw new IllegalStateException("The observer refuses the ordered lane's first acquisition");
                    }
                }
            });
            try {
                queue.enqueue(Message.ordered("in-handler".getBytes(StandardCharsets.UTF_8), 1, "ACC-1", 0));
                var unit = ShardOwnedSchema.unitForKey("ACC-1", ShardOwnedSchema.ORDERED_UNITS);
                var consuming = CompletableFuture.supplyAsync(() -> {
                    try {
                        return queue.consume((messageId, key, payload, payloadType) -> {
                            if (key != null) {
                                entered.countDown();
                                awaitQuietly(release, Duration.ofSeconds(60));
                            }
                        }, ConsumerOptions.defaults());
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
                List<KeptUnitRelease> releases;
                try {
                    assertThat(holding.awaitHeld(Duration.ofSeconds(20))).as("premise: the ordered lane's stop kept the unit and says so").isTrue();
                    assertThat(queue.registeredConsumers()).as("premise: the failing consume() registered nothing").isEmpty();
                    assertThat(consuming).as("premise: the consume() is still stopping the lanes").isNotDone();
                    releases = queue.releaseKeptOrderedUnit(INSTANCE_ID, unit).releases();
                } finally {
                    holding.letGo();
                }
                assertThat(releases).as("the lanes still being stopped are asked, and release the unit they kept")
                                    .singleElement()
                                    .satisfies(released -> {
                                        assertThat(released.outcome()).isEqualTo(KeptUnitRelease.Outcome.RELEASED);
                                        assertThat(released.instanceId()).isEqualTo(INSTANCE_ID);
                                        assertThat(released.keysInHandler()).containsExactly("ACC-1");
                                    });
                assertThat(ownerOf(unit)).as("the row is released").isNull();
                assertThatThrownBy(() -> consuming.get(15, TimeUnit.SECONDS)).as("premise: the consume() failed")
                                                                              .hasRootCauseInstanceOf(IllegalStateException.class)
                                                                              .hasMessageContaining("refuses the ordered lane's first acquisition");
                failTheOrderedStart.set(false);
                assertThat(queue.releaseKeptOrderedUnit(INSTANCE_ID, unit).releases()).as("released once: the unit is no longer kept").isEmpty();
            } finally {
                holding.letGo();
                laneLogger.detachAppender(holding);
                release.countDown();
            }
        }
    }

    /**
     * Attach to the lanes' logger an appender that holds the thread logging the WARN that a stop of {@link #INSTANCE_ID} is
     * keeping an ordered unit - the stop's budget thread, past its flush of the kept unit - until
     * {@link HoldingAppender#letGo}. Detach it once done.
     */
    private HoldingAppender holdAtTheWarningThatAUnitIsKept() {
        var holding = new HoldingAppender(message -> message.startsWith("Instance " + INSTANCE_ID + " is keeping "));
        holding.setContext(laneLogger.getLoggerContext());
        holding.start();
        laneLogger.addAppender(holding);
        return holding;
    }

    /**
     * Holds the first thread that logs a message {@code holdAt} matches, inside that logging call, until {@link #letGo}.
     * Unsynchronized: a {@code ch.qos.logback.core.AppenderBase} holds its monitor across {@code append}, so every other
     * thread logging to the same logger would wait behind the held one.
     */
    private static final class HoldingAppender extends ch.qos.logback.core.UnsynchronizedAppenderBase<ILoggingEvent> {
        private final Predicate<String> holdAt;
        private final AtomicBoolean     armed = new AtomicBoolean(true);
        private final CountDownLatch    held  = new CountDownLatch(1);
        private final CountDownLatch    going = new CountDownLatch(1);

        private HoldingAppender(Predicate<String> holdAt) {
            this.holdAt = holdAt;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (holdAt.test(event.getFormattedMessage()) && armed.compareAndSet(true, false)) {
                held.countDown();
                awaitQuietly(going, Duration.ofSeconds(30));
            }
        }

        boolean awaitHeld(Duration timeout) throws InterruptedException {
            return held.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void letGo() {
            going.countDown();
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
        return ShardOwnerSettings.builder()
                                 .setHoleExpiry(Duration.ofMillis(300))
                                 .setSweepInterval(Duration.ofMillis(100))
                                 .setPollBackstop(Duration.ofMillis(50))
                                 .setShedGrace(shedGrace)
                                 .setLeaseTtl(leaseTtl)
                                 .build();
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

    /**
     * Whether the calling thread is inside {@link ShardOwnedQueue#releaseKeptOrderedUnit} and not inside the
     * {@code chargeKeysInHandlerBeforeRelease} or {@code releaseLeaseRows} it calls: asking for a connection there, it is
     * the release's flush, under its claim.
     */
    private static boolean inTheFlushOfAReleaseOnRequest() {
        return StackWalker.getInstance().walk(frames -> {
            var methods = frames.filter(frame -> frame.getClassName().equals(ShardOwnedQueue.class.getName()))
                                .map(StackWalker.StackFrame::getMethodName)
                                .toList();
            return methods.contains("releaseKeptOrderedUnit") && !methods.contains("chargeKeysInHandlerBeforeRelease")
                    && !methods.contains("releaseLeaseRows");
        });
    }

    /**
     * A {@link javax.sql.DataSource} that, told to ({@link #runOnce}), runs an action on one thread as that thread next asks
     * for a connection, once, before handing it the delegate's. Every other request gets the delegate's connection as it
     * is.
     */
    private static final class RunBeforeConnectionDataSource implements javax.sql.DataSource {
        private final javax.sql.DataSource             delegate;
        private final AtomicReference<Thread>          onThread   = new AtomicReference<>();
        private final AtomicReference<Runnable>        action     = new AtomicReference<>();
        private final AtomicReference<Thread>          statementThread = new AtomicReference<>();
        private final AtomicReference<String>          statementPrefix = new AtomicReference<>();
        private final AtomicReference<Runnable>        statementAction = new AtomicReference<>();

        RunBeforeConnectionDataSource(javax.sql.DataSource delegate) {
            this.delegate = delegate;
        }

        void runOnce(Thread thread, Runnable runnable) {
            action.set(runnable);
            onThread.set(thread);
        }

        /**
         * Run {@code runnable} once, on {@code thread}, as it next prepares a statement starting with {@code sqlPrefix} on a
         * connection it asks for from here on, before that statement is prepared.
         */
        void runOnceBeforeStatement(Thread thread, String sqlPrefix, Runnable runnable) {
            statementPrefix.set(sqlPrefix);
            statementAction.set(runnable);
            statementThread.set(thread);
        }

        private void runIfTold() throws SQLException {
            if (!Thread.currentThread().equals(onThread.get())) {
                return;
            }
            var runnable = action.getAndSet(null);
            if (runnable != null) {
                onThread.set(null);
                runnable.run();
            }
        }

        // Connection's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
        // its interface, which the context loader need not.
        @SuppressWarnings("PMD.UseProperClassLoader")
        private Connection watched(Connection connection) {
            if (!Thread.currentThread().equals(statementThread.get())) {
                return connection;
            }
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName()) && args != null && args[0] instanceof String sql
                        && sql.startsWith(statementPrefix.get()) && Thread.currentThread().equals(statementThread.get())) {
                    var runnable = statementAction.getAndSet(null);
                    if (runnable != null) {
                        statementThread.set(null);
                        runnable.run();
                    }
                }
                try {
                    return method.invoke(connection, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }

        @Override
        public Connection getConnection() throws SQLException {
            runIfTold();
            return watched(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            runIfTold();
            return watched(delegate.getConnection(username, password));
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
