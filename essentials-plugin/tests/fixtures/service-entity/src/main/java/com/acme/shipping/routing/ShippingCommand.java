package com.acme.shipping.routing;

import com.acme.shipping.types.OrderId;

public interface ShippingCommand {
    OrderId id();
}
