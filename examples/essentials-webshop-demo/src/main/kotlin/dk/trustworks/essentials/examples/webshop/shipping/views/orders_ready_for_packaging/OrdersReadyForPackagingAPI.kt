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

package dk.trustworks.essentials.examples.webshop.shipping.views.orders_ready_for_packaging

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class OrdersReadyForPackagingAPI(private val repository: OrderReadyForPackagingViewRepository) {

    data class PackagingWorkItem(val orderId: String, val shippingAddress: String, val shippingMethod: String)

    /** What the warehouse screen renders. One query, no joins, no calls to `sales`. */
    @GetMapping("/api/shipping/orders-ready-for-packaging")
    fun ordersReadyForPackaging(): List<PackagingWorkItem> =
        repository.findByReadyToPackTrue()
            .map { PackagingWorkItem(it.id, it.shippingAddress, it.shippingMethod) }
}
