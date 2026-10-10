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

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.sales.events.OrderEvent
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.PaymentDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.types.OrderIsNotReadyToBePlacedException
import org.springframework.stereotype.Service

/**
 * Same shape as the shipping-details slice, on the same stream - two slices writing different events about the
 * same order, neither aware of the other. Adding a third detail step later means adding a directory, not editing
 * an order service.
 */
@Service
class AddPaymentDetailsToOrderDecider : Decider<AddPaymentDetailsToOrder, OrderEvent> {

    override fun handle(cmd: AddPaymentDetailsToOrder, events: List<OrderEvent>): PaymentDetailsAdded? {
        if (events.any { it is OrderPlaced }) {
            throw OrderIsNotReadyToBePlacedException(cmd.id, "the order has already been placed")
        }
        val current = events.filterIsInstance<PaymentDetailsAdded>().lastOrNull()
        if (current != null &&
            current.invoiceAddress == cmd.invoiceAddress &&
            current.paymentMethod == cmd.paymentMethod
        ) {
            return null
        }
        return PaymentDetailsAdded(cmd.id, cmd.invoiceAddress, cmd.paymentMethod)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is AddPaymentDetailsToOrder
}
