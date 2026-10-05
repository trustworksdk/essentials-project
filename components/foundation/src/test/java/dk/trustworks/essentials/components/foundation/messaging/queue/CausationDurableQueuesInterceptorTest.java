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

import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class CausationDurableQueuesInterceptorTest {
    private static final QueueName QUEUE = QueueName.of("TestQueue");
    private static final EventId   CAUSE = EventId.of("the-cause");

    private final CausationDurableQueuesInterceptor interceptor = new CausationDurableQueuesInterceptor();

    // ----------------------------------------------------------------------------------------------------- queueing

    @Test
    void a_message_queued_with_a_cause_bound_carries_it() {
        var operation = queueMessage(Message.of("payload"));

        CausationContext.where(CAUSE).run(() -> queue(operation));

        assertThat(operation.getMetaData()).containsEntry(MessageMetaData.CAUSED_BY_EVENT_ID, CAUSE.toString());
    }

    @Test
    void a_message_queued_with_no_cause_bound_carries_none() {
        var operation = queueMessage(Message.of("payload"));

        queue(operation);

        assertThat(operation.getMetaData()).doesNotContainKey(MessageMetaData.CAUSED_BY_EVENT_ID);
    }

    @Test
    void a_cause_already_on_the_message_is_kept() {
        var metaData = new MessageMetaData();
        metaData.put(MessageMetaData.CAUSED_BY_EVENT_ID, "applications-own-cause");
        var operation = queueMessage(Message.of("payload", metaData));

        CausationContext.where(CAUSE).run(() -> queue(operation));

        assertThat(operation.getMetaData()).containsEntry(MessageMetaData.CAUSED_BY_EVENT_ID, "applications-own-cause");
    }

    @Test
    void every_message_in_a_batch_carries_the_cause() {
        var operation = QueueMessages.builder()
                                     .setQueueName(QUEUE)
                                     .setMessages(List.of(Message.of("first"), Message.of("second")))
                                     .setDeliveryDelay(Optional.empty())
                                     .build();

        CausationContext.where(CAUSE).run(() -> interceptor.intercept(operation, chain(operation, () -> List.of())));

        assertThat(operation.getMessages()).allSatisfy(message -> assertThat(message.getMetaData())
                .containsEntry(MessageMetaData.CAUSED_BY_EVENT_ID, CAUSE.toString()));
    }

    @Test
    void metadata_backed_by_an_immutable_map_is_queued_without_a_cause_rather_than_failing() {
        var operation = queueMessage(Message.of("payload", new MessageMetaData(Map.of())));

        assertThatCode(() -> CausationContext.where(CAUSE).run(() -> queue(operation))).doesNotThrowAnyException();
        assertThat(operation.getMetaData()).doesNotContainKey(MessageMetaData.CAUSED_BY_EVENT_ID);
    }

    // ------------------------------------------------------------------------------------------------------ delivery

    @Test
    void a_message_carrying_a_cause_is_handled_with_it_bound() {
        var metaData = new MessageMetaData();
        metaData.put(MessageMetaData.CAUSED_BY_EVENT_ID, CAUSE.toString());

        assertThat(causeSeenWhileHandling(queuedMessage(Message.of("payload", metaData)))).contains(CAUSE);
        assertThat(CausationContext.current()).isEmpty();
    }

    @Test
    void a_message_without_a_cause_is_handled_with_whatever_is_bound_around_the_delivery() {
        assertThat(causeSeenWhileHandling(queuedMessage(Message.of("payload")))).isEmpty();
    }

    @Test
    void delivery_reads_nothing_but_the_message_metadata() {
        // The shard-owned engine's delivery path hands over a message whose id and delivery counts throw
        var metaData = new MessageMetaData();
        metaData.put(MessageMetaData.CAUSED_BY_EVENT_ID, CAUSE.toString());
        var partial = new PartialQueuedMessage(Message.of("payload", metaData));

        assertThat(causeSeenWhileHandling(partial)).contains(CAUSE);
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private QueueMessage queueMessage(Message message) {
        return QueueMessage.builder()
                           .setQueueName(QUEUE)
                           .setMessage(message)
                           .build();
    }

    private void queue(QueueMessage operation) {
        interceptor.intercept(operation, chain(operation, QueueEntryId::random));
    }

    private Optional<EventId> causeSeenWhileHandling(QueuedMessage message) {
        var seen      = new AtomicReference<Optional<EventId>>();
        var operation = new HandleQueuedMessage(message, ignored -> seen.set(CausationContext.current()));
        interceptor.intercept(operation, chain(operation, () -> {
            operation.messageHandler.handle(message);
            return (Void) null;
        }));
        return seen.get();
    }

    private <OPERATION, RESULT> InterceptorChain<OPERATION, RESULT, DurableQueuesInterceptor> chain(OPERATION operation, java.util.function.Supplier<RESULT> defaultBehaviour) {
        return InterceptorChain.newInterceptorChainForOperation(operation, List.of(), (interceptor, chain) -> chain.proceed(), defaultBehaviour);
    }

    /**
     * The shape the shard-owned engine delivers: the message is there, nothing else is
     */
    private record PartialQueuedMessage(Message message) implements QueuedMessage {
        @Override
        public Message getMessage() {
            return message;
        }

        @Override
        public QueueName getQueueName() {
            return QUEUE;
        }

        @Override
        public QueueEntryId getId() {
            throw new UnsupportedOperationException("No id on the delivery path");
        }

        @Override
        public OffsetDateTime getAddedTimestamp() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OffsetDateTime getNextDeliveryTimestamp() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getLastDeliveryError() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isDeadLetterMessage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getTotalDeliveryAttempts() {
            throw new UnsupportedOperationException("No delivery count on the delivery path");
        }

        @Override
        public int getRedeliveryAttempts() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DeliveryMode getDeliveryMode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isBeingDelivered() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OffsetDateTime getDeliveryTimestamp() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markForRedeliveryIn(java.time.Duration deliveryDelay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isManuallyMarkedForRedelivery() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.time.Duration getRedeliveryDelay() {
            throw new UnsupportedOperationException();
        }
    }

    private static QueuedMessage queuedMessage(Message message) {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.random())
                                   .setQueueName(QUEUE)
                                   .setMessage(message)
                                   .setAddedTimestamp(OffsetDateTime.now())
                                   .setNextDeliveryTimestamp(OffsetDateTime.now())
                                   .setDeliveryTimestamp(OffsetDateTime.now())
                                   .setTotalDeliveryAttempts(1)
                                   .setRedeliveryAttempts(0)
                                   .build();
    }
}
