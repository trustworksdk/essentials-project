package com.acme.billing.automations.recurring_billing;

import com.acme.billing.aggregates.Invoices;
import com.acme.billing.events.InvoiceIssued;
import com.acme.billing.events.InvoicePaid;
import com.acme.billing.types.InvoiceId;
import com.acme.billing.use_cases.issue_invoice.IssueInvoice;
import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Recurring billing: once an invoice is paid, the next period's invoice for the same amount is
 * issued one billing period later. {@code InvoicePaid -> [RecurringBillingTodo] -> IssueInvoice}.
 *
 * The next invoice's id is derived from the paid one, so a redelivered event orders the same
 * invoice again and IssueInvoiceHandler's existence check turns the repeat into a no-op.
 */
@Service
public class RecurringBillingProcessor extends EventProcessor {

    static final Duration BILLING_PERIOD = Duration.ofDays(30);

    private final DocumentDbRepository<RecurringBillingTodo, String> todos;

    public RecurringBillingProcessor(EventProcessorDependencies dependencies,
                                     DocumentDbRepository<RecurringBillingTodo, String> todos) {
        super(dependencies);
        this.todos = todos;
    }

    @Override
    public String getProcessorName() {
        return "RecurringBillingProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Invoices.AGGREGATE_TYPE);
    }

    @MessageHandler
    void on(InvoiceIssued event, OrderedMessage message) {
        var id = event.id().toString();
        if (todos.existsById(id)) {
            return;
        }
        todos.save(new RecurringBillingTodo(id), message.getOrder());
    }

    @MessageHandler
    void on(InvoicePaid event, OrderedMessage message) {
        var id = event.id().toString();
        var todo = todos.findById(id);
        if (todo == null) {
            todo = new RecurringBillingTodo(id);
        } else if (todo.getVersionValue() >= message.getOrder()) {
            return;
        }
        todo.setPaid(true);
        if (todo.mayRequestNextInvoice()) {
            getCommandBus().sendAndDontWait(new IssueInvoice(nextInvoiceId(event.id()), event.amountMinor()), BILLING_PERIOD);
            todo.setNextInvoiceRequested(true);
        }
        if (todo.getVersionValue() < 0) {
            todos.save(todo, message.getOrder());
        } else {
            todos.update(todo, message.getOrder());
        }
    }

    static InvoiceId nextInvoiceId(InvoiceId paidInvoiceId) {
        return InvoiceId.of(UUID.nameUUIDFromBytes(("next-of:" + paidInvoiceId).getBytes(StandardCharsets.UTF_8)).toString());
    }
}
