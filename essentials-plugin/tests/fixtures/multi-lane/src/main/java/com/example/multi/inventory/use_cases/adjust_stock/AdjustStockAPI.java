package com.example.multi.inventory.use_cases.adjust_stock;

import com.example.multi.inventory.types.Sku;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/stock")
public class AdjustStockAPI {
    private final CommandBus commandBus;

    public AdjustStockAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    public record AdjustStockRequest(long delta) {}

    @PostMapping("/{sku}/adjustments")
    public void adjustStock(@PathVariable String sku, @RequestBody AdjustStockRequest body) {
        commandBus.send(new AdjustStock(Sku.of(sku), body.delta()));
    }
}
