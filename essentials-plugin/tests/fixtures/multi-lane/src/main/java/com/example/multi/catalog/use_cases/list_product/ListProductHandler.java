package com.example.multi.catalog.use_cases.list_product;

import com.example.multi.catalog.entities.Product;
import com.example.multi.catalog.entities.Products;
import com.example.multi.catalog.events.ProductListed;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;

@Component
public class ListProductHandler extends AnnotatedCommandHandler {
    private final Products products;
    private final EventBus eventBus;

    public ListProductHandler(Products products, EventBus eventBus) {
        this.products = products;
        this.eventBus = eventBus;
    }

    @CmdHandler
    public void handle(ListProduct cmd) {
        var id = cmd.id().toString();
        if (products.existsById(id)) {
            return;
        }
        products.save(new Product(id, cmd.name(), cmd.priceMinor()), Version.ZERO_VALUE);
        eventBus.publish(new ProductListed(cmd.id(), cmd.name(), cmd.priceMinor()));
    }
}
