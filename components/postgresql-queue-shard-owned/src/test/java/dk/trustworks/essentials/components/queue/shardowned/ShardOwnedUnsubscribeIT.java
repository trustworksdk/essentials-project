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

    private record UnitFence(int shard, long fence) {
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

    private static ShardOwnerSettings settingsWith(Duration shedGrace, Duration leaseTtl) {
        var defaults = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(), Duration.ofMillis(1), Duration.ofMillis(2),
                                      Duration.ofMillis(300), Duration.ofMillis(100), 1_000, 8,
                                      Duration.ofMillis(50), Duration.ofSeconds(30), 2, shedGrace, leaseTtl, Duration.ofSeconds(60));
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
