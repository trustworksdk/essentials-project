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

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

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
    private final MessageQueue     queue         = proxy(MessageQueue.class, (method, args) -> {
        if (!"consume".equals(method)) {
            throw new UnsupportedOperationException(method);
        }
        consuming.countDown();
        awaitUninterruptibly(letConsumeEnd);
        var started = new boolean[]{true};
        return proxy(Subscription.class, (subscriptionMethod, subscriptionArgs) -> switch (subscriptionMethod) {
            case "isStarted" -> started[0];
            case "stop" -> {
                started[0] = false;
                stopped.add("subscription");
                if (stopThrows.get()) {
                    throw new IllegalStateException("simulated observer failure stopping");
                }
                yield null;
            }
            default -> throw new UnsupportedOperationException(subscriptionMethod);
        });
    });
    private final ShardOwnedDurableQueueConsumer consumer = new ShardOwnedDurableQueueConsumer(consumeFrom("stuck"), queue,
                                                                                                EssentialsObjectMappers.createJSONSerializer(),
                                                                                                cancelled -> { },
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

    @Test
    void a_stop_after_the_start_has_subscribed_stops_the_subscription_itself() {
        letConsumeEnd.countDown();
        consumer.start();
        assertThat(consumer.isStarted()).as("premise: started").isTrue();

        consumer.stop();

        assertThat(stopped).containsExactly("subscription");
        assertThat(consumer.isStarted()).isFalse();
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

    @FunctionalInterface
    private interface Invocation {
        Object handle(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> switch (method.getName()) {
            case "toString" -> type.getSimpleName() + " proxy";
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> invocation.handle(method.getName(), args);
        }));
    }
}
