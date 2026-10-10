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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.add_payment_details_to_order

import dk.trustworks.essentials.examples.webshop.sales.events.PaymentDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.types.Address
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.PaymentMethod
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AddPaymentDetailsToOrderAPI(private val commandBus: CommandBus) {

    data class PaymentDetailsRequest(val invoiceAddress: Address, val paymentMethod: PaymentMethod)

    @PutMapping("/api/orders/{orderId}/payment-details")
    fun addPaymentDetails(
        @PathVariable orderId: OrderId,
        @RequestBody request: PaymentDetailsRequest
    ): ResponseEntity<Void> {
        val event: PaymentDetailsAdded? =
            commandBus.send(AddPaymentDetailsToOrder(orderId, request.invoiceAddress, request.paymentMethod))
        return if (event != null) ResponseEntity.ok().build() else ResponseEntity.noContent().build()
    }
}
