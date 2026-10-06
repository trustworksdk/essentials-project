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


package dk.trustworks.essentials.components.foundation.messaging.queue.micrometer;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.HandleQueuedMessage;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

class DurableQueuesMicrometerTracingInterceptorTest {

    /**
     * The shard-owned engine's push delivery path hands its consumers a message that throws for the timestamps and
     * attempt counts it does not carry. These were read for span attributes, so with tracing enabled every delivery
     * on that engine failed here - before the handler ran - and was dead-lettered.
     */
    @Test
    @SuppressWarnings("unchecked")
    void a_message_that_cannot_answer_the_optional_span_attributes_is_still_handled() {
        var message = mock(QueuedMessage.class);
        when(message.getQueueName()).thenReturn(QueueName.of("Inbox:Orders"));
        when(message.getId()).thenReturn(QueueEntryId.of("1"));
        when(message.getMetaData()).thenReturn(new MessageMetaData());
        when(message.getAddedTimestamp()).thenThrow(new UnsupportedOperationException("not carried"));
        when(message.getDeliveryTimestamp()).thenThrow(new UnsupportedOperationException("not carried"));
        when(message.getTotalDeliveryAttempts()).thenThrow(new UnsupportedOperationException("not carried"));
        when(message.getRedeliveryAttempts()).thenThrow(new UnsupportedOperationException("not carried"));
        InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> chain = mock(InterceptorChain.class);

        var interceptor = new DurableQueuesMicrometerTracingInterceptor(Tracer.NOOP, Propagator.NOOP, ObservationRegistry.create(), false);

        assertThatNoException().isThrownBy(() -> interceptor.intercept(new HandleQueuedMessage(message, mock(QueuedMessageHandler.class)), chain));
        verify(chain).proceed();
    }
}
