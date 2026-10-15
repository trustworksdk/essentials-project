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

package dk.trustworks.essentials.components.foundation.messaging.queue.operations;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.HandleQueuedMessage.ConsumerState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HandleQueuedMessageTest {

    @Test
    void the_consumer_state_follows_the_live_view() {
        assertThat(handleQueuedMessage(() -> true).consumerState()).isEqualTo(ConsumerState.STARTED);
        assertThat(handleQueuedMessage(() -> false).consumerState()).isEqualTo(ConsumerState.STOPPING);
    }

    @Test
    void the_consumer_state_is_unknown_without_a_live_view() {
        var built       = handleQueuedMessage(null);
        var constructed = new HandleQueuedMessage(mock(QueuedMessage.class), mock(QueuedMessageHandler.class));

        assertThat(built.consumerState()).isEqualTo(ConsumerState.UNKNOWN);
        assertThat(constructed.consumerState()).isEqualTo(ConsumerState.UNKNOWN);
    }

    @Test
    void the_consumer_state_is_read_once_per_call() {
        // A consumer that is stopping on the first read and started again from then on (a restart): one call gives one
        // consistent answer, where two separate reads could each see a different state
        var reads     = new AtomicInteger();
        var operation = handleQueuedMessage(() -> reads.getAndIncrement() != 0);

        assertThat(operation.consumerState()).isEqualTo(ConsumerState.STOPPING);
        assertThat(reads.get()).isEqualTo(1);
    }

    private static HandleQueuedMessage handleQueuedMessage(BooleanSupplier consumerStarted) {
        return HandleQueuedMessage.builder()
                                  .setMessage(mock(QueuedMessage.class))
                                  .setMessageHandler(mock(QueuedMessageHandler.class))
                                  .setConsumerStarted(consumerStarted)
                                  .build();
    }
}
