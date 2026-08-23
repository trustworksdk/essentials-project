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

import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BatchedAcknowledgementBufferTest {
    private static final Duration HANDLING_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void acknowledgements_are_coalesced_into_one_call_when_the_batch_fills() {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.acknowledgeMessagesAsHandled(anyCollection())).thenAnswer(invocation -> ((Collection<?>) invocation.getArgument(0)).size());

        var buffer = new BatchedAcknowledgementBuffer(durableQueues, 3, Duration.ofSeconds(1), HANDLING_TIMEOUT);

        buffer.acknowledge(QueueEntryId.of("1"));
        buffer.acknowledge(QueueEntryId.of("2"));
        // Nothing may reach the queues before the batch is full - a buffer that flushes per message is the
        // very thing being removed, and it would still pass a test that only checked the total.
        verify(durableQueues, never()).acknowledgeMessagesAsHandled(anyCollection());

        buffer.acknowledge(QueueEntryId.of("3"));

        var captor = ArgumentCaptor.forClass(Collection.class);
        verify(durableQueues, times(1)).acknowledgeMessagesAsHandled(captor.capture());
        assertThat(captor.getValue()).containsExactly(QueueEntryId.of("1"), QueueEntryId.of("2"), QueueEntryId.of("3"));
        assertThat(buffer.pendingAcknowledgements()).isZero();
        assertThat(buffer.flushCount()).isEqualTo(1);
    }

    @Test
    void stop_flushes_what_is_still_buffered() {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.acknowledgeMessagesAsHandled(anyCollection())).thenAnswer(invocation -> ((Collection<?>) invocation.getArgument(0)).size());

        var buffer = new BatchedAcknowledgementBuffer(durableQueues, 100, Duration.ofSeconds(1), HANDLING_TIMEOUT);
        buffer.start();
        buffer.acknowledge(QueueEntryId.of("1"));
        buffer.acknowledge(QueueEntryId.of("2"));

        // Without the final flush on stop, both messages would still look in-flight and be redelivered after
        // the handling timeout - a graceful shutdown would cause duplicate delivery.
        buffer.stop();

        verify(durableQueues, times(1)).acknowledgeMessagesAsHandled(anyCollection());
        assertThat(buffer.pendingAcknowledgements()).isZero();
        assertThat(buffer.isStarted()).isFalse();
    }

    @Test
    void a_failing_flush_does_not_requeue_the_batch_and_does_not_propagate() {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.acknowledgeMessagesAsHandled(anyCollection())).thenThrow(new RuntimeException("DB is gone"));

        var buffer = new BatchedAcknowledgementBuffer(durableQueues, 2, Duration.ofSeconds(1), HANDLING_TIMEOUT);
        buffer.start();

        // The ids are dropped on purpose: their rows still carry is_being_delivered = TRUE, so
        // resetMessagesStuckBeingDelivered recovers them. Requeueing would turn one poisoned batch into an
        // endless retry that blocks every later acknowledgement behind it.
        assertThatNoException().isThrownBy(() -> {
            buffer.acknowledge(QueueEntryId.of("1"));
            buffer.acknowledge(QueueEntryId.of("2"));
        });
        assertThat(buffer.pendingAcknowledgements()).isZero();

        buffer.stop();
    }

    @Test
    void a_flush_interval_that_could_race_the_stuck_message_reset_is_rejected() {
        var durableQueues = mock(DurableQueues.class);

        // At or below a quarter of the handling timeout is accepted...
        assertThatNoException().isThrownBy(() -> new BatchedAcknowledgementBuffer(durableQueues, 64, Duration.ofSeconds(5), Duration.ofSeconds(20)));

        // ...above it the stuck-message reset can resurrect a message whose acknowledgement is merely
        // buffered, delivering it a second time. That is a configuration bug and must fail loudly at
        // construction rather than silently duplicate messages in production.
        assertThatThrownBy(() -> new BatchedAcknowledgementBuffer(durableQueues, 64, Duration.ofSeconds(6), Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long relative to the messageHandlingTimeout");
    }

    @Test
    void the_settings_reject_a_non_positive_batch_size() {
        assertThatThrownBy(() -> new BatchedAcknowledgementSettings(true, 0, Duration.ofMillis(50)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_default_batch_acknowledge_implementation_falls_back_to_acknowledging_one_at_a_time() {
        // Every DurableQueues implementation that does not override the batch operation must still behave
        // correctly - the default exists so an existing backend keeps working, not so it silently no-ops.
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.acknowledgeMessagesAsHandled(any(AcknowledgeMessagesAsHandled.class))).thenCallRealMethod();
        when(durableQueues.acknowledgeMessageAsHandled(any(AcknowledgeMessageAsHandled.class))).thenReturn(true, false, true);

        var acknowledged = durableQueues.acknowledgeMessagesAsHandled(
                new AcknowledgeMessagesAsHandled(List.of(QueueEntryId.of("1"), QueueEntryId.of("2"), QueueEntryId.of("3"))));

        // Only the ones the single-message path reported as acknowledged are counted.
        assertThat(acknowledged).isEqualTo(2);
        verify(durableQueues, times(3)).acknowledgeMessageAsHandled(any(AcknowledgeMessageAsHandled.class));
    }

    @Test
    void an_empty_batch_is_rejected_rather_than_issued_as_a_no_op_statement() {
        assertThatThrownBy(() -> new AcknowledgeMessagesAsHandled(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
