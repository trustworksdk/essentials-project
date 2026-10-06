package com.example.shop.orders.use_cases.cancel_order

import com.example.shop.orders.types.OrderId
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Single-method endpoint for THIS slice only (rules/slice-design.md §R2).
 *
 * NEVER a multi-endpoint controller injecting many handlers — adding an endpoint means adding a
 * slice with its own API file.
 *
 * NEVER RETURN THE ENTITY. On this lane the entity is a managed, mutable persistence object;
 * returning one makes every field of the write model part of your wire contract. Return an id, a
 * 202, or nothing. If the client needs state back, that is a *query* and belongs to a view slice
 * (§ The read side on this lane).
 *
 * The command IS the contract (§R2) — no adapter layer, no mapper. `CancelOrderRequest` carries only
 * the fields the client actually sends, because the id is generated here; that is *assembly*.
 */
@RestController
@RequestMapping("/api/orders/cancel")
class CancelOrderAPI(private val commandBus: CommandBus) {

    data class CancelOrderRequest(val placeholder: String)
    data class CancelOrderResponse(val orderId: String)

    @PostMapping
    fun cancelOrder(@RequestBody body: CancelOrderRequest): CancelOrderResponse {
        val id = OrderId.random()
        commandBus.send<Any?, CancelOrder>(CancelOrder(id, body.placeholder))
        return CancelOrderResponse(id.value)
    }
}
