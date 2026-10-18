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
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueConsumer;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueueException;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues;
import dk.trustworks.essentials.components.queue.shardowned.spi.Subscription;
import org.junit.jupiter.api.AfterEach;
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
 * {@code DurableQueues.start()} starts every consumer or none. It used to set itself started and then start its consumers
 * one after another, so a consumer whose start threw - a failed subscription, or a {@code QueueObserver} throwing for an
 * acquisition - left the consumers before it consuming, the ones after it unstarted, and the {@code DurableQueues}
 * reporting itself started. The engine is stood in for by proxies. Each test registers three consumers and fails the
 * SECOND subscription the start attempts, whichever consumer that is, so one consumer has started before the failure
 * and one has not been reached, whatever order the consumers are started in.
 */
class ShardOwnedDurableQueuesStartTest {

    private final AtomicInteger subscriptions = new AtomicInteger();
    private final List<String>  subscribed    = new CopyOnWriteArrayList<>();
    private final List<String>  stopped       = new CopyOnWriteArrayList<>();
    /**
     * Which subscription, counted from the first {@code consume} of the test, fails while {@link #failure} is set.
     */
    private volatile int        failingSubscription = 2;
    /**
     * The type of what the {@link #failingSubscription}th subscription throws - {@code IllegalStateException},
     * {@code AssertionError} or {@code StackOverflowError} - or null for none to fail.
     */
    private volatile String     failure;
    /**
     * Counted down by the stop of a subscription of a queue whose name starts with {@code slow-stop}, which then waits
     * for {@link #releaseSlowStop}.
     */
    private final CountDownLatch slowStopEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSlowStop = new CountDownLatch(1);
    /**
     * Which subscription, counted from the first {@code consume} of the test, waits for {@link #releaseConsume} once it has
     * counted down {@link #consumeEntered} - held up in its queue's {@code consume()}, as by a network partition - or 0 for
     * none to.
     */
    private volatile int         heldUpSubscription;
    private final CountDownLatch consumeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseConsume = new CountDownLatch(1);
    /**
     * Run, once, by the {@link ShardOwnedDurableQueues.StartHook} as {@code consumeFromQueue} is about to start the consumer
     * it added, and as {@code start()}'s loop is about to start a consumer.
     */
    private final AtomicReference<Runnable> beforeStartingAddedConsumer = new AtomicReference<>();
    private final AtomicReference<Runnable> beforeStartingConsumer      = new AtomicReference<>();
    /**
     * A field, not a local, and shut down after each test once every latch a task may wait for is released: closing it
     * (try-with-resources) would wait for a task still waiting for one, and hang the build when a test fails.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final MessageQueues queues        = proxy(MessageQueues.class, (method, args) -> switch (method) {
        case "findQueue" -> Optional.of(queue(((dk.trustworks.essentials.components.queue.shardowned.spi.QueueName) args[0]).toString()));
        case "queueNames" -> List.of();
        default -> throw new UnsupportedOperationException(method);
    });

    /**
     * {@code thrownType} is what the failing subscription throws: an {@code IllegalStateException}, which the consumer
     * wraps in a {@link DurableQueueException}, or an {@code AssertionError} (a non-critical {@code Error}), which escapes
     * the consumer's start as it was thrown.
     */
    @ParameterizedTest
    @ValueSource(strings = {"IllegalStateException", "AssertionError"})
    void a_start_whose_consumer_fails_to_start_stops_every_consumer_and_is_not_started(String thrownType) {
        var durableQueues = newDurableQueues();
        var consumers     = consumeFromThreeQueues(durableQueues, "queue");
        failure = thrownType;

        var thrown = catchThrowable(durableQueues::start);

        if ("AssertionError".equals(thrownType)) {
            assertThat(thrown).as("the consumer's error escapes as it was thrown").isInstanceOf(AssertionError.class)
                              .hasMessageStartingWith("simulated failure subscribing");
        } else {
            assertThat(thrown).as("the consumer's failure escapes").isInstanceOf(DurableQueueException.class)
                              .hasRootCauseInstanceOf(IllegalStateException.class)
                              .rootCause().hasMessageStartingWith("simulated failure subscribing");
        }
        assertThat(subscribed).as("premise: one consumer had started before the failing one").hasSize(1);
        assertThat(stopped).as("the consumer that had started was stopped again").containsExactlyElementsOf(subscribed);
        assertThat(consumers).as("no consumer is left started").noneMatch(DurableQueueConsumer::isStarted);
        assertThat(durableQueues.isStarted()).as("the DurableQueues reports itself not started").isFalse();

        failure = null;
        durableQueues.start();

        assertThat(consumers).as("a second start starts every consumer").allMatch(DurableQueueConsumer::isStarted);
        assertThat(durableQueues.isStarted()).isTrue();
    }

    @Test
    void a_stop_failing_while_a_failed_start_is_undone_is_added_to_the_start_failure() {
        var durableQueues = newDurableQueues();
        var consumers     = consumeFromThreeQueues(durableQueues, "stop-throws");
        failure = "IllegalStateException";

        var thrown = catchThrowable(durableQueues::start);

        assertThat(thrown).as("the start's failure escapes, not the stop's").isInstanceOf(DurableQueueException.class)
                          .rootCause().hasMessageStartingWith("simulated failure subscribing");
        assertThat(subscribed).as("premise: one consumer had started before the failing one").hasSize(1);
        assertThat(thrown.getSuppressed()).as("the stop's failure is added to it")
                                          .singleElement()
                                          .satisfies(suppressed -> assertThat(suppressed).hasMessage("simulated failure stopping " + subscribed.getFirst()));
        assertThat(consumers).as("no consumer is left started").noneMatch(DurableQueueConsumer::isStarted);
        assertThat(durableQueues.isStarted()).isFalse();
    }

    /**
     * A critical error ({@code Exceptions.isCriticalError}) from a consumer's start escapes untouched, with nothing undone,
     * and the {@code DurableQueues} stays started, so a {@code stop()} still stops what the start left running.
     */
    @Test
    void a_start_whose_consumer_throws_a_critical_error_stops_nothing_and_stays_started() {
        var durableQueues = newDurableQueues();
        consumeFromThreeQueues(durableQueues, "queue");
        failure = "StackOverflowError";

        var thrown = catchThrowable(durableQueues::start);

        assertThat(thrown).as("the critical error escapes as it was thrown").isExactlyInstanceOf(StackOverflowError.class)
                          .hasMessageStartingWith("simulated critical error subscribing");
        assertThat(thrown.getSuppressed()).isEmpty();
        assertThat(subscribed).as("premise: one consumer had started before the failing one").hasSize(1);
        assertThat(stopped).as("nothing was stopped").isEmpty();
        assertThat(durableQueues.isStarted()).isTrue();

        durableQueues.stop();

        assertThat(stopped).as("the stop stops what the start left running").containsExactlyElementsOf(subscribed);
        assertThat(durableQueues.isStarted()).isFalse();
    }

    /**
     * {@code consumeFromQueue} on a started {@code DurableQueues} registers the consumer and starts it. A start that threw
     * left it registered, so a retry for the same queue was refused with "There is already a consumer on queue" until the
     * whole {@code DurableQueues} was stopped.
     */
    @ParameterizedTest
    @ValueSource(strings = {"IllegalStateException", "AssertionError"})
    void a_consumer_whose_start_fails_is_not_left_registered_so_consuming_from_the_queue_again_succeeds(String thrownType) {
        var durableQueues = newDurableQueues();
        durableQueues.start();
        failingSubscription = 1;
        failure = thrownType;

        var thrown = catchThrowable(() -> durableQueues.consumeFromQueue(consumeFrom("retried")));

        if ("AssertionError".equals(thrownType)) {
            assertThat(thrown).as("the consumer's error escapes as it was thrown").isInstanceOf(AssertionError.class)
                              .hasMessage("simulated failure subscribing retried");
        } else {
            assertThat(thrown).as("the consumer's failure escapes").isInstanceOf(DurableQueueException.class)
                              .hasRootCauseMessage("simulated failure subscribing retried");
        }
        failure = null;
        var retried = durableQueues.consumeFromQueue(consumeFrom("retried"));
        assertThat(retried.isStarted()).as("the retry's consumer is started").isTrue();
        assertThat(subscribed).containsExactly("retried");
    }

    /**
     * {@code start()} on a {@code DurableQueues} that is already started does nothing. It used to start every consumer
     * again and, when one of them failed - one whose own {@code stop()} had stopped it, say - stop every consumer,
     * the ones that had been running all along included, and report itself not started.
     */
    @Test
    void a_start_of_a_started_durable_queues_leaves_its_running_consumers_running() {
        var durableQueues = newDurableQueues();
        var consumers     = consumeFromThreeQueues(durableQueues, "queue");
        durableQueues.start();
        assertThat(consumers).as("premise: every consumer started").allMatch(DurableQueueConsumer::isStarted);
        consumers.getFirst().stop();
        failingSubscription = subscriptions.get() + 1;
        failure = "IllegalStateException";

        assertThatCode(durableQueues::start).as("a start of a started DurableQueues does nothing").doesNotThrowAnyException();

        assertThat(subscriptions.get()).as("no consumer was started again").isEqualTo(3);
        assertThat(consumers.subList(1, 3)).as("the consumers that were running still run").allMatch(DurableQueueConsumer::isStarted);
        assertThat(stopped).as("only the consumer stopped on its own was stopped").containsExactly(consumers.getFirst().queueName().toString());
        assertThat(durableQueues.isStarted()).isTrue();
    }

    /**
     * A consumer {@code consumeFromQueue} adds while a failed start is stopping the consumers it had started does not
     * start: the {@code DurableQueues} reports itself not started from the moment the stopping begins. It used to report
     * itself started until every consumer was stopped, so the added consumer started, and the stopping - iterating the
     * consumers while it was added - could miss it, leaving it consuming with {@code isStarted()} false.
     */
    @Test
    void a_consumer_added_while_a_failed_start_is_stopping_its_consumers_does_not_start() throws Exception {
        var durableQueues = newDurableQueues();
        var consumers     = new ArrayList<>(consumeFromThreeQueues(durableQueues, "slow-stop"));
        failure = "IllegalStateException";
        var starting = executor.submit(() -> catchThrowable(durableQueues::start));
        await(slowStopEntered);

        var late = durableQueues.consumeFromQueue(consumeFrom("late"));

        releaseSlowStop.countDown();
        assertThat(starting.get(10, TimeUnit.SECONDS)).as("premise: the start failed").isInstanceOf(DurableQueueException.class);
        assertThat(subscribed).as("the consumer added meanwhile never subscribed").doesNotContain("late");
        assertThat(late.isStarted()).isFalse();
        assertThat(durableQueues.isStarted()).isFalse();

        failure = null;
        durableQueues.start();
        consumers.add(late);
        assertThat(consumers).as("the next start starts it with the others").allMatch(DurableQueueConsumer::isStarted);
    }

    /**
     * A {@code stop()} called while {@code start()} is held up starting one consumer - its queue's {@code consume()} caught
     * by a network partition, say - ends the start: the start starts none of the consumers after that one. It used to go
     * on through every consumer, so the stop, which gives up waiting for the held-up start
     * ({@link ShardOwnedDurableQueueConsumer#STOP_WAITS_FOR_START}) and finds nothing to stop on the consumers after it,
     * returned, and the start then started those consumers, consuming while the {@code DurableQueues} reported itself not
     * started.
     */
    @Test
    void a_stop_while_a_start_is_held_up_starting_one_consumer_keeps_the_start_from_starting_the_consumers_after_it() throws Exception {
        var durableQueues = newDurableQueues();
        var consumers     = consumeFromThreeQueues(durableQueues, "queue");
        heldUpSubscription = 2;
        var starting = executor.submit(durableQueues::start);
        await(consumeEntered);

        durableQueues.stop();
        releaseConsume.countDown();
        starting.get(10, TimeUnit.SECONDS);

        assertThat(subscriptions.get()).as("the start subscribed no consumer after the held-up one").isEqualTo(2);
        assertThat(subscribed).as("premise: the consumer before the held-up one, and the held-up one").hasSize(2);
        assertThat(stopped).as("both were stopped: the first by the stop, the held-up one by its own start")
                           .containsExactlyInAnyOrderElementsOf(subscribed);
        assertThat(consumers).as("no consumer is left started").noneMatch(DurableQueueConsumer::isStarted);
        assertThat(durableQueues.isStarted()).isFalse();

        durableQueues.start();
        assertThat(consumers).as("the next start starts every consumer").allMatch(DurableQueueConsumer::isStarted);
    }

    /**
     * A consumer {@code consumeFromQueue} adds to a started {@code DurableQueues}, whose start returns after a
     * {@code stop()} - here held up in its queue's {@code consume()} while the stop gives up waiting for it - is not left
     * started, stays registered, and is started by the next {@code start()}.
     */
    @Test
    void a_consumer_whose_start_returns_after_a_stop_stays_registered_stopped_and_starts_with_the_next_start() throws Exception {
        var durableQueues = newDurableQueues();
        durableQueues.start();
        heldUpSubscription = 1;
        var adding = executor.submit(() -> durableQueues.consumeFromQueue(consumeFrom("slow-consume")));
        await(consumeEntered);

        durableQueues.stop();
        releaseConsume.countDown();
        var consumer = adding.get(10, TimeUnit.SECONDS);

        assertThat(consumer.isStarted()).as("the consumer is not left started").isFalse();
        assertThat(stopped).containsExactly("slow-consume");
        assertThatThrownBy(() -> durableQueues.consumeFromQueue(consumeFrom("slow-consume")))
                .as("the consumer is still registered")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("There is already a consumer on queue 'slow-consume'");

        durableQueues.start();
        assertThat(consumer.isStarted()).as("the next start starts it").isTrue();
    }

    /**
     * The narrower window: a {@code stop()} that passes a consumer {@code consumeFromQueue} has registered, after it read
     * the {@code DurableQueues} started and before the consumer's start has begun, finds nothing to stop, and the start
     * then subscribes. {@code consumeFromQueue} stops that subscription itself, keeps the consumer registered for the next
     * {@code start()}, and logs what that stop throws at WARN rather than throwing it.
     */
    @Test
    void a_consumer_that_subscribes_after_a_stop_passed_it_is_stopped_again_logged_when_that_stop_fails_and_started_with_the_next_start() {
        var durableQueues = newDurableQueues();
        durableQueues.start();
        beforeStartingAddedConsumer.set(durableQueues::stop);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ShardOwnedDurableQueues.class);
        var logged = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logged.start();
        logger.addAppender(logged);
        try {
            var consumer = durableQueues.consumeFromQueue(consumeFrom("stop-throws-late"));

            assertThat(subscribed).as("premise: the consumer subscribed after the stop").containsExactly("stop-throws-late");
            assertThat(stopped).as("consumeFromQueue stopped the subscription").containsExactly("stop-throws-late");
            assertThat(consumer.isStarted()).isFalse();
            assertThat(durableQueues.isStarted()).isFalse();
            assertThat(logged.list).as("the stop's failure is logged at WARN, not thrown")
                                   .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                                   .singleElement()
                                   .satisfies(event -> {
                                       assertThat(event.getFormattedMessage()).contains("finished starting after ShardOwnedDurableQueues was stopped")
                                                                              .contains("It stays registered and is started by the next start()");
                                       assertThat(event.getThrowableProxy().getMessage()).isEqualTo("simulated failure stopping stop-throws-late");
                                   });
            assertThatThrownBy(() -> durableQueues.consumeFromQueue(consumeFrom("stop-throws-late")))
                    .as("the consumer is still registered")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("There is already a consumer on queue");

            durableQueues.start();
            assertThat(consumer.isStarted()).as("the next start starts it").isTrue();
        } finally {
            logger.detachAppender(logged);
        }
    }

    /**
     * A {@code start()} that comes while {@code consumeFromQueue} is stopping a consumer that subscribed after a
     * {@code stop()} is not undone by that stop: the consumer ends started, as the {@code DurableQueues} reports itself.
     * The stop reads the {@code DurableQueues} not started under the consumer's lifecycle lock, which the start's start of
     * the consumer waits for, so the start subscribes it again once the stop is done. A stop decided before that lock
     * could run after the start had restarted the consumer, leaving it stopped under a {@code DurableQueues} reporting
     * itself started, whose next {@code start()} does nothing.
     */
    @Test
    void a_start_while_a_consumer_that_subscribed_after_a_stop_is_being_stopped_again_leaves_it_started() throws Exception {
        var durableQueues = newDurableQueues();
        durableQueues.start();
        beforeStartingAddedConsumer.set(durableQueues::stop);
        var adding = executor.submit(() -> durableQueues.consumeFromQueue(consumeFrom("slow-stop-raced")));
        await(slowStopEntered);

        var restarting = executor.submit(durableQueues::start);
        releaseSlowStop.countDown();
        var consumer = adding.get(10, TimeUnit.SECONDS);
        restarting.get(10, TimeUnit.SECONDS);

        assertThat(durableQueues.isStarted()).isTrue();
        assertThat(consumer.isStarted()).as("the consumer is started, as the DurableQueues reports itself").isTrue();
        assertThat(subscribed).as("subscribed past the stop, then again by the start").containsExactly("slow-stop-raced", "slow-stop-raced");
        assertThat(stopped).containsExactly("slow-stop-raced");
    }

    /**
     * {@code consumeFromQueue} registers the consumer before it starts it, so a concurrent {@code start()} of the
     * {@code DurableQueues} can take it from the registry and start it after the {@code consumeFromQueue}'s own start has
     * failed. That start used to subscribe it although {@code consumeFromQueue} had dropped it from the registry and
     * thrown: a consumer no {@code stop()} reached and no caller held, running beside the one a retried
     * {@code consumeFromQueue} registered on the same queue - two instances, each allowed half the shards.
     */
    @Test
    void a_consumer_whose_start_fails_is_not_subscribed_by_a_start_that_had_already_taken_it() throws Exception {
        var durableQueues = newDurableQueues();
        durableQueues.start();
        failingSubscription = 1;
        failure = "IllegalStateException";
        var loopHasTheConsumer = new CountDownLatch(1);
        var releaseLoop        = new CountDownLatch(1);
        var restarting         = new AtomicReference<Future<?>>();
        beforeStartingConsumer.set(() -> {
            loopHasTheConsumer.countDown();
            await(releaseLoop);
        });
        beforeStartingAddedConsumer.set(() -> {
            durableQueues.stop();
            restarting.set(executor.submit(durableQueues::start));
            await(loopHasTheConsumer);
        });

        Throwable thrown;
        try {
            thrown = catchThrowable(() -> durableQueues.consumeFromQueue(consumeFrom("flaky")));
        } finally {
            releaseLoop.countDown();
        }
        restarting.get().get(10, TimeUnit.SECONDS);

        assertThat(thrown).as("premise: the consumer's start failed").isInstanceOf(DurableQueueException.class)
                          .hasRootCauseMessage("simulated failure subscribing flaky");
        assertThat(durableQueues.isStarted()).as("premise: the concurrent start went through").isTrue();
        assertThat(subscribed).as("the start that had taken the failed consumer did not subscribe it").isEmpty();

        failure = null;
        var retried = durableQueues.consumeFromQueue(consumeFrom("flaky"));
        assertThat(retried.isStarted()).isTrue();
        assertThat(subscribed).as("one subscription on the queue: the retry's").containsExactly("flaky");
        assertThat(durableQueues.getActiveQueueNames()).containsExactly(QueueName.of("flaky"));
    }

    @AfterEach
    void releaseEveryLatchAndStopTheExecutor() {
        releaseSlowStop.countDown();
        releaseConsume.countDown();
        executor.shutdownNow();
    }

    private ShardOwnedDurableQueues newDurableQueues() {
        return new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0, new ShardOwnedDurableQueues.StartHook() {
            @Override
            public void beforeStartingAddedConsumer(QueueName queueName) {
                runOnce(beforeStartingAddedConsumer);
            }

            @Override
            public void beforeStartingConsumer(QueueName queueName) {
                runOnce(beforeStartingConsumer);
            }
        });
    }

    private static void runOnce(AtomicReference<Runnable> action) {
        var once = action.getAndSet(null);
        if (once != null) {
            once.run();
        }
    }

    /**
     * Three consumers of a {@code DurableQueues} that is not started, so none of them subscribes yet.
     */
    private static List<DurableQueueConsumer> consumeFromThreeQueues(ShardOwnedDurableQueues durableQueues, String namePrefix) {
        return List.of(durableQueues.consumeFromQueue(consumeFrom(namePrefix + "-1")),
                       durableQueues.consumeFromQueue(consumeFrom(namePrefix + "-2")),
                       durableQueues.consumeFromQueue(consumeFrom(namePrefix + "-3")));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test's latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * A queue whose {@code consume} counts the subscription, waits for {@link #releaseConsume} when it is the
     * {@link #heldUpSubscription}th and, when it is the {@link #failingSubscription}th while {@link #failure} is set, throws
     * a new one of that type. Otherwise it records the queue's name and returns a
     * subscription that is started until it is stopped, and whose {@code stop()} then throws an
     * {@link IllegalStateException} for a queue whose name starts with {@code stop-throws}, and waits for
     * {@link #releaseSlowStop} for one whose name starts with {@code slow-stop}.
     */
    private MessageQueue queue(String name) {
        return proxy(MessageQueue.class, (method, args) -> {
            if (!"consume".equals(method)) {
                throw new UnsupportedOperationException(method);
            }
            var failureType  = failure;
            var subscription = subscriptions.incrementAndGet();
            if (subscription == heldUpSubscription) {
                consumeEntered.countDown();
                await(releaseConsume);
            }
            if (subscription == failingSubscription && failureType != null) {
                switch (failureType) {
                    case "StackOverflowError" -> throw new StackOverflowError("simulated critical error subscribing " + name);
                    case "AssertionError" -> throw new AssertionError("simulated failure subscribing " + name);
                    case "IllegalStateException" -> throw new IllegalStateException("simulated failure subscribing " + name);
                    default -> throw new IllegalArgumentException("Unknown failure type " + failureType);
                }
            }
            subscribed.add(name);
            var started = new boolean[]{true};
            return proxy(Subscription.class, (subscriptionMethod, subscriptionArgs) -> switch (subscriptionMethod) {
                case "isStarted" -> started[0];
                case "stop" -> {
                    if (name.startsWith("slow-stop")) {
                        slowStopEntered.countDown();
                        await(releaseSlowStop);
                    }
                    started[0] = false;
                    stopped.add(name);
                    if (name.startsWith("stop-throws")) {
                        throw new IllegalStateException("simulated failure stopping " + name);
                    }
                    yield null;
                }
                default -> throw new UnsupportedOperationException(subscriptionMethod);
            });
        });
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
