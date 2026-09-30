package com.acme.shop.orders.views.order_list;

import com.acme.shop.orders.config.OrdersConfiguration;
import com.acme.shop.orders.events.OrderPlaced;
import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TRAP (gate 10 / 11(b)): this javadoc names a handler that does not exist —
 * {@code @MessageHandler void on(OrderShipped e)} — and must not produce one.
 */
@Service
public class OrderListProjection extends ViewEventProcessor {
    private static final String NOTE = "@MessageHandler void on(OrderShipped e) { } /* not code */";

    private final DocumentDbRepository<OrderListView, String> repository;

    public OrderListProjection(ViewEventProcessorDependencies dependencies,
                               DocumentDbRepository<OrderListView, String> repository) {
        super(dependencies);
        this.repository = repository;
    }

    @Override
    public String getProcessorName() {
        return "OrderListProjection";
    }

    /** RULE (subscriptions): a constant resolves to the literal it is assigned. */
    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(OrdersConfiguration.AGGREGATE_TYPE);
    }

    @MessageHandler
    void on(OrderPlaced event, OrderedMessage message) {
        repository.save(new OrderListView(event.id().value(), "PLACED"), message.getOrder());
    }

    /** RULE (11(b)): a fully-qualified parameter type resolves to its simple name. */
    @MessageHandler
    void on(com.acme.shop.orders.events.OrderCancelled event, OrderedMessage message) {
        var existing = repository.findById(event.id().value());
        existing.setStatus("CANCELLED");
        repository.update(existing, message.getOrder());
    }
}
