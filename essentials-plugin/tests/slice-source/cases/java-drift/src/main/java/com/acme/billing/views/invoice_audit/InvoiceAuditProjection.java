package com.acme.billing.views.invoice_audit;

import com.acme.billing.events.InvoiceEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;

public class InvoiceAuditProjection extends ViewEventProcessor {

    /** FINDING (11(b)): a handler typed as the sealed parent handles all three variants; the manifest names two. */
    @MessageHandler
    void on(InvoiceEvent event, OrderedMessage message) {
    }

    /** TRAP: an envelope-typed handler has no event type to check. */
    @MessageHandler
    void any(OrderedMessage message) {
    }
}
