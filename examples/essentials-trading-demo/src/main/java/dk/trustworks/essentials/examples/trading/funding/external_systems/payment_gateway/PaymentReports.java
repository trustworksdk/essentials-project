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

package dk.trustworks.essentials.examples.trading.funding.external_systems.payment_gateway;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.examples.trading.integration.ExternalEventEnvelope;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Where everything the gateway reports about a payment arrives, as the exchange's reports do for orders
 * ({@code orders}' {@code ExchangeReports}): the initiation's answer as a {@code RecordPaymentOutcome}, and the
 * gateway's own events as {@link ExternalEventEnvelope}s, queued by {@link PaymentGatewayEvents} and translated here
 * as they are consumed ({@link PaymentEventTranslation}). One inbox, so two reports for one payment that collide on its
 * stream fail one unit of work and the loser is redelivered against the new state.
 */
@Component
public class PaymentReports {
    private static final Logger    log          = LoggerFactory.getLogger(PaymentReports.class);
    private static final InboxName INBOX_NAME   = InboxName.of("Funding:PaymentReports");
    private static final int       MAX_ATTEMPTS = 50;

    private final Inboxes                                 inboxes;
    private final CommandBus                              commandBus;
    private final UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;
    private volatile Inbox                                inbox;

    public PaymentReports(Inboxes inboxes, CommandBus commandBus, UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createInbox() {
        inbox();
    }

    /**
     * Queues a report, joining the caller's unit of work if there is one.
     */
    public void received(Object recordCommand) {
        requireNonNull(recordCommand, "No recordCommand provided");
        unitOfWorkFactory.usingUnitOfWork(() -> inbox().addMessageReceived(recordCommand));
    }

    /**
     * The inbox itself, for {@link PaymentGatewayEvents} to queue the gateway's events into through the deduplicating
     * intake.
     */
    synchronized Inbox inbox() {
        if (inbox == null) {
            inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(INBOX_NAME)
                                                        .messageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                                                        .numberOfParallelMessageConsumers(2)
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), MAX_ATTEMPTS))
                                                        .build(),
                                             this::record);
        }
        return inbox;
    }

    private void record(Message message) {
        log.debug("Recording payment report {}", message.getPayload());
        var report = message.getPayload() instanceof ExternalEventEnvelope gatewayEvent
                     ? PaymentEventTranslation.toReport(gatewayEvent)
                     : message.getPayload();
        commandBus.send(report);
    }
}
