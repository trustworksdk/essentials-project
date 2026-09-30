package com.acme.billing.views.invoice_list;

import com.acme.billing.aggregates.Invoices;
import com.acme.billing.events.InvoiceIssued;
import com.acme.billing.events.InvoicePaid;
import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import org.springframework.stereotype.Service;

import java.util.List;

/** Projects the Invoices stream into the invoice list. */
@Service
public class InvoiceListProjection extends ViewEventProcessor {

    private final DocumentDbRepository<InvoiceListView, String> repository;

    public InvoiceListProjection(ViewEventProcessorDependencies dependencies,
                                 DocumentDbRepository<InvoiceListView, String> repository) {
        super(dependencies);
        this.repository = repository;
    }

    @Override
    public String getProcessorName() {
        return "InvoiceListProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Invoices.AGGREGATE_TYPE);
    }

    @MessageHandler
    void on(InvoiceIssued event, OrderedMessage message) {
        var id = event.id().toString();
        if (repository.existsById(id)) {
            return;
        }
        repository.save(new InvoiceListView(id, event.amountMinor()), message.getOrder());
    }

    @MessageHandler
    void on(InvoicePaid event, OrderedMessage message) {
        var row = repository.findById(event.id().toString());
        if (row == null || row.getVersionValue() >= message.getOrder()) {
            return;
        }
        row.setPaid(true);
        repository.update(row, message.getOrder());
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        repository.deleteAll();
    }
}
