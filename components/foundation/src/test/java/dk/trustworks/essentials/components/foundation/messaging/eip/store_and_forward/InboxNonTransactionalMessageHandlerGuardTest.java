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
 * A {@link UnitOfWorkMode#NONE} handler needs a window where no {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
 * is active. Under {@link TransactionalMode#FullyTransactional} the {@link DurableQueues} consumer wraps fetching,
 * handling and acknowledgement in one shared UnitOfWork, so no such window exists and the {@link Inbox} must reject
 * the consumer at wiring time instead of silently running the blocking call inside a database transaction.
 */
class InboxNonTransactionalMessageHandlerGuardTest {

    @Test
    void a_consumer_with_NONE_handlers_is_rejected_under_FullyTransactional() {
        var inboxes = inboxes(TransactionalMode.FullyTransactional);
        var inbox   = inboxes.getOrCreateInbox(inboxConfig());

        assertThatThrownBy(() -> inbox.setMessageConsumer(new BoundaryOwningConsumer(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnitOfWorkMode.NONE")
                .hasMessageContaining(TransactionalMode.SingleOperationTransaction.name());
    }

    @Test
    void a_consumer_without_NONE_handlers_is_accepted_under_FullyTransactional() {
        var inboxes = inboxes(TransactionalMode.FullyTransactional);
        var inbox   = inboxes.getOrCreateInbox(inboxConfig());

        assertThatNoException().isThrownBy(() -> inbox.setMessageConsumer(new BoundaryOwningConsumer(false)));
    }

    @Test
    void a_consumer_with_NONE_handlers_is_accepted_under_SingleOperationTransaction() {
        var inboxes = inboxes(TransactionalMode.SingleOperationTransaction);
        var inbox   = inboxes.getOrCreateInbox(inboxConfig());

        assertThatNoException().isThrownBy(() -> inbox.setMessageConsumer(new BoundaryOwningConsumer(true)));
    }

    @Test
    void a_plain_Consumer_is_unaffected_by_the_guard() {
        var inboxes = inboxes(TransactionalMode.FullyTransactional);
        var inbox   = inboxes.getOrCreateInbox(inboxConfig());

        assertThatNoException().isThrownBy(() -> inbox.setMessageConsumer(message -> {
        }));
    }

    @Test
    void a_consumer_carrying_its_own_NONE_handlers_is_detected_without_reporting_it_by_hand() {
        var inboxes = inboxes(TransactionalMode.FullyTransactional);
        var inbox   = inboxes.getOrCreateInbox(inboxConfig());

        assertThatThrownBy(() -> inbox.setMessageConsumer(new SelfHostingBoundaryOwningConsumer()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnitOfWorkMode.NONE");
    }

    /**
     * The {@link Inbox} wraps every delivery in a {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
     * unless the consumer owns the boundary, so {@link UnitOfWorkMode#NONE} could never take effect - reject rather
     * than silently run the blocking call inside that transaction
     */
    @Test
    void a_consumer_that_does_not_own_the_boundary_is_rejected_when_the_Inbox_would_open_a_UnitOfWork() {
        var inboxes = inboxes(TransactionalMode.SingleOperationTransaction, true);
        var inbox   = inboxes.getOrCreateInbox(inboxConfig());

        assertThatThrownBy(() -> inbox.setMessageConsumer(new PatternMatchingMessageHandler(new NonTransactionalHandlers())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnitOfWorkMode.NONE")
                .hasMessageContaining(UnitOfWorkBoundaryOwningMessageConsumer.class.getSimpleName());
    }

    @Test
    void a_consumer_that_does_not_own_the_boundary_is_accepted_when_there_is_no_UnitOfWorkFactory() {
        // Without a UnitOfWorkFactory the Inbox doesn't open a UnitOfWork around the delivery either, so the handler
        // does get the UnitOfWork-free window it asked for
        var inboxes = inboxes(TransactionalMode.SingleOperationTransaction, false);
        var inbox   = inboxes.getOrCreateInbox(inboxConfig());

        assertThatNoException().isThrownBy(() -> inbox.setMessageConsumer(new PatternMatchingMessageHandler(new NonTransactionalHandlers())));
    }

    private static Inboxes inboxes(TransactionalMode transactionalMode) {
        return inboxes(transactionalMode, false);
    }

    @SuppressWarnings("unchecked")
    private static Inboxes inboxes(TransactionalMode transactionalMode, boolean withUnitOfWorkFactory) {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.getTransactionalMode()).thenReturn(transactionalMode);
        if (withUnitOfWorkFactory) {
            when(durableQueues.getUnitOfWorkFactory()).thenReturn(Optional.of(mock(UnitOfWorkFactory.class)));
        }
        return Inboxes.durableQueueBasedInboxes(durableQueues,
                                                mock(FencedLockManager.class));
    }

    private static InboxConfig inboxConfig() {
        return InboxConfig.builder()
                          .inboxName(InboxName.of("TestInbox"))
                          .messageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                          .numberOfParallelMessageConsumers(1)
                          .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(10), 3))
                          .build();
    }

    /**
     * Stand-in for the consumer an {@code EventProcessor} hands to its {@link Inbox}: its handler methods live on
     * another object, so it reports on their behalf instead of using the introspecting default
     */
    private record BoundaryOwningConsumer(boolean hasNonTransactionalMessageHandlers) implements UnitOfWorkBoundaryOwningMessageConsumer {
        @Override
        public void accept(Message message) {
        }
    }

    /**
     * A boundary-owning consumer that carries its {@literal @MessageHandler} methods itself, and therefore relies on
     * {@link UnitOfWorkBoundaryOwningMessageConsumer#hasNonTransactionalMessageHandlers()} introspecting them
     */
    private static class SelfHostingBoundaryOwningConsumer implements UnitOfWorkBoundaryOwningMessageConsumer {
        @Override
        public void accept(Message message) {
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(BlockingIOEvent e) {
        }
    }

    private static class NonTransactionalHandlers {
        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(BlockingIOEvent e) {
        }
    }

    private record BlockingIOEvent() {
    }
}
