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

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * The observer must never be able to affect message delivery, because it runs on delivery threads.
 */
class DurableQueueMessageObserverTest {
    private static final QueueName QUEUE = QueueName.of("TestQueue");

    @SuppressWarnings("removal")
    private static QueuedMessage message() {
        return new DefaultQueuedMessage(QueueEntryId.random(),
                                        QUEUE,
                                        Message.of("a-payload"),
                                        OffsetDateTime.now(),
                                        OffsetDateTime.now(),
                                        OffsetDateTime.now(),
                                        null,
                                        1,
                                        0,
                                        false,
                                        false);
    }

    private static final class Recording implements DurableQueueMessageObserver {
        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void messageHandled(QueuedMessage message, Duration handlerDuration) {
            calls.add("handled");
        }

        @Override
        public void messageRedeliveryRequested(QueuedMessage message) {
            calls.add("redeliveryRequested");
        }

        @Override
        public void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {
            calls.add("retried");
        }

        @Override
        public void messageDeadLettered(QueuedMessage message, Throwable cause, MessageDeliveryOutcome outcome) {
            calls.add("deadLettered");
        }
    }

    private static final class Exploding implements DurableQueueMessageObserver {
        @Override
        public void messageHandled(QueuedMessage message, Duration handlerDuration) {
            throw new IllegalStateException("observer is broken");
        }

        @Override
        public void messageDeadLettered(QueuedMessage message, Throwable cause, MessageDeliveryOutcome outcome) {
            throw new IllegalStateException("observer is broken");
        }
    }

    @Test
    void none_does_nothing_and_is_the_same_instance_every_time() {
        var none = DurableQueueMessageObserver.none();

        assertThatCode(() -> none.messageHandled(message(), Duration.ofMillis(1))).doesNotThrowAnyException();
        assertThat(DurableQueueMessageObserver.none()).isSameAs(none);
    }

    @Test
    void safe_swallows_everything_the_observer_throws() {
        var safe = DurableQueueMessageObserver.safe(new Exploding());

        assertThatCode(() -> {
            safe.messageHandled(message(), Duration.ofMillis(1));
            safe.messageHandled(message(), Duration.ofMillis(1));
            safe.messageDeadLettered(message(), new IllegalStateException("boom"), MessageDeliveryOutcome.PERMANENT_ERROR);
        }).doesNotThrowAnyException();
    }

    @Test
    void composite_notifies_every_observer() {
        var first  = new Recording();
        var second = new Recording();

        var composite = DurableQueueMessageObserver.composite(List.of(first, second));
        composite.messageHandled(message(), Duration.ofMillis(1));
        composite.messageRetried(message(), new IllegalStateException("boom"), Duration.ofSeconds(1));
        composite.messageDeadLettered(message(), new IllegalStateException("boom"), MessageDeliveryOutcome.PERMANENT_ERROR);
        composite.messageRedeliveryRequested(message());

        assertThat(first.calls).containsExactly("handled", "retried", "deadLettered", "redeliveryRequested");
        assertThat(second.calls).isEqualTo(first.calls);
    }

    @Test
    void one_broken_observer_does_not_stop_the_others() {
        var recording = new Recording();

        var composite = DurableQueueMessageObserver.composite(List.of(new Exploding(), recording));

        assertThatCode(() -> composite.messageHandled(message(), Duration.ofMillis(1))).doesNotThrowAnyException();
        assertThat(recording.calls).containsExactly("handled");
    }

    @Test
    void composite_of_nothing_is_none() {
        assertThat(DurableQueueMessageObserver.composite(List.of()))
                .isSameAs(DurableQueueMessageObserver.none());
    }

    @Test
    void safe_does_not_wrap_twice() {
        var safe = DurableQueueMessageObserver.safe(new Recording());

        assertThat(DurableQueueMessageObserver.safe(safe)).isSameAs(safe);
        assertThat(DurableQueueMessageObserver.safe(DurableQueueMessageObserver.none()))
                .isSameAs(DurableQueueMessageObserver.none());
    }
}
