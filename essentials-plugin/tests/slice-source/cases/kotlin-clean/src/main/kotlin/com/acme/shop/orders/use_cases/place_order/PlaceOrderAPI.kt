package com.acme.shop.orders.use_cases.place_order

import com.acme.shop.orders.config.ApiPaths
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/*
 * TRAP: Kotlin block comments nest. /* @PostMapping("/nested") fun phantom() {} */ is still inside
 * the outer comment, and so is this: @GetMapping("/also-phantom")
 */
@RestController
@RequestMapping(ApiPaths.ORDERS)
class PlaceOrderAPI(private val commandBus: CommandBus) {

    /** RULE (dispatches): explicit type arguments between the call name and `(`. */
    @PostMapping
    fun placeOrder(@RequestBody command: PlaceOrder) {
        commandBus.send<Any?, PlaceOrder>(command)
    }
}
