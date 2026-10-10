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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.examples.webshop.payment.config.PaymentAggregateTypes
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldPlaced
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldRejected
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureFailed
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureRequested
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptured
import dk.trustworks.essentials.examples.webshop.sales.config.SalesAggregateTypes
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.OrderCancelled
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.PaymentDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ShippingDetailsAdded
import dk.trustworks.essentials.examples.webshop.shipping.config.ShippingAggregateTypes
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderPackagingRequested
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderShipped
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * The confirmation screen, assembled from four event streams across three bounded contexts.
 *
 * This is the composite UI, done in the read model rather than in the browser: one row, one query, no join and
 * no fan-out of calls to three services while the customer waits. The price is that the row is eventually
 * consistent - a status can be a few hundred milliseconds behind the fact - which is exactly the trade the read
 * side exists to make.
 *
 * Note the direction of every dependency: this slice reads other contexts' **events**. `payment` and `shipping`
 * do not know it exists, and deleting it would not change a single decision either of them makes. A screen that
 * needs data from three places is a reason to project, not a reason to couple.
 */
@Service
class OrderSummaryProjection(
    dependencies: ViewEventProcessorDependencies,
    private val repository: OrderSummaryViewRepository
) : ViewEventProcessor(dependencies) {

    override fun getProcessorName(): String = "OrderSummaryProjection"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(
            SalesAggregateTypes.SHOPPING_BASKETS,
            SalesAggregateTypes.ORDERS,
            ShippingAggregateTypes.SHIPPING_ORDERS,
            PaymentAggregateTypes.CREDIT_CARD_HOLDS
        )

    @MessageHandler
    fun on(e: CheckOutRequested, message: OrderedMessage) {
        update(e.orderId.toString()) {
            it.basketId = e.id.toString()
            it.total = e.total
        }
    }

    @MessageHandler
    fun on(e: ShippingDetailsAdded, message: OrderedMessage) {
        update(e.id.toString()) {
            it.shippingAddress = with(e.shippingAddress) { "$street, $postalCode $city, $countryCode" }
            it.shippingMethod = e.shippingMethod.name
        }
    }

    @MessageHandler
    fun on(e: PaymentDetailsAdded, message: OrderedMessage) {
        update(e.id.toString()) { it.paymentMethod = e.paymentMethod.name }
    }

    @MessageHandler
    fun on(e: OrderPlaced, message: OrderedMessage) {
        update(e.id.toString()) { it.placed = true }
    }

    @MessageHandler
    fun on(e: CreditCardHoldPlaced, message: OrderedMessage) {
        update(e.id.toString()) {
            it.paymentStatus = "HELD"
            // Clearing the reason keeps this handler an assignment of the whole payment outcome rather than half
            // of it, so a later authorization after an earlier decline leaves no stale explanation behind.
            it.paymentDeclineReason = null
        }
    }

    @MessageHandler
    fun on(e: CreditCardHoldRejected, message: OrderedMessage) {
        update(e.id.toString()) {
            it.paymentStatus = "REJECTED"
            it.paymentDeclineReason = e.reason
        }
    }

    @MessageHandler
    fun on(e: FundsCaptureRequested, message: OrderedMessage) {
        // "We have asked and do not know yet" is a state the customer-facing screen has to be able to show. A
        // screen that only knows HELD and CAPTURED has to pretend one of them during the wait.
        update(e.id.toString()) { it.paymentStatus = "CAPTURE_PENDING" }
    }

    @MessageHandler
    fun on(e: FundsCaptured, message: OrderedMessage) {
        update(e.id.toString()) {
            it.paymentStatus = "CAPTURED"
            it.paymentDeclineReason = null
        }
    }

    @MessageHandler
    fun on(e: FundsCaptureFailed, message: OrderedMessage) {
        update(e.id.toString()) {
            it.paymentStatus = "CAPTURE_FAILED"
            it.paymentDeclineReason = e.reason
        }
    }

    @MessageHandler
    fun on(e: OrderCancelled, message: OrderedMessage) {
        update(e.id.toString()) {
            it.cancelled = true
            it.cancellationReason = e.reason
        }
    }

    @MessageHandler
    fun on(e: OrderPackagingRequested, message: OrderedMessage) {
        update(e.id.toString()) { it.shippingStatus = "PACKAGING" }
    }

    @MessageHandler
    fun on(e: OrderShipped, message: OrderedMessage) {
        update(e.id.toString()) { it.shippingStatus = "SHIPPED: ${e.trackingNumber}" }
    }

    /**
     * Every handler writes through here, and every write is an assignment rather than an increment - so applying
     * the same event twice produces the same row, and no handler needs to compare event orders. When a
     * projection *can* be written this way, it should be: it is the cheapest form of idempotence there is.
     */
    private fun update(orderId: String, change: (OrderSummaryView) -> Unit) {
        val row = repository.findById(orderId).orElseGet { OrderSummaryView(id = orderId) }
        change(row)
        row.lastUpdated = OffsetDateTime.now()
        repository.save(row)
    }

    override fun onSubscriptionsReset(aggregateType: AggregateType, resubscribeFromAndIncluding: GlobalEventOrder) {
        repository.deleteAll()
    }
}
