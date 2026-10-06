package com.example.multi.catalog.use_cases.reprice_product;

import com.example.multi.catalog.types.ProductId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/products")
public class RepriceProductAPI {
    private final CommandBus commandBus;

    public RepriceProductAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    public record RepriceProductRequest(long priceMinor) {}

    @PutMapping("/{productId}/price")
    public void repriceProduct(@PathVariable ProductId productId, @RequestBody RepriceProductRequest body) {
        commandBus.send(new RepriceProduct(productId, body.priceMinor()));
    }
}
