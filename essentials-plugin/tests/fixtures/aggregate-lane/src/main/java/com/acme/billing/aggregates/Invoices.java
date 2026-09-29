package com.acme.billing.aggregates;

import com.acme.billing.events.InvoiceEvent;
import com.acme.billing.types.InvoiceId;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;

@Component
public class Invoices {
    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("Invoices");

    private final StatefulAggregateRepository<InvoiceId, InvoiceEvent, Invoice> repository;

    public Invoices(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        this.repository = StatefulAggregateRepository.from(eventStore, AGGREGATE_TYPE,
                                                           reflectionBasedAggregateRootFactory(), Invoice.class);
    }

    public Invoice getInvoice(InvoiceId id) { return repository.load(id); }
    public Optional<Invoice> findInvoice(InvoiceId id) { return repository.tryLoad(id); }
    public boolean isInvoiceMissing(InvoiceId id) { return findInvoice(id).isEmpty(); }
    public Invoice saveNew(Invoice invoice) { return repository.save(invoice); }
}
