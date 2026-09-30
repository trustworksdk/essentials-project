package com.acme.shipping.use_cases.ship_order;

import com.acme.shipping.types.OrderId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipping/orders")
public class ShipOrderAPI {

    private final CommandBus commandBus;

    public ShipOrderAPI(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @PostMapping("/{orderId}/ship")
    public void ship(@PathVariable String orderId) {
        commandBus.send(new ShipOrder(OrderId.of(orderId)));
    }
}
