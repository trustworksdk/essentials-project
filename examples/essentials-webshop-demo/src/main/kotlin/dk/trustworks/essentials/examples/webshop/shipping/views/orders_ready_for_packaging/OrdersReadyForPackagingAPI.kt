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

    data class PackagingWorkItem(
        val orderId: String,
        val shippingAddress: String,
        val shippingMethod: String,
        val status: String,
        val paymentDeclineReason: String?
    )

    /**
     * What the warehouse screen renders. One query, no joins, no calls to `sales` or `payment`.
     *
     * The status is derived here rather than stored, because it is a presentation of four independent facts and
     * not a fifth fact in its own right. The ordering encodes the business rules:
     *
     * - a refusal - of the authorization, or of the settlement afterwards - outranks everything, so a card
     *   declined after the parcel was packed sends the row back to `BLOCKED` and takes the dispatch button with
     *   it, which is the outcome the warehouse wants and a stored status would have had to remember to produce;
     * - packed but unsettled is `AWAITING_PAYMENT`, not dispatchable - a hold is a promise, and the parcel
     *   waits for the money rather than for the promise;
     * - packed and settled is `READY_TO_DISPATCH`, and only then does anything offer to ship it.
     */
    @GetMapping("/api/shipping/orders-ready-for-packaging")
    fun ordersReadyForPackaging(): List<PackagingWorkItem> =
        repository.findByReadyToPackTrue()
            .map {
                PackagingWorkItem(
                    orderId = it.id,
                    shippingAddress = it.shippingAddress,
                    shippingMethod = it.shippingMethod,
                    status = when {
                        it.paymentDeclineReason != null -> "BLOCKED"
                        it.captureFailureReason != null -> "BLOCKED"
                        it.packaged && it.paymentSettled -> "READY_TO_DISPATCH"
                        it.packaged -> "AWAITING_PAYMENT"
                        else -> "READY_TO_PACK"
                    },
                    paymentDeclineReason = it.paymentDeclineReason ?: it.captureFailureReason
                )
            }
}
