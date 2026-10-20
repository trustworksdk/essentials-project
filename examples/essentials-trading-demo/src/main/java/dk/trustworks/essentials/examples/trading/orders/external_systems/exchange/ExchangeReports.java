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

package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.slf4j.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Where everything the exchange reports about an order arrives: the submission's answer, the acceptance, fills and
 * cancellations, already translated to {@code orders}' own commands ({@code RecordExchangeAnswer}, {@code RecordFill},
 * {@code RecordCancellation}) — and the end of the business day, as one {@code ExpireOrder} per open {@code DAY} order.
 * <p>
 * They go through one {@link Inbox}, not straight to the command bus, so that the caller — a gateway callback, a
 * webhook receiver, a submission handler running with no unit of work — never has to deal with the order's stream
 * being written concurrently. Two reports for one order that collide on its stream fail one unit of work with an
 * optimistic-append conflict, and the inbox redelivers that report against the new state. The order applies every
 * report idempotently and in any order, so a redelivery never adds anything twice.
 */
@Component
public class ExchangeReports {
    private static final Logger    log          = LoggerFactory.getLogger(ExchangeReports.class);
    private static final InboxName INBOX_NAME   = InboxName.of("Orders:ExchangeReports");
    private static final int       MAX_ATTEMPTS = 50;

    private final Inboxes                                 inboxes;
    private final CommandBus                              commandBus;
    private final UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;
    private volatile Inbox                                inbox;

    public ExchangeReports(Inboxes inboxes, CommandBus commandBus, UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    /**
     * Every instance consumes, so reports queued by one instance are recorded by any.
     */
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

    private synchronized Inbox inbox() {
        if (inbox == null) {
            inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(INBOX_NAME)
                                                        .messageConsumptionMode(MessageConsumptionMode.GlobalCompetingConsumers)
                                                        .numberOfParallelMessageConsumers(2)
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), MAX_ATTEMPTS))
                                                        .build(),
                                             this::record);
        }
        return inbox;
    }

    private void record(Message message) {
        log.debug("Recording exchange report {}", message.getPayload());
        commandBus.send(message.getPayload());
    }
}
