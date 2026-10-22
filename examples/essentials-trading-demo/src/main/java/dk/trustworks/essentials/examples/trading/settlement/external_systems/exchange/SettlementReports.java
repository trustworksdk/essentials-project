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

package dk.trustworks.essentials.examples.trading.settlement.external_systems.exchange;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.examples.trading.integration.ExternalEventEnvelope;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Where the exchange's settlement events arrive, queued by {@link SettlementEvents} and translated as they are
 * consumed ({@link SettlementEventTranslation}). Reports for one execution that collide on its stream fail one unit of
 * work and the loser is redelivered; a report that cannot be recorded yet is redelivered too, so the policy allows
 * many attempts.
 */
@Component
public class SettlementReports {
    private static final InboxName INBOX_NAME   = InboxName.of("Settlement:Reports");
    private static final int       MAX_ATTEMPTS = 50;

    private final Inboxes    inboxes;
    private final CommandBus commandBus;
    private volatile Inbox   inbox;

    public SettlementReports(Inboxes inboxes, CommandBus commandBus) {
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createInbox() {
        inbox();
    }

    synchronized Inbox inbox() {
        if (inbox == null) {
            inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(INBOX_NAME)
                                                        .messageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                                                        .numberOfParallelMessageConsumers(2)
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), MAX_ATTEMPTS))
                                                        .build(),
                                             this::record);
        }
        return inbox;
    }

    private void record(Message message) {
        commandBus.send(SettlementEventTranslation.toReport((ExternalEventEnvelope) message.getPayload()));
    }
}
