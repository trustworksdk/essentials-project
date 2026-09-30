package com.acme.shop.orders.use_cases.cancel_order;

import com.acme.shop.orders.types.OrderId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single-method endpoint for THIS slice only (rules/slice-design.md §R2).
 *
 * NEVER a multi-endpoint controller injecting many handlers — adding an endpoint means adding a
 * slice with its own API file, never an extra method here.
 *
 * NEVER RETURN THE ENTITY. On this lane the entity is a managed, mutable persistence object; handing
 * one to a caller makes every field of the write model part of your wire contract and lets the
 * caller mutate a row-backed object. Return an id, a 202, or nothing. If the client needs the state
 * back, that is a *query*, and it belongs to a view slice (§ The read side on this lane).
 *
 * The command IS the contract (§R2) — no adapter layer, no mapper. {@code CancelOrderRequest} carries
 * only the fields the client actually sends, because the id is generated here; that is *assembly*,
 * not translation.
 */
@RestController
@RequestMapping("/api/orders/cancel")
public class CancelOrderAPI {

    private final CommandBus commandBus;

    public CancelOrderAPI(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public record CancelOrderRequest(String placeholder) {}
    public record CancelOrderResponse(String orderId) {}

    @PostMapping
    public CancelOrderResponse cancelOrder(@RequestBody CancelOrderRequest body) {
        var id = OrderId.random();
        commandBus.send(new CancelOrder(id, body.placeholder()));
        return new CancelOrderResponse(id.toString());
    }
}
