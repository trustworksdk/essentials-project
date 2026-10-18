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

package dk.trustworks.essentials.components.foundation.reactive.command;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DurableLocalCommandBus#stop()} cancels its {@link DurableQueueConsumer} rather than only stopping it, because
 * the bus's next {@link DurableLocalCommandBus#start()} consumes from the command queue anew. A consumer that is only
 * stopped may stay registered with its {@link DurableQueues} - a {@code ShardOwnedDurableQueues} consumer does, so that
 * the {@code DurableQueues}' next {@code start()} starts it again - and then refuses that second consumer for the queue.
 */
class DurableLocalCommandBusStopTest {

    @Test
    void stop_cancels_the_command_queue_consumer_so_a_restart_can_consume_from_the_queue_again() {
        var durableQueues    = mock(DurableQueues.class);
        var firstConsumer    = mock(DurableQueueConsumer.class);
        var secondConsumer   = mock(DurableQueueConsumer.class);
        when(durableQueues.consumeFromQueue(any(QueueName.class), any(), anyInt(), any())).thenReturn(firstConsumer, secondConsumer);
        var commandBus = new DurableLocalCommandBus(durableQueues);
        commandBus.start();

        commandBus.stop();

        verify(firstConsumer).cancel();
        verify(firstConsumer, never()).stop();

        commandBus.start();
        commandBus.stop();

        verify(durableQueues, times(2)).consumeFromQueue(eq(DurableLocalCommandBus.DEFAULT_COMMAND_QUEUE_NAME), any(), anyInt(), any());
        verify(secondConsumer).cancel();
    }
}
