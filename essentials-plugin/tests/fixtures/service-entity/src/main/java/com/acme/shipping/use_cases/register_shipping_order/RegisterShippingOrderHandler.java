package com.acme.shipping.use_cases.register_shipping_order;

import com.acme.shipping.entities.ShippingOrder;
import com.acme.shipping.persistence.ShippingOrders;
import com.acme.shipping.events.ShippingOrderRegistered;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RegisterShippingOrderHandler extends AnnotatedCommandHandler {

    private final ShippingOrders shippingOrders;
    private final EventBus eventBus;

    public RegisterShippingOrderHandler(ShippingOrders shippingOrders, EventBus eventBus) {
        this.shippingOrders = shippingOrders;
        this.eventBus = eventBus;
    }

    @CmdHandler
    @Transactional
    public void handle(RegisterShippingOrder cmd) {
        var order = new ShippingOrder(cmd);
        shippingOrders.save(order);
        eventBus.publish(ShippingOrderRegistered.from(cmd));
    }
}
