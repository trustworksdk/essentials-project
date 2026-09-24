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

package dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward;

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * An {@link Outbox} always wraps message delivery in a {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
 * of its own, so it can never offer the {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}-free
 * window a {@link UnitOfWorkMode#NONE} handler needs. It must reject such a consumer at wiring time instead of
 * silently running the blocking call inside a database transaction.
 */
class OutboxNonTransactionalMessageHandlerGuardTest {

    @Test
    void a_consumer_with_NONE_handlers_is_rejected() {
        var outbox = outboxes(true).getOrCreateOutbox(outboxConfig());

        assertThatThrownBy(() -> outbox.setMessageConsumer(new PatternMatchingMessageHandler(new NonTransactionalHandlers())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnitOfWorkMode.NONE")
                .hasMessageContaining("Outbox");
    }

    @Test
    void a_consumer_with_NONE_handlers_is_accepted_when_there_is_no_UnitOfWorkFactory() {
        // Without a UnitOfWorkFactory the Outbox doesn't open a UnitOfWork around the delivery either, so the handler
        // does get the UnitOfWork-free window it asked for
        var outbox = outboxes(false).getOrCreateOutbox(outboxConfig());

        assertThatNoException().isThrownBy(() -> outbox.setMessageConsumer(new PatternMatchingMessageHandler(new NonTransactionalHandlers())));
    }

    @Test
    void a_plain_Consumer_is_unaffected_by_the_guard() {
        var outbox = outboxes(true).getOrCreateOutbox(outboxConfig());

        assertThatNoException().isThrownBy(() -> outbox.setMessageConsumer(message -> {
        }));
    }

    @SuppressWarnings("unchecked")
    private static Outboxes outboxes(boolean withUnitOfWorkFactory) {
        var durableQueues = mock(DurableQueues.class);
        if (withUnitOfWorkFactory) {
            when(durableQueues.getUnitOfWorkFactory()).thenReturn(Optional.of(mock(UnitOfWorkFactory.class)));
        }
        return Outboxes.durableQueueBasedOutboxes(durableQueues,
                                                  mock(FencedLockManager.class));
    }

    private static OutboxConfig outboxConfig() {
        return OutboxConfig.builder()
                           .setOutboxName(OutboxName.of("TestOutbox"))
                           .setMessageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                           .setNumberOfParallelMessageConsumers(1)
                           .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 3))
                           .build();
    }

    private static class NonTransactionalHandlers {
        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(BlockingIOEvent e) {
        }
    }

    private record BlockingIOEvent() {
    }
}
