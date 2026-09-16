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

package dk.trustworks.essentials.examples.webshop.sales.views.order_summary

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.Amount
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class OrderSummaryAPI(private val repository: OrderSummaryViewRepository) {

    data class OrderSummaryResponse(
        val orderId: String,
        val total: Amount?,
        val shippingAddress: String?,
        val shippingMethod: String?,
        val paymentMethod: String?,
        val placed: Boolean,
        val paymentStatus: String,
        val shippingStatus: String
    )

    /**
     * A 404 here means "no event about this order has been projected yet", which right after a checkout can
     * simply mean the projection is a moment behind. The shop page polls rather than treating it as an error.
     */
    @GetMapping("/api/orders/{orderId}/summary")
    fun summary(@PathVariable orderId: OrderId): ResponseEntity<OrderSummaryResponse> =
        repository.findById(orderId.toString())
            .map {
                ResponseEntity.ok(
                    OrderSummaryResponse(
                        orderId = it.id,
                        total = it.total,
                        shippingAddress = it.shippingAddress,
                        shippingMethod = it.shippingMethod,
                        paymentMethod = it.paymentMethod,
                        placed = it.placed,
                        paymentStatus = it.paymentStatus,
                        shippingStatus = it.shippingStatus
                    )
                )
            }
            .orElseGet { ResponseEntity.notFound().build() }
}
