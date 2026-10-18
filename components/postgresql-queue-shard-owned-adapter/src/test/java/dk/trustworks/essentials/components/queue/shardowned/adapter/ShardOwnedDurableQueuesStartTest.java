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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
        var executor = Executors.newSingleThreadExecutor();
        try {
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
        } finally {
            releaseSlowStop.countDown();
            executor.shutdownNow();
        }
    }

    private ShardOwnedDurableQueues newDurableQueues() {
        return new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0);
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
     * A queue whose {@code consume} counts the subscription and, when it is the {@link #failingSubscription}th while
     * {@link #failure} is set, throws a new one of that type. Otherwise it records the queue's name and returns a
     * subscription that is started until it is stopped, and whose {@code stop()} then throws an
     * {@link IllegalStateException} for a queue whose name starts with {@code stop-throws}, and waits for
     * {@link #releaseSlowStop} for one whose name starts with {@code slow-stop}.
     */
    private MessageQueue queue(String name) {
        return proxy(MessageQueue.class, (method, args) -> {
            if (!"consume".equals(method)) {
                throw new UnsupportedOperationException(method);
            }
            var failureType = failure;
            if (subscriptions.incrementAndGet() == failingSubscription && failureType != null) {
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
