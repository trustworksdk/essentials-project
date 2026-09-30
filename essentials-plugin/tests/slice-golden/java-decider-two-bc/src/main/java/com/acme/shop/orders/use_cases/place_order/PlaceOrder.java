package com.acme.shop.orders.use_cases.place_order;

import com.acme.shop.orders.routing.OrderCommand;
import com.acme.shop.orders.types.OrderId;

/**
 * Command for the place_order slice — the intent, as data.
 *
 * A {@code record}: commands are immutable value objects. Implements {@link OrderCommand} so
 * the {@code EventStreamDeciderAndAggregateTypeConfigurator} can route it to the Order
 * aggregate and extract the aggregate id.
 *
 * The command interface is deliberately NOT sealed: adding a command is an open/closed extension
 * (a new slice), never an edit to an existing hierarchy.
 */
public record PlaceOrder(
        OrderId id,
        // TODO: replace with this command's real payload
        String placeholder
) implements OrderCommand {
}
