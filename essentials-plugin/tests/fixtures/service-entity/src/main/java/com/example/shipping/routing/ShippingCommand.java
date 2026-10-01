package com.example.shipping.routing;

import com.example.shipping.types.OrderId;

public interface ShippingCommand {
    OrderId id();
}
