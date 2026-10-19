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
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueueConsumer.Holder;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueueConsumer.Holder.*;
import static dk.trustworks.essentials.components.queue.shardowned.adapter.TestProxies.proxy;
import static org.assertj.core.api.Assertions.*;

/**
 * A consumer's lifecycle {@link Holder} moves only along the edges of the diagram in {@code Holder}'s javadoc, and the
 * rule lives once, in {@link ShardOwnedDurableQueueConsumer#moveHolder}: any other move leaves the holder where it
 * stands. Unlike {@code PostgresqlMessageQueue}'s holder, a stop arriving while a start finishes an owed stop owes
 * nothing further: this consumer's stop without the lock stops nothing, and the start leaves the consumer not started.
 */
class ShardOwnedDurableQueueConsumerHolderTest {

    private static final Map<Holder, Set<Holder>> EDGES = Map.of(
            NONE, EnumSet.of(STARTING, STOPPING),
            STARTING, EnumSet.of(STOP_OWED, NONE),
            STOP_OWED, EnumSet.of(FINISHING_OWED_STOP, NONE),
            FINISHING_OWED_STOP, EnumSet.of(NONE),
            STOPPING, EnumSet.of(NONE));

    /**
     * How each holder is reached from {@link Holder#NONE}, along the diagram.
     */
    private static final Map<Holder, List<Holder>> PATH_FROM_NONE = Map.of(
            NONE, List.of(),
            STARTING, List.of(STARTING),
            STOP_OWED, List.of(STARTING, STOP_OWED),
            FINISHING_OWED_STOP, List.of(STARTING, STOP_OWED, FINISHING_OWED_STOP),
            STOPPING, List.of(STOPPING));

    @ParameterizedTest
    @EnumSource(Holder.class)
    void the_holder_moves_only_along_the_diagrams_edges_and_reports_where_it_was(Holder start) {
        for (var target : Holder.values()) {
            var consumer = consumerHeldBy(start);

            var was = consumer.moveHolder(target);

            assertThat(was).isEqualTo(start);
            assertThat(consumer.holder()).as("%s asked for %s", start, target)
                                         .isEqualTo(EDGES.get(start).contains(target) ? target : start);
            assertThat(start.canMoveTo(target)).as("%s.canMoveTo(%s)", start, target).isEqualTo(EDGES.get(start).contains(target));
        }
    }

    @Test
    void a_stop_arriving_while_the_start_finishes_an_owed_stop_owes_nothing_further() {
        var consumer = consumerHeldBy(FINISHING_OWED_STOP);

        consumer.moveHolder(STOP_OWED);

        assertThat(consumer.holder()).isEqualTo(FINISHING_OWED_STOP);
    }

    @Test
    void a_new_consumer_is_held_by_none() {
        assertThat(consumer().holder()).isEqualTo(NONE);
    }

    @Test
    void the_diagram_names_every_holder() {
        assertThat(EDGES.keySet()).containsExactlyInAnyOrder(Holder.values());
        assertThat(PATH_FROM_NONE.keySet()).containsExactlyInAnyOrder(Holder.values());
    }

    private static ShardOwnedDurableQueueConsumer consumerHeldBy(Holder holder) {
        var consumer = consumer();
        for (var step : PATH_FROM_NONE.get(holder)) {
            consumer.moveHolder(step);
        }
        assertThat(consumer.holder()).as("set-up").isEqualTo(holder);
        return consumer;
    }

    private static ShardOwnedDurableQueueConsumer consumer() {
        // Nothing here reaches the queue: the holder is moved directly.
        var queue = proxy(MessageQueue.class, (method, args) -> {
            throw new UnsupportedOperationException(method);
        });
        return new ShardOwnedDurableQueueConsumer(ConsumeFromQueue.builder()
                                                                  .setQueueName(QueueName.of("holder"))
                                                                  .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 3))
                                                                  .setParallelConsumers(1)
                                                                  .setQueueMessageHandler(message -> { })
                                                                  .build(),
                                                  queue,
                                                  EssentialsObjectMappers.createJSONSerializer(),
                                                  cancelled -> { },
                                                  (message, handler) -> handler.handle(message));
    }
}
