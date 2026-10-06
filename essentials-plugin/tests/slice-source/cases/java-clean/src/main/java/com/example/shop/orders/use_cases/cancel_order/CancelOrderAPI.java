package com.example.shop.orders.use_cases.cancel_order;

import com.example.shop.orders.types.OrderId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/")
public class CancelOrderAPI {
    private final CommandBus commandBus;

    public CancelOrderAPI(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public record Body(String reason) {
    }

    /** RULE (gate 6): path variables compare by position — `{orderId}` here, `{id}` in the manifest. */
    @PostMapping("{orderId}/cancel")
    public void cancel(@PathVariable OrderId orderId, @RequestBody Body body) {
        var cmd = new CancelOrder(orderId, body.reason());
        commandBus.send(cmd);
    }
}
