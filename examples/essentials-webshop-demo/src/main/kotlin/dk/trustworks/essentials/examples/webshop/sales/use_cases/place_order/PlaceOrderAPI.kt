/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.webshop.sales.use_cases.place_order

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class PlaceOrderAPI(private val commandBus: CommandBus) {

    /**
     * When this returns, the order is placed and permanent - but the packaging list and the payment hold have not
     * caught up yet, because those are subscriptions. The shop page therefore polls the order summary rather than
     * assuming the screen it renders next is complete. That gap is not a defect to hide; it is what buys the
     * write side its independence from every reader.
     */
    @PostMapping("/api/orders/{orderId}/place")
    fun placeOrder(@PathVariable orderId: OrderId): ResponseEntity<Void> {
        commandBus.send<Any?, PlaceOrder>(PlaceOrder(orderId))
        return ResponseEntity.accepted().build()
    }
}
