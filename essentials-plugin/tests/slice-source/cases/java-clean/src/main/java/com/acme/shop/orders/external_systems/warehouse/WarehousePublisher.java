package com.acme.shop.orders.external_systems.warehouse;

import com.acme.shop.orders.events.OrderEvent;
import com.acme.shop.orders.external_systems.warehouse.outgoing.WarehouseClient;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.acme.shop.orders.config.OrdersConfiguration.AGGREGATE_TYPE;

@Service
public class WarehousePublisher extends EventProcessor {
    private final WarehouseClient client;

    public WarehousePublisher(EventProcessorDependencies dependencies, WarehouseClient client) {
        super(dependencies);
        this.client = client;
    }

    @Override
    public String getProcessorName() {
        return "WarehousePublisher";
    }

    /** RULE (subscriptions): a statically imported constant. */
    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AGGREGATE_TYPE);
    }

    /** RULE (11(b)): a sealed parent is expanded to its concrete subtypes — all three are declared. */
    @MessageHandler
    void on(OrderEvent event) {
        client.forward(event);
    }

    /** TRAP (11(b)): a Spring lifecycle event is not a domain event. */
    @EventListener
    void onReady(ApplicationReadyEvent ready) {
        client.connect();
    }
}
