package com.acme.billing.views.invoice_list;

import com.acme.billing.aggregates.Invoices;
import com.acme.billing.events.InvoiceIssued;
import com.acme.billing.events.InvoiceVoided;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.InTransactionEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

import java.util.List;

public class InvoiceListProjection extends InTransactionEventProcessor {

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Invoices.AGGREGATE_TYPE);
    }

    @MessageHandler
    void on(InvoiceIssued event) {
    }

    /** RULE: fully qualified, and declared — no finding. */
    @MessageHandler
    void on(com.acme.billing.events.InvoicePaid event) {
    }

    /** FINDING (11(b)): the projector gained a handler; the manifest was not touched. */
    @MessageHandler
    void on(InvoiceVoided event) {
    }
}
