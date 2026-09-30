package com.acme.shop.orders.routing;

import com.acme.shop.orders.types.OrderId;

/** Deliberately not sealed: the words `sealed` and `permits` in this comment are not a declaration. */
public interface OrderCommand {
    OrderId id();
}
