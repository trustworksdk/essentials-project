package com.acme.shipping.use_cases.ship_order;

import com.acme.shipping.events.OrderShipped;
import com.acme.shipping.persistence.ShippingOrders;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Clean, and the model for this lane: load, call the ONE invariant method, save, publish. */
@Component
public class ShipOrderHandler extends AnnotatedCommandHandler {

    private final ShippingOrders shippingOrders;
    private final EventBus eventBus;

    public ShipOrderHandler(ShippingOrders shippingOrders, EventBus eventBus) {
        this.shippingOrders = shippingOrders;
        this.eventBus = eventBus;
    }

    @CmdHandler
    @Transactional
    public void handle(ShipOrder cmd) {
        var order = shippingOrders.findById(cmd.orderId().value()).orElseThrow();
        if (order.markOrderAsShipped()) {
            shippingOrders.save(order);
            eventBus.publish(new OrderShipped(cmd.orderId().value()));
        }
    }
}
