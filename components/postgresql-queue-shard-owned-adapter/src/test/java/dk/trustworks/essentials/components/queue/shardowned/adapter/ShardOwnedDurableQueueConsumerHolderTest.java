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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueueConsumer.Holder;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueueConsumer.Holder.*;
import static dk.trustworks.essentials.components.queue.shardowned.adapter.TestProxies.proxy;
import static org.assertj.core.api.Assertions.*;

/**
 * A consumer's lifecycle {@link Holder} moves only along the edges of the diagram in {@code Holder}'s javadoc, and the
 * rule lives once, in {@link ShardOwnedDurableQueueConsumer#moveHolder}: any other move leaves the holder where it
 * stands, and is logged at ERROR, as the bug in the consumer's lifecycle it is; a move to where the holder stands is no
 * move and says nothing. Unlike {@code PostgresqlMessageQueue}'s holder, a stop arriving while a start finishes an owed stop owes
 * nothing further: this consumer's stop without the lock stops nothing, and the start leaves the consumer not started.
 */
class ShardOwnedDurableQueueConsumerHolderTest {

    private ListAppender<ILoggingEvent>   logged;
    private ch.qos.logback.classic.Logger consumerLogger;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        consumerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardOwnedDurableQueueConsumer.class);
        consumerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        consumerLogger.detachAppender(logged);
    }

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
    void the_holder_moves_only_along_the_diagrams_edges_and_any_other_move_is_logged_at_error(Holder start) {
        for (var target : Holder.values()) {
            var consumer = consumerHeldBy(start);
            logged.list.clear();

            consumer.moveHolder(target);

            var onTheDiagram = EDGES.get(start).contains(target);
            assertThat(consumer.holder()).as("%s asked for %s", start, target)
                                         .isEqualTo(onTheDiagram ? target : start);
            assertThat(start.canMoveTo(target)).as("%s.canMoveTo(%s)", start, target).isEqualTo(onTheDiagram);
            if (onTheDiagram || target == start) {
                assertThat(logged.list).as("%s asked for %s", start, target).isEmpty();
            } else {
                assertThat(logged.list).as("%s asked for %s", start, target).singleElement().satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("'holder'", "from " + start + " to " + target);
                    assertThat(event.getThrowableProxy()).as("the stack naming the caller").isNotNull();
                });
            }
        }
    }

    /**
     * {@link ShardOwnedDurableQueueConsumer#stop()} never asks for this move: it marks a stop owed from {@link Holder#STARTING}
     * alone, and returns on {@code FINISHING_OWED_STOP}. Asked for regardless, it is the bug it is logged as.
     */
    @Test
    void a_stop_arriving_while_the_start_finishes_an_owed_stop_owes_nothing_further() {
        var consumer = consumerHeldBy(FINISHING_OWED_STOP);
        logged.list.clear();

        consumer.moveHolder(STOP_OWED);

        assertThat(consumer.holder()).isEqualTo(FINISHING_OWED_STOP);
        assertThat(logged.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void a_move_to_where_the_holder_stands_logs_nothing() {
        for (var start : Holder.values()) {
            var consumer = consumerHeldBy(start);
            logged.list.clear();

            consumer.moveHolder(start);

            assertThat(consumer.holder()).isEqualTo(start);
            assertThat(logged.list).as("%s asked for %s", start, start).isEmpty();
        }
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
