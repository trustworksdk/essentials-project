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
import org.junit.jupiter.api.Test;

import java.time.Duration;

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

    private static Inboxes inboxes(TransactionalMode transactionalMode) {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.getTransactionalMode()).thenReturn(transactionalMode);
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
     * Stand-in for the consumer an {@code EventProcessor} hands to its {@link Inbox}
     */
    private record BoundaryOwningConsumer(boolean hasNonTransactionalMessageHandlers) implements UnitOfWorkBoundaryOwningMessageConsumer {
        @Override
        public void accept(Message message) {
        }
    }
}
