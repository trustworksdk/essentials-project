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
package dk.trustworks.essentials.components.foundation.messaging.queue;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The built-in permanent-error list of {@link DefaultDurableQueueConsumer}: failures no redelivery can fix go to the
 * dead-letter queue on the first attempt. JSON that cannot be bound to the message type is one of them - for both
 * Jackson majors. Up to 0.50.0 only the Jackson 2 exception was recognised, so under the default Jackson 3 flavor such
 * a message was redelivered until the redelivery policy gave up.
 */
class DefaultDurableQueueConsumerPermanentErrorTest {
    private DefaultDurableQueueConsumer<DurableQueues, UnitOfWork, UnitOfWorkFactory<UnitOfWork>> consumer;
    private QueuedMessage                                                                          queuedMessage;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        var consumeFromQueue = ConsumeFromQueue.builder()
                                               .setQueueName(QueueName.of("permanent-error-test"))
                                               .setConsumerName("permanent-error-test")
                                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 5))
                                               .setParallelConsumers(1)
                                               .setQueueMessageHandler(message -> {
                                               })
                                               .build();
        // Never started: only the error classification is under test. The class is abstract without abstract
        // methods, so an empty subclass is all it takes.
        consumer = new DefaultDurableQueueConsumer<>(consumeFromQueue,
                                                     (UnitOfWorkFactory<UnitOfWork>) mock(UnitOfWorkFactory.class),
                                                     mock(DurableQueues.class),
                                                     removed -> {
                                                     },
                                                     100,
                                                     QueuePollingOptimizer.None(),
                                                     List.of()) {
        };
        queuedMessage = mock(QueuedMessage.class);
    }

    @Test
    void a_jackson_3_mismatched_input_is_a_permanent_error() {
        var failure = new RuntimeException("handler failed",
                                           tools.jackson.databind.exc.MismatchedInputException.from((tools.jackson.core.JsonParser) null, String.class, "boom"));

        assertThat(consumer.isPermanentError(queuedMessage, failure)).isTrue();
    }

    @Test
    void a_jackson_2_mismatched_input_is_still_a_permanent_error() {
        var failure = new RuntimeException("handler failed",
                                           com.fasterxml.jackson.databind.exc.MismatchedInputException.from((com.fasterxml.jackson.core.JsonParser) null, String.class, "boom"));

        assertThat(consumer.isPermanentError(queuedMessage, failure)).isTrue();
    }

    @Test
    void a_transient_failure_is_not_a_permanent_error() {
        assertThat(consumer.isPermanentError(queuedMessage, new IllegalStateException("database briefly unavailable"))).isFalse();
    }
}
