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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.request_checkout

import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.reactive.command.CommandBus
import dk.trustworks.essentials.types.Amount
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class RequestCheckOutAPI(private val commandBus: CommandBus) {

    data class CheckOutRequest(val orderId: OrderId)

    data class CheckOutResponse(val orderId: OrderId, val total: Amount)

    /**
     * A first checkout returns the total the decider computed. A retry produces no event - the checkout already
     * happened - so the response carries the order id the caller asked for and leaves the total at zero; the
     * shop page reads the order summary view for the authoritative figure either way.
     */
    @PostMapping("/api/shopping-baskets/{basketId}/checkout")
    fun checkOut(
        @PathVariable basketId: ShoppingBasketId,
        @RequestBody request: CheckOutRequest
    ): CheckOutResponse {
        val event: CheckOutRequested? = commandBus.send(RequestCheckOut(basketId, request.orderId))
        return CheckOutResponse(
            orderId = event?.orderId ?: request.orderId,
            total = event?.total ?: Amount.ZERO
        )
    }
}
