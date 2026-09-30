package com.acme.shipping.use_cases.register_shipping_order;

import com.acme.shipping.entities.ShippingOrder;
import com.acme.shipping.persistence.ShippingOrders;
import com.acme.shipping.types.OrderId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipping/orders")
public class RegisterShippingOrderAPI {

    private final CommandBus commandBus;
    private final ShippingOrders shippingOrders;

    public RegisterShippingOrderAPI(CommandBus commandBus, ShippingOrders shippingOrders) {
        this.commandBus = commandBus;
        this.shippingOrders = shippingOrders;
    }

    @PostMapping
    public ShippingOrder register(@RequestBody Body body) {
        commandBus.send(new RegisterShippingOrder(OrderId.of(body.orderId()), body.destination()));
        return shippingOrders.findById(body.orderId()).orElseThrow();
    }

    public record Body(String orderId, String destination) {
    }
}
