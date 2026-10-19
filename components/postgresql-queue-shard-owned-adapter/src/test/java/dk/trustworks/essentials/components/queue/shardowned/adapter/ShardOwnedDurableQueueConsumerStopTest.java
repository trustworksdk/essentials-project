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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue;
import dk.trustworks.essentials.components.queue.shardowned.spi.Subscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.queue.shardowned.adapter.TestProxies.proxy;
import static org.assertj.core.api.Assertions.*;

/**
 * A consumer's {@code stop()} called while its {@code start()} is still inside the queue's {@code consume()} - which
 * registers the instance and leases units on the database, unbounded when a statement is caught by a network partition -
 * returns within {@link ShardOwnedDurableQueueConsumer#STOP_WAITS_FOR_START}, and the start stops the subscription the
 * queue then returns. {@code start()} and {@code stop()} used to be {@code synchronized}, so the stop waited for the whole
 * of {@code consume()}. The queue is stood in for by a proxy whose {@code consume()} blocks until the test lets it go.
 */
class ShardOwnedDurableQueueConsumerStopTest {

    private final CountDownLatch   consuming     = new CountDownLatch(1);
    private final CountDownLatch   letConsumeEnd = new CountDownLatch(1);
    private final List<String>     stopped       = new CopyOnWriteArrayList<>();
    private final AtomicBoolean    stopThrows    = new AtomicBoolean();
    /** When set, the subscription's {@code unsubscribe()} counts down {@link #subscriptionStopping} and blocks until it is let go. */
    private final AtomicReference<CountDownLatch> letSubscriptionStopEnd = new AtomicReference<>();
    private final CountDownLatch   subscriptionStopping = new CountDownLatch(1);
    private final AtomicInteger    consumeCalls  = new AtomicInteger();
    /**
     * When set, run once on the start's thread just before {@code consume()} returns - as a {@code QueueObserver} told of
     * an acquisition runs inside it.
     */
    private final AtomicReference<Runnable> insideConsume     = new AtomicReference<>();
    /**
     * When set, run once on the stopping thread inside the subscription's {@code unsubscribe()} - as a
     * {@code QueueObserver} told of a release runs inside it.
     */
    private final AtomicReference<Runnable> insideUnsubscribe = new AtomicReference<>();
    private final MessageQueue     queue         = proxy(MessageQueue.class, (method, args) -> {
        if (!"consume".equals(method)) {
            throw new UnsupportedOperationException(method);
        }
        consumeCalls.incrementAndGet();
        consuming.countDown();
        awaitUninterruptibly(letConsumeEnd);
        var started = new boolean[]{true};
        var subscription = proxy(Subscription.class, (subscriptionMethod, subscriptionArgs) -> switch (subscriptionMethod) {
            case "isStarted" -> started[0];
            case "unsubscribe" -> {
                started[0] = false;
                stopped.add("subscription");
                var observer = insideUnsubscribe.getAndSet(null);
                if (observer != null) {
                    observer.run();
                }
                var blocking = letSubscriptionStopEnd.get();
                if (blocking != null) {
                    subscriptionStopping.countDown();
                    awaitUninterruptibly(blocking);
                }
                if (stopThrows.get()) {
                    throw new IllegalStateException("simulated observer failure stopping");
                }
                yield null;
            }
            default -> throw new UnsupportedOperationException(subscriptionMethod);
        });
        var observer = insideConsume.getAndSet(null);
        if (observer != null) {
            observer.run();
        }
        return subscription;
    });
    private final List<Object>     cancelled     = new CopyOnWriteArrayList<>();
    private final ShardOwnedDurableQueueConsumer consumer = new ShardOwnedDurableQueueConsumer(consumeFrom("stuck"), queue,
                                                                                                EssentialsObjectMappers.createJSONSerializer(),
                                                                                                cancelled::add,
                                                                                                (message, handler) -> handler.handle(message));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_stop_called_while_the_start_is_still_subscribing_returns_within_its_wait_and_the_start_stops_the_subscription(boolean interrupted) throws Exception {
        var starting = CompletableFuture.runAsync(consumer::start);
        try {
            assertThat(consuming.await(10, TimeUnit.SECONDS)).as("premise: the start is inside the queue's consume()").isTrue();

            var stop = stopOnAnotherThread(interrupted);

            assertThat(stop.returned()).as("the stop returned while consume() had not").isTrue();
            assertThat(stop.took()).as(interrupted ? "an interrupted stop does not wait for the start"
                                                     : "the stop waits for the start no longer than its wait, and a margin")
                                     .isLessThan(interrupted ? Duration.ofMillis(1_000)
                                                             : ShardOwnedDurableQueueConsumer.STOP_WAITS_FOR_START.plusMillis(1_500));
            assertThat(stop.stillInterrupted()).as("the stop left the thread's interrupt flag as it found it").isEqualTo(interrupted);
            assertThat(starting).as("premise: the start was still subscribing when the stop returned").isNotDone();
            assertThat(consumer.isStarted()).isFalse();
        } finally {
            letConsumeEnd.countDown();
        }

        starting.get(10, TimeUnit.SECONDS);

        assertThat(stopped).as("the start stopped the subscription the queue returned").containsExactly("subscription");
        assertThat(consumer.isStarted()).as("the start left the consumer not started").isFalse();
    }

    /**
     * What the subscription's stop throws then is logged, not thrown from {@code start()}: the stop's caller has returned.
     */
    @Test
    void a_failure_stopping_the_subscription_for_a_stop_that_did_not_wait_is_not_thrown_from_the_start() throws Exception {
        stopThrows.set(true);
        var starting = CompletableFuture.runAsync(consumer::start);
        try {
            assertThat(consuming.await(10, TimeUnit.SECONDS)).as("premise: the start is inside the queue's consume()").isTrue();
            assertThat(stopOnAnotherThread(true).returned()).as("premise: the stop returned while consume() had not").isTrue();
        } finally {
            letConsumeEnd.countDown();
        }

        assertThatCode(() -> starting.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThat(stopped).containsExactly("subscription");
        assertThat(consumer.isStarted()).isFalse();
    }

    /**
     * After a stop that gave up waiting, a restart - {@code ShardOwnedDurableQueues.start()} restarts the same consumer
     * objects - waits for the earlier start still inside {@code consume()}, as long as that takes, and interrupted too,
     * as {@code lock()} would, with the flag set again once it returns. It used to wait in {@code lock()} without a word;
     * once it has waited {@link ShardOwnedDurableQueueConsumer#STOP_WAITS_FOR_START} it now logs one WARN naming the
     * consumer and the queue.
     */
    @Test
    void a_start_after_a_stop_that_did_not_wait_waits_for_the_earlier_start_and_logs_one_warn_saying_so() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ShardOwnedDurableQueueConsumer.class);
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        logger.addAppender(logged);
        var restartReturned         = new CountDownLatch(1);
        var restartStillInterrupted = new AtomicBoolean();
        var restarting = new Thread(() -> {
            consumer.start();
            restartStillInterrupted.set(Thread.currentThread().isInterrupted());
            restartReturned.countDown();
        });
        try {
            var starting = CompletableFuture.runAsync(consumer::start);
            try {
                assertThat(consuming.await(10, TimeUnit.SECONDS)).as("premise: the start is inside the queue's consume()").isTrue();
                assertThat(stopOnAnotherThread(true).returned()).as("premise: the stop returned while consume() had not").isTrue();

                restarting.start();
                var deadline = System.nanoTime() + ShardOwnedDurableQueueConsumer.STOP_WAITS_FOR_START.plusSeconds(5).toNanos();
                while (waitingToStart(logged).isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertThat(waitingToStart(logged)).as("the restart says it is waiting for the earlier start, naming the consumer and the queue")
                                                  .singleElement()
                                                  .satisfies(message -> assertThat(message).contains("'" + consumer.consumerName() + "'",
                                                                                                     "'stuck'"));

                restarting.interrupt();
                assertThat(restartReturned.await(ShardOwnedDurableQueueConsumer.STOP_WAITS_FOR_START.toMillis() + 500, TimeUnit.MILLISECONDS))
                        .as("the restart goes on waiting for the earlier start, interrupted or not")
                        .isFalse();
                assertThat(waitingToStart(logged)).as("one WARN per wait, not one per budget").hasSize(1);
                assertThat(starting).as("premise: the earlier start is still subscribing").isNotDone();
            } finally {
                letConsumeEnd.countDown();
            }

            starting.get(10, TimeUnit.SECONDS);
            assertThat(restartReturned.await(10, TimeUnit.SECONDS)).as("the restart returns once the earlier start has").isTrue();
            assertThat(restartStillInterrupted).as("the restart set the interrupt it deferred again").isTrue();
            assertThat(stopped).as("the earlier start stopped its own subscription").containsExactly("subscription");
            assertThat(consumer.isStarted()).as("the restart subscribed afresh").isTrue();
        } finally {
            letConsumeEnd.countDown();
            logger.detachAppender(logged);
        }
    }

    /**
     * A second stop called while the start is stopping the subscription for a stop that gave up waiting - each lane of it
     * may drain for up to {@code shedGrace} and then hand back its units - returns within
     * {@link ShardOwnedDurableQueueConsumer#STOP_WAITS_FOR_START}, as the first did, leaving that stopping to the start. It
     * used to wait for the whole of the subscription's stop, spinning on the lock. For instance {@code DurableQueues.stop()}
     * followed by an Inbox's {@code cancel()}, during a shutdown after a network partition.
     */
    @Test
    void a_stop_called_while_the_start_stops_the_subscription_for_a_stop_that_did_not_wait_returns_within_its_wait() throws Exception {
        var letStopEnd = new CountDownLatch(1);
        letSubscriptionStopEnd.set(letStopEnd);
        var starting = CompletableFuture.runAsync(consumer::start);
        try {
            assertThat(consuming.await(10, TimeUnit.SECONDS)).as("premise: the start is inside the queue's consume()").isTrue();
            assertThat(stopOnAnotherThread(true).returned()).as("premise: the first stop returned while consume() had not").isTrue();
            letConsumeEnd.countDown();
            assertThat(subscriptionStopping.await(10, TimeUnit.SECONDS)).as("premise: the start is stopping the subscription consume() returned")
                                                                        .isTrue();

            var secondStop = stopOnAnotherThread(false);

            assertThat(secondStop.returned()).as("the second stop returned while the subscription's stop had not").isTrue();
            assertThat(secondStop.took()).as("the second stop waits no longer than its wait, and a margin")
                                         .isLessThan(ShardOwnedDurableQueueConsumer.STOP_WAITS_FOR_START.plusMillis(1_500));
            assertThat(starting).as("premise: the start was still stopping the subscription when the second stop returned").isNotDone();
            assertThat(consumer.isStarted()).isFalse();
        } finally {
            letConsumeEnd.countDown();
            letStopEnd.countDown();
        }

        starting.get(10, TimeUnit.SECONDS);

        assertThat(stopped).as("the subscription was stopped once, by the start").containsExactly("subscription");
        assertThat(consumer.isStarted()).as("the start left the consumer not started").isFalse();
    }

    /**
     * {@code stopIf} reads its condition only once it holds the lifecycle lock a start holds throughout, so a start still
     * subscribing has finished before the condition is read, and a condition that then reads false leaves the subscription
     * that start made running. This is what keeps {@code ShardOwnedDurableQueues}' compensating stop from undoing a
     * {@code start()} that set its flag again between its read and the stop: a condition read before the lock would see
     * the consumer not yet started, here, and the stop then run after the start had subscribed.
     */
    @Test
    void stopIf_reads_its_condition_only_once_a_start_still_subscribing_has_finished_and_stops_nothing_when_it_is_false() throws Exception {
        var starting = CompletableFuture.runAsync(consumer::start);
        var startedWhenRead = new AtomicReference<Boolean>();
        var stopIfReturned  = new AtomicReference<Boolean>();
        var stopping = new Thread(() -> stopIfReturned.set(consumer.stopIf(() -> {
            startedWhenRead.set(consumer.isStarted());
            return false;
        })));
        try {
            assertThat(consuming.await(10, TimeUnit.SECONDS)).as("premise: the start is inside the queue's consume()").isTrue();

            stopping.start();
            org.awaitility.Awaitility.await()
                                     .atMost(Duration.ofSeconds(10))
                                     .until(() -> stopping.getState() == Thread.State.WAITING
                                             || stopping.getState() == Thread.State.TIMED_WAITING);
            assertThat(startedWhenRead.get()).as("the condition is not read while the start holds the lock").isNull();
        } finally {
            letConsumeEnd.countDown();
        }

        starting.get(10, TimeUnit.SECONDS);
        stopping.join(10_000);

        assertThat(stopping.isAlive()).as("stopIf returned once the start had").isFalse();
        assertThat(stopIfReturned.get()).as("stopIf says the condition did not hold").isFalse();
        assertThat(startedWhenRead.get()).as("the condition was read once the start had subscribed").isTrue();
        assertThat(stopped).as("nothing was stopped").isEmpty();
        assertThat(consumer.isStarted()).as("the subscription the start made is still running").isTrue();
    }

    @Test
    void stopIf_stops_the_subscription_when_its_condition_holds() {
        letConsumeEnd.countDown();
        consumer.start();
        assertThat(consumer.isStarted()).as("premise: started").isTrue();

        assertThat(consumer.stopIf(() -> true)).as("stopIf says the condition held").isTrue();

        assertThat(stopped).containsExactly("subscription");
        assertThat(consumer.isStarted()).isFalse();
    }

    /**
     * A start waiting for the lifecycle lock while the holder reads {@code NONE} - here a {@code stopIf} reading its
     * condition; otherwise a holder that has just taken the lock or is about to let go of it - says nothing yet, and asks
     * again one {@link ShardOwnedDurableQueueConsumer#STOP_WAITS_FOR_START} later, as {@code PostgresqlMessageQueue}'s
     * {@code holdLifecycle} does. It used to log at once that an earlier start was still in the queue's {@code consume()},
     * leasing units until the driver's {@code socketTimeout}: a guess, and the one WARN the wait logs. Once the holder is a
     * stop stopping the subscription, the WARN says that.
     */
    @Test
    void a_start_waiting_while_the_holder_reads_none_says_nothing_until_a_later_read_names_the_holder() throws Exception {
        letConsumeEnd.countDown();
        consumer.start();
        assertThat(consumer.isStarted()).as("premise: started").isTrue();
        var letStopEnd = new CountDownLatch(1);
        letSubscriptionStopEnd.set(letStopEnd);
        var conditionRead   = new CountDownLatch(1);
        var letConditionEnd = new CountDownLatch(1);
        var logger          = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ShardOwnedDurableQueueConsumer.class);
        var logged          = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        logger.addAppender(logged);
        CompletableFuture<Boolean> stopping;
        CompletableFuture<Void>    restarting;
        try {
            stopping = CompletableFuture.supplyAsync(() -> consumer.stopIf(() -> {
                conditionRead.countDown();
                awaitUninterruptibly(letConditionEnd);
                return true;
            }));
            assertThat(conditionRead.await(10, TimeUnit.SECONDS)).as("premise: stopIf holds the lock, reading its condition").isTrue();

            restarting = CompletableFuture.runAsync(consumer::start);
            Thread.sleep(ShardOwnedDurableQueueConsumer.STOP_WAITS_FOR_START.plusMillis(700).toMillis());
            assertThat(restarting).as("premise: the restart has waited past its first wait for the lock").isNotDone();
            assertThat(waitingToStart(logged)).as("nothing said while the holder reads NONE").isEmpty();

            letConditionEnd.countDown();
            assertThat(subscriptionStopping.await(10, TimeUnit.SECONDS)).as("premise: stopIf is stopping the subscription").isTrue();
            var deadline = System.nanoTime() + ShardOwnedDurableQueueConsumer.STOP_WAITS_FOR_START.plusSeconds(5).toNanos();
            while (waitingToStart(logged).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(waitingToStart(logged)).as("one WARN, once a read names the holder: the stop stopping the subscription")
                                              .singleElement()
                                              .satisfies(message -> assertThat(message).contains("a stop of the consumer is still stopping its subscription")
                                                                                       .doesNotContain("consume()"));
        } finally {
            letConditionEnd.countDown();
            letStopEnd.countDown();
            logger.detachAppender(logged);
        }

        assertThat(stopping.get(10, TimeUnit.SECONDS)).as("stopIf says the condition held").isTrue();
        restarting.get(10, TimeUnit.SECONDS);
        assertThat(stopped).as("the stopIf stopped the first subscription").containsExactly("subscription");
        assertThat(consumer.isStarted()).as("the restart subscribed afresh").isTrue();
    }

    private static List<String> waitingToStart(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged) {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                          .filter(message -> message.contains("still waiting to start"))
                          .toList();
    }

    @Test
    void a_stop_after_the_start_has_subscribed_stops_the_subscription_itself() {
        letConsumeEnd.countDown();
        consumer.start();
        assertThat(consumer.isStarted()).as("premise: started").isTrue();

        consumer.stop();

        assertThat(stopped).containsExactly("subscription");
        assertThat(consumer.isStarted()).isFalse();
    }

    /**
     * A stop or cancel on the start's own thread, from inside the queue's {@code consume()} - a {@code QueueObserver} told
     * of an acquisition - is owed as a stop that gave up waiting is: the start unsubscribes the subscription
     * {@code consume()} returns, and the consumer ends not started. It used to take the lifecycle lock again, reentrantly,
     * find no subscription yet and stop nothing, and the start then kept the subscription: after a cancel, a consumer
     * delivering though dropped from the {@code DurableQueues}, which no stop reached.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_stop_or_cancel_on_the_starts_own_thread_inside_consume_is_owed_and_the_start_unsubscribes(boolean cancel) {
        letConsumeEnd.countDown();
        insideConsume.set(cancel ? consumer::cancel : consumer::stop);

        consumer.start();

        assertThat(insideConsume.get()).as("premise: the stop ran inside consume()").isNull();
        assertThat(stopped).as("the start unsubscribed the subscription consume() returned").containsExactly("subscription");
        assertThat(consumer.isStarted()).isFalse();
        assertThat(consumer.holder()).as("the start let go of the holder").isEqualTo(ShardOwnedDurableQueueConsumer.Holder.NONE);
        assertThat(cancelled).as("a cancel deregisters the consumer").isEqualTo(cancel ? List.of(consumer) : List.of());

        consumer.start();

        if (cancel) {
            assertThat(consumeCalls).as("a cancelled consumer does not start again").hasValue(1);
            assertThat(consumer.isStarted()).isFalse();
        } else {
            assertThat(consumeCalls).as("a later start subscribes afresh").hasValue(2);
            assertThat(consumer.isStarted()).isTrue();
        }
    }

    /**
     * A start on the start's own thread, from inside the queue's {@code consume()}, does nothing: the start subscribing is
     * the one starting the consumer. It used to subscribe a second time inside {@code consume()}, and the outer start then
     * replaced the inner start's subscription with its own, leaving that one running where no stop reached it.
     */
    @Test
    void a_start_on_the_starts_own_thread_inside_consume_does_nothing_and_the_consumer_subscribes_once() {
        letConsumeEnd.countDown();
        insideConsume.set(consumer::start);
        var logged = captureLogs();
        try {
            consumer.start();

            assertThat(insideConsume.get()).as("premise: the start ran inside consume()").isNull();
            assertThat(consumeCalls).as("the queue's consume() was called once").hasValue(1);
            assertThat(consumer.isStarted()).isTrue();
            assertThat(warningsOrErrors(logged)).as("nothing to warn about: the start subscribing starts the consumer").isEmpty();

            consumer.stop();

            assertThat(stopped).as("the one subscription was the consumer's, and its stop ended it").containsExactly("subscription");
            assertThat(consumer.isStarted()).isFalse();
        } finally {
            detach(logged);
        }
    }

    /**
     * A stop or cancel on the thread of a stop, from inside the subscription's {@code unsubscribe()} - a
     * {@code QueueObserver} told of a release - leaves the stopping to that stop. It used to take the lifecycle lock again,
     * reentrantly, and unsubscribe the subscription a second time.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_stop_or_cancel_on_the_stopping_thread_inside_unsubscribe_leaves_the_stopping_to_that_stop(boolean cancel) {
        letConsumeEnd.countDown();
        consumer.start();
        assertThat(consumer.isStarted()).as("premise: started").isTrue();
        insideUnsubscribe.set(cancel ? consumer::cancel : consumer::stop);

        consumer.stop();

        assertThat(insideUnsubscribe.get()).as("premise: the second stop ran inside unsubscribe()").isNull();
        assertThat(stopped).as("the subscription was unsubscribed once").containsExactly("subscription");
        assertThat(consumer.isStarted()).isFalse();
        assertThat(consumer.holder()).as("the stop let go of the holder").isEqualTo(ShardOwnedDurableQueueConsumer.Holder.NONE);
    }

    /**
     * A start on the thread of a stop, from inside the subscription's {@code unsubscribe()}, does nothing - the stop ends the
     * consumer not started - and says so at WARN, naming the consumer, the queue and the holder.
     */
    @Test
    void a_start_on_the_stopping_thread_inside_unsubscribe_does_nothing_and_says_so() {
        letConsumeEnd.countDown();
        consumer.start();
        insideUnsubscribe.set(consumer::start);
        var logged = captureLogs();
        try {
            consumer.stop();

            assertThat(insideUnsubscribe.get()).as("premise: the start ran inside unsubscribe()").isNull();
            assertThat(consumeCalls).as("the start did not subscribe").hasValue(1);
            assertThat(consumer.isStarted()).isFalse();
            assertThat(warningsOrErrors(logged)).singleElement()
                                                .satisfies(message -> assertThat(message).contains("'" + consumer.consumerName() + "'", "'stuck'",
                                                                                                   "(STOPPING)", "does nothing"));
        } finally {
            detach(logged);
        }
    }

    /**
     * A start on the start's own thread, from inside the queue's {@code consume()}, after a cancel there - an observer that
     * cancels and then starts - does nothing and says nothing: a cancelled consumer is never started again, so there is
     * nothing to start again once the start has returned. It used to log a WARN telling the operator to start it again.
     */
    @Test
    void a_start_inside_consume_after_a_cancel_there_does_nothing_and_says_nothing() {
        letConsumeEnd.countDown();
        insideConsume.set(() -> {
            consumer.cancel();
            consumer.start();
        });
        var logged = captureLogs();
        try {
            consumer.start();

            assertThat(insideConsume.get()).as("premise: the cancel and the start ran inside consume()").isNull();
            assertThat(consumeCalls).as("the queue's consume() was called once").hasValue(1);
            assertThat(stopped).as("the start unsubscribed the subscription consume() returned").containsExactly("subscription");
            assertThat(consumer.isStarted()).isFalse();
            assertThat(warningsOrErrors(logged)).as("a cancelled consumer's start has nothing to warn about").isEmpty();
        } finally {
            detach(logged);
        }
    }

    /**
     * A start on the thread of a cancel or retire, from inside the subscription's {@code unsubscribe()} - a
     * {@code QueueObserver} told of a release - does nothing and says nothing, as a start of a retired or cancelled
     * consumer does on any other thread.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void a_start_inside_unsubscribe_of_a_cancel_or_retire_does_nothing_and_says_nothing(boolean retire) {
        letConsumeEnd.countDown();
        consumer.start();
        assertThat(consumer.isStarted()).as("premise: started").isTrue();
        insideUnsubscribe.set(consumer::start);
        var logged = captureLogs();
        try {
            if (retire) {
                consumer.retire();
            } else {
                consumer.cancel();
            }

            assertThat(insideUnsubscribe.get()).as("premise: the start ran inside unsubscribe()").isNull();
            assertThat(consumeCalls).as("the start did not subscribe").hasValue(1);
            assertThat(consumer.isStarted()).isFalse();
            assertThat(warningsOrErrors(logged)).as("a retired or cancelled consumer's start has nothing to warn about").isEmpty();
        } finally {
            detach(logged);
        }
    }

    /**
     * Further stops on the start's own thread, once one inside {@code consume()} has made the start owe it - a second one
     * inside {@code consume()} ({@code STOP_OWED}), and one inside the owed stop's {@code unsubscribe()}
     * ({@code FINISHING_OWED_STOP}) - add nothing: the subscription is unsubscribed once, by the start.
     */
    @Test
    void further_stops_on_the_starts_own_thread_after_an_owed_one_leave_the_stopping_to_the_start() {
        letConsumeEnd.countDown();
        var holdersSeen = new CopyOnWriteArrayList<ShardOwnedDurableQueueConsumer.Holder>();
        insideConsume.set(() -> {
            holdersSeen.add(consumer.holder());
            consumer.stop();
            holdersSeen.add(consumer.holder());
            consumer.stop();
        });
        insideUnsubscribe.set(() -> {
            holdersSeen.add(consumer.holder());
            consumer.stop();
        });

        consumer.start();

        assertThat(insideConsume.get()).as("premise: the stops ran inside consume()").isNull();
        assertThat(insideUnsubscribe.get()).as("premise: the last stop ran inside the owed stop's unsubscribe()").isNull();
        assertThat(holdersSeen).as("premise: the holder each nested stop found")
                               .containsExactly(ShardOwnedDurableQueueConsumer.Holder.STARTING,
                                                ShardOwnedDurableQueueConsumer.Holder.STOP_OWED,
                                                ShardOwnedDurableQueueConsumer.Holder.FINISHING_OWED_STOP);
        assertThat(stopped).as("the subscription was unsubscribed once").containsExactly("subscription");
        assertThat(consumer.isStarted()).isFalse();
        assertThat(consumer.holder()).as("the start let go of the holder").isEqualTo(ShardOwnedDurableQueueConsumer.Holder.NONE);
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureLogs() {
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ShardOwnedDurableQueueConsumer.class)).addAppender(logged);
        return logged;
    }

    private static void detach(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ShardOwnedDurableQueueConsumer.class)).detachAppender(logged);
    }

    private static List<String> warningsOrErrors(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logged) {
        return logged.list.stream()
                          .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
                          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                          .toList();
    }

    private record Stopped(boolean returned, Duration took, boolean stillInterrupted) {
    }

    private Stopped stopOnAnotherThread(boolean interrupted) throws InterruptedException {
        var took             = new AtomicLong();
        var stillInterrupted = new AtomicBoolean();
        var stopping = new Thread(() -> {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            var stopCalled = System.nanoTime();
            consumer.stop();
            took.set(System.nanoTime() - stopCalled);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        stopping.start();
        stopping.join(ShardOwnedDurableQueueConsumer.STOP_WAITS_FOR_START.plusSeconds(5).toMillis());
        return new Stopped(!stopping.isAlive(), Duration.ofNanos(took.get()), stillInterrupted.get());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        var interrupted = false;
        while (true) {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never let consume() end");
                }
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ConsumeFromQueue consumeFrom(String queueName) {
        return ConsumeFromQueue.builder()
                               .setQueueName(QueueName.of(queueName))
                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                               .setParallelConsumers(1)
                               .setQueueMessageHandler(message -> { })
                               .build();
    }
}
