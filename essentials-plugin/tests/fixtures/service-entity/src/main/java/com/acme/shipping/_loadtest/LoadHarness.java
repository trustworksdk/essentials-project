package com.acme.shipping._loadtest;

import com.acme.shipping.types.OrderId;
import com.acme.shipping.use_cases.register_shipping_order.RegisterShippingOrder;
import dk.trustworks.essentials.reactive.command.CommandBus;

public class LoadHarness {

    private final CommandBus commandBus;

    public LoadHarness(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public void fire(int count) {
        for (int i = 0; i < count; i++) {
            commandBus.send(new RegisterShippingOrder(OrderId.of("load-" + i), "nowhere"));
        }
    }
}
