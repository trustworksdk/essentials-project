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
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues;
import dk.trustworks.essentials.components.queue.shardowned.spi.Subscription;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * A subscription's {@code stop()} throws once both of its lanes have stopped when a {@code QueueObserver} throws for a
 * release. The adapter must not let that throw leave anything else running or registered: {@code DurableQueues.stop()}
 * stopped its consumers one after another, so the first throw left the rest consuming; a consumer kept the stopped
 * subscription, so its next {@code start()} returned without consuming; and {@code cancel()} skipped deregistering it.
 * The engine is stood in for by proxies, whose subscriptions all throw from {@code stop()}.
 */
class ShardOwnedDurableQueuesStopTest {

    private final List<String>  consumed = new CopyOnWriteArrayList<>();
    private final List<String>  stopped  = new CopyOnWriteArrayList<>();
    private final MessageQueues queues   = proxy(MessageQueues.class, (method, args) -> switch (method) {
        case "findQueue" -> Optional.of(queue(((dk.trustworks.essentials.components.queue.shardowned.spi.QueueName) args[0]).toString()));
        case "queueNames" -> List.of();
        default -> throw new UnsupportedOperationException(method);
    });

    @Test
    void stop_stops_every_consumer_when_an_earlier_ones_stop_throws_and_throws_the_first_failure() {
        var durableQueues = new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0);
        durableQueues.start();
        var first  = durableQueues.consumeFromQueue(consumeFrom("first"));
        var second = durableQueues.consumeFromQueue(consumeFrom("second"));
        assertThat(consumed).as("premise: both consumers subscribed").containsExactlyInAnyOrder("first", "second");

        var thrown = catchThrowable(durableQueues::stop);

        assertThat(thrown).as("the first stop's failure escapes").isInstanceOf(IllegalStateException.class)
                          .hasMessageStartingWith("simulated observer failure stopping");
        assertThat(thrown.getSuppressed()).as("the second stop's failure is added to it").hasSize(1);
        assertThat(stopped).as("every consumer's subscription was stopped").containsExactlyInAnyOrder("first", "second");
        assertThat(first.isStarted()).isFalse();
        assertThat(second.isStarted()).isFalse();
        assertThat(durableQueues.isStarted()).isFalse();
    }

    /**
     * A non-critical {@link Error} - an observer's {@code AssertionError}, say - from a consumer's stop is collected like a
     * {@code RuntimeException}: only {@code RuntimeException} was caught, so the first such throw left every consumer after
     * it consuming.
     */
    @Test
    void stop_stops_every_consumer_when_an_earlier_ones_stop_throws_an_error_and_throws_the_first_error() {
        var durableQueues = new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0);
        durableQueues.start();
        var first  = durableQueues.consumeFromQueue(consumeFrom("asserting-first"));
        var second = durableQueues.consumeFromQueue(consumeFrom("asserting-second"));

        var thrown = catchThrowable(durableQueues::stop);

        assertThat(thrown).as("the first stop's error escapes").isInstanceOf(AssertionError.class)
                          .hasMessageStartingWith("simulated observer assertion stopping");
        assertThat(thrown.getSuppressed()).as("the second stop's error is added to it").hasSize(1);
        assertThat(stopped).as("every consumer's subscription was stopped").containsExactlyInAnyOrder("asserting-first", "asserting-second");
        assertThat(first.isStarted()).isFalse();
        assertThat(second.isStarted()).isFalse();
        assertThat(durableQueues.isStarted()).isFalse();
    }

    /**
     * A critical error ({@code Exceptions.isCriticalError}) from a consumer's stop ends the stopping at once and escapes as
     * it was thrown, and the {@code DurableQueues} reports itself stopped all the same.
     */
    @Test
    void stop_reports_itself_stopped_when_a_consumers_stop_throws_a_critical_error() {
        var durableQueues = new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0);
        durableQueues.start();
        durableQueues.consumeFromQueue(consumeFrom("critical"));

        var thrown = catchThrowable(durableQueues::stop);

        assertThat(thrown).as("the critical error escapes as it was thrown").isExactlyInstanceOf(StackOverflowError.class)
                          .hasMessage("simulated critical error stopping critical");
        assertThat(thrown.getSuppressed()).isEmpty();
        assertThat(durableQueues.isStarted()).as("the DurableQueues reports itself stopped").isFalse();
    }

    @Test
    void a_consumer_whose_stop_throws_consumes_again_when_started_again() {
        var durableQueues = new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0);
        durableQueues.start();
        var consumer = durableQueues.consumeFromQueue(consumeFrom("restarted"));

        assertThatThrownBy(consumer::stop).hasMessageStartingWith("simulated observer failure stopping");
        consumer.start();

        assertThat(consumed).as("the restart subscribed again").containsExactly("restarted", "restarted");
        assertThat(consumer.isStarted()).isTrue();
    }

    @Test
    void a_consumer_cancelled_while_its_stop_throws_is_deregistered() {
        var durableQueues = new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0);
        durableQueues.start();
        var consumer = durableQueues.consumeFromQueue(consumeFrom("cancelled"));

        assertThatThrownBy(consumer::cancel).hasMessageStartingWith("simulated observer failure stopping");

        assertThat(consumer.isStarted()).isFalse();
        assertThatCode(() -> durableQueues.consumeFromQueue(consumeFrom("cancelled")))
                .as("the cancelled consumer no longer holds the queue")
                .doesNotThrowAnyException();
    }

    /**
     * A queue whose {@code consume} records the queue's name and returns a subscription that is started until it is
     * stopped, and whose {@code stop()} then throws, as one throws when the observer it tells of a release throws - an
     * {@link AssertionError} for a queue whose name starts with {@code asserting}, a {@link StackOverflowError} (a critical
     * error) for one whose name starts with {@code critical}, an {@link IllegalStateException} otherwise.
     */
    private MessageQueue queue(String name) {
        return proxy(MessageQueue.class, (method, args) -> {
            if (!"consume".equals(method)) {
                throw new UnsupportedOperationException(method);
            }
            consumed.add(name);
            var started = new boolean[]{true};
            return proxy(Subscription.class, (subscriptionMethod, subscriptionArgs) -> switch (subscriptionMethod) {
                case "isStarted" -> started[0];
                case "stop" -> {
                    started[0] = false;
                    stopped.add(name);
                    if (name.startsWith("critical")) {
                        throw new StackOverflowError("simulated critical error stopping " + name);
                    }
                    if (name.startsWith("asserting")) {
                        throw new AssertionError("simulated observer assertion stopping " + name);
                    }
                    throw new IllegalStateException("simulated observer failure stopping " + name);
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
