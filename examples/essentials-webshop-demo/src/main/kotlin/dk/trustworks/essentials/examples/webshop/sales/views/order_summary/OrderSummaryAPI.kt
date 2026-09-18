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
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class OrderSummaryAPI(private val repository: OrderSummaryViewRepository) {

    companion object {
        /** Nobody reads more than this on one screen, and nothing should be able to ask the database to. */
        const val MAX_PAGE_SIZE: Int = 100
    }

    data class OrderSummaryResponse(
        val orderId: String,
        val total: Amount?,
        val shippingAddress: String?,
        val shippingMethod: String?,
        val paymentMethod: String?,
        val placed: Boolean,
        val paymentStatus: String,
        val paymentDeclineReason: String?,
        val shippingStatus: String,
        val cancelled: Boolean,
        val cancellationReason: String?
    )

    /**
     * A 404 here means "no event about this order has been projected yet", which right after a checkout can
     * simply mean the projection is a moment behind. The shop page polls rather than treating it as an error.
     */
    /**
     * What the order-history panel needs to draw itself: one page of orders, and enough about the whole set to
     * say "11-20 of 37" and to know whether the buttons either side of it do anything.
     */
    data class OrdersPageResponse(
        val orders: List<OrderSummaryResponse>,
        val page: Int,
        val size: Int,
        val totalOrders: Long,
        val totalPages: Int
    )

    /**
     * One page of orders, most recently touched first: the order-history panel.
     *
     * The rows are the same rows the single-order endpoint returns - there is no second read model and no second
     * query shape, because "all of them" is not a different question about an order, it is the same question
     * asked without an id.
     *
     * [size] is clamped rather than trusted. It reaches SQL as a `limit`, so an unbounded one lets any caller
     * ask this endpoint to materialise the entire table; and a page of zero or minus one is not a request worth
     * honouring. Clamping keeps a mistyped query string from becoming a query plan.
     *
     * The response is a DTO of this slice's own, not a Spring Data `Page`. A `Page` serialises its internal
     * shape - `content`, `pageable`, `numberOfElements`, `sort` - and that shape is not this slice's contract to
     * keep.
     */
    @GetMapping("/api/orders")
    fun orders(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): OrdersPageResponse {
        val requested = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        val found = repository.findAllByOrderByLastUpdatedDesc(requested)
        return OrdersPageResponse(
            orders = found.content.map { it.toResponse() },
            page = found.number,
            size = found.size,
            totalOrders = found.totalElements,
            totalPages = found.totalPages
        )
    }

    @GetMapping("/api/orders/{orderId}/summary")
    fun summary(@PathVariable orderId: OrderId): ResponseEntity<OrderSummaryResponse> =
        repository.findById(orderId.toString())
            .map { ResponseEntity.ok(it.toResponse()) }
            .orElseGet { ResponseEntity.notFound().build() }

    private fun OrderSummaryView.toResponse() = OrderSummaryResponse(
        orderId = id,
        total = total,
        shippingAddress = shippingAddress,
        shippingMethod = shippingMethod,
        paymentMethod = paymentMethod,
        placed = placed,
        paymentStatus = paymentStatus,
        paymentDeclineReason = paymentDeclineReason,
        shippingStatus = shippingStatus,
        cancelled = cancelled,
        cancellationReason = cancellationReason
    )
}
