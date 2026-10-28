package com.example.shop.orders.use_cases.place_order;

import com.example.shop.orders.types.OrderId;
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
 * The command IS the contract (§R2) — no adapter layer, no mapper. {@code PlaceOrderRequest} carries
 * only the fields the client actually sends: the id of the Order to change, and the change.
 * This slice changes an existing Order — the handler loads it — so the id comes from the
 * client, never from {@code OrderId.random()}, which would name a row that does not exist.
 * Turning the request into the command is *assembly*, not translation.
 */
@RestController
@RequestMapping("/api/orders")
public class PlaceOrderAPI {

    private final CommandBus commandBus;

    public PlaceOrderAPI(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public record PlaceOrderRequest(String orderId, String placeholder) {}
    public record PlaceOrderResponse(String orderId) {}

    @PostMapping
    public PlaceOrderResponse placeOrder(@RequestBody PlaceOrderRequest body) {
        var id = OrderId.of(body.orderId());
        commandBus.send(new PlaceOrder(id, body.placeholder()));
        return new PlaceOrderResponse(id.toString());
    }
}
