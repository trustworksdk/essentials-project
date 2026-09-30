package com.acme.multi.catalog.use_cases.reprice_product;

import com.acme.multi.catalog.entities.Products;
import com.acme.multi.catalog.events.ProductRepriced;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RepriceProductHandler extends AnnotatedCommandHandler {
    private static final AggregateType PRICE_HISTORY = AggregateType.of("ProductPrices");

    private final Products products;
    private final EventBus eventBus;
    private final EventStore eventStore;

    public RepriceProductHandler(Products products, EventBus eventBus, EventStore eventStore) {
        this.products = products;
        this.eventBus = eventBus;
        this.eventStore = eventStore;
    }

    @CmdHandler
    public void handle(RepriceProduct cmd) {
        var product = products.getById(cmd.id().toString());
        if (product.reprice(cmd.priceMinor())) {
            products.update(product);
            var event = new ProductRepriced(cmd.id(), cmd.priceMinor());
            eventStore.appendToStream(PRICE_HISTORY, cmd.id(), List.of(event));
            eventBus.publish(event);
        }
    }
}
