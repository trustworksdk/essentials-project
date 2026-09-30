package com.acme.shipping.automations.auto_ship;

import com.acme.shipping.events.ShippingOrderRegistered;
import com.acme.shipping.persistence.ShippingOrders;
import com.acme.shipping.types.OrderId;
import com.acme.shipping.use_cases.ship_order.ShipOrder;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AutoShipProcessor {

    private final CommandBus commandBus;
    private final ShippingOrders shippingOrders;

    public AutoShipProcessor(CommandBus commandBus, ShippingOrders shippingOrders) {
        this.commandBus = commandBus;
        this.shippingOrders = shippingOrders;
    }

    @EventListener
    public void on(ShippingOrderRegistered event) {
        commandBus.sendAndDontWait(new ShipOrder(OrderId.of(event.orderId())));
    }

    public void shipBatch(List<String> orderIds) {
        for (var order : shippingOrders.findByIdIn(orderIds)) {
            commandBus.sendAndDontWait(new ShipOrder(OrderId.of(order.getOrderId())));
        }
    }
}
