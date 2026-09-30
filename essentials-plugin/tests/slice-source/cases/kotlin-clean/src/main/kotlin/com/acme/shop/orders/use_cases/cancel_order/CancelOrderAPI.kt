package com.acme.shop.orders.use_cases.cancel_order

import com.acme.shop.orders.types.OrderId
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class CancelOrderAPI(private val commandBus: CommandBus) {
    data class Body(val reason: String)

    @PostMapping("/api/orders/{orderId}/cancel")
    fun cancel(@PathVariable orderId: OrderId, @RequestBody body: Body) {
        commandBus.send(CancelOrder(orderId, body.reason))
    }
}
