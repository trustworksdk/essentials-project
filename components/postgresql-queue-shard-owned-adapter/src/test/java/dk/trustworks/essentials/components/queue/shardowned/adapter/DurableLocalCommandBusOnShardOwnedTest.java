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
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues;
import dk.trustworks.essentials.components.queue.shardowned.spi.Subscription;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.components.queue.shardowned.adapter.TestProxies.proxy;
import static org.assertj.core.api.Assertions.*;

/**
 * A {@link DurableLocalCommandBus} on {@link ShardOwnedDurableQueues} can be stopped and started again. A shard-owned
 * consumer stopped through its own {@code stop()} stays registered - so the {@code DurableQueues}' next {@code start()}
 * starts it again - and only {@code cancel()} deregisters it. The bus used to {@code stop()} its consumer, so its next
 * {@code start()}, which consumes from the command queue anew, was refused with "There is already a consumer on queue",
 * and a stop and start of the {@code DurableQueues} resubscribed the stopped bus's consumer. The engine is stood in for
 * by proxies.
 */
class DurableLocalCommandBusOnShardOwnedTest {
    private static final QueueName COMMAND_QUEUE = QueueName.of("commands");

    private final List<String>  subscribed = new CopyOnWriteArrayList<>();
    private final List<String>  stopped    = new CopyOnWriteArrayList<>();
    private final MessageQueues queues     = proxy(MessageQueues.class, (method, args) -> switch (method) {
        case "findQueue" -> Optional.of(queue(((dk.trustworks.essentials.components.queue.shardowned.spi.QueueName) args[0]).toString()));
        case "queueNames" -> List.of();
        default -> throw new UnsupportedOperationException(method);
    });

    @Test
    void a_stopped_command_bus_starts_again_with_one_consumer_on_its_queue() {
        var durableQueues = newDurableQueues();
        durableQueues.start();
        var commandBus = newCommandBus(durableQueues);
        commandBus.start();
        assertThat(durableQueues.getActiveQueueNames()).as("premise: the bus consumes from its queue").containsExactly(COMMAND_QUEUE);

        commandBus.stop();

        assertThat(durableQueues.getActiveQueueNames()).as("the stopped bus no longer consumes").isEmpty();
        assertThat(stopped).as("the bus's subscription was stopped").containsExactly(COMMAND_QUEUE.toString());

        assertThatCode(commandBus::start).as("the bus starts again").doesNotThrowAnyException();

        assertThat(commandBus.isStarted()).isTrue();
        assertThat(durableQueues.getActiveQueueNames()).as("one consumer on the bus's queue").containsExactly(COMMAND_QUEUE);
        assertThat(subscribed).as("the restarted bus subscribed again").containsExactly(COMMAND_QUEUE.toString(), COMMAND_QUEUE.toString());
        commandBus.stop();
    }

    @Test
    void a_restart_of_the_durable_queues_does_not_start_a_stopped_command_bus_consuming() {
        var durableQueues = newDurableQueues();
        durableQueues.start();
        var commandBus = newCommandBus(durableQueues);
        commandBus.start();
        commandBus.stop();

        durableQueues.stop();
        durableQueues.start();

        assertThat(subscribed).as("the stopped bus's consumer was not subscribed again").containsExactly(COMMAND_QUEUE.toString());
        assertThat(durableQueues.getActiveQueueNames()).isEmpty();
        assertThat(commandBus.isStarted()).isFalse();
    }

    private ShardOwnedDurableQueues newDurableQueues() {
        return new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0);
    }

    private static DurableLocalCommandBus newCommandBus(ShardOwnedDurableQueues durableQueues) {
        return DurableLocalCommandBus.builder()
                                     .setDurableQueues(durableQueues)
                                     .setCommandQueueName(COMMAND_QUEUE)
                                     .setParallelSendAndDontWaitConsumers(1)
                                     .build();
    }

    /**
     * A queue whose {@code consume} records the queue's name and returns a subscription that is started until it is
     * unsubscribed, which records the queue's name again.
     */
    private MessageQueue queue(String name) {
        return proxy(MessageQueue.class, (method, args) -> {
            if (!"consume".equals(method)) {
                throw new UnsupportedOperationException(method);
            }
            subscribed.add(name);
            var started = new boolean[]{true};
            return proxy(Subscription.class, (subscriptionMethod, subscriptionArgs) -> switch (subscriptionMethod) {
                case "isStarted" -> started[0];
                case "unsubscribe" -> {
                    started[0] = false;
                    stopped.add(name);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(subscriptionMethod);
            });
        });
    }
}
