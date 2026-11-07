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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.cancel_order

import dk.trustworks.essentials.examples.webshop.sales.events.OrderCancelled
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class CancelOrderAPI(private val commandBus: CommandBus) {

    data class CancelOrderRequest(val reason: String)

    /**
     * 204 rather than 200 means the order was already cancelled and no second event was recorded. The caller
     * asked for a state and got it either way, which is what makes the button safe to press twice.
     */
    @PostMapping("/api/orders/{orderId}/cancel")
    fun cancelOrder(
        @PathVariable orderId: OrderId,
        @RequestBody request: CancelOrderRequest
    ): ResponseEntity<Void> {
        val event: OrderCancelled? = commandBus.send(CancelOrder(orderId, request.reason))
        return if (event != null) ResponseEntity.ok().build() else ResponseEntity.noContent().build()
    }
}
