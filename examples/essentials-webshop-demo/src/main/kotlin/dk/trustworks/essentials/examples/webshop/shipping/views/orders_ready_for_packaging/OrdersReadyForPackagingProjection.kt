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
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptured
import dk.trustworks.essentials.examples.webshop.sales.config.SalesAggregateTypes
import dk.trustworks.essentials.examples.webshop.sales.events.OrderCancelled
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.PaymentDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ShippingDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.types.PaymentMethod
import dk.trustworks.essentials.examples.webshop.shipping.config.ShippingAggregateTypes
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderPackagingRequested
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderShipped
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The packaging list, built from three contexts' events and one of its own.
 *
 * `shipping` learns everything it needs about an order by **subscribing** to the other contexts' event streams:
 * the address from [ShippingDetailsAdded], the go-ahead from [OrderPlaced], the calling-off from
 * [OrderCancelled], and whether the card was actually charged from [CreditCardHoldRejected]. It never calls
 * `sales` or `payment`, holds no reference to any of their classes beyond the exported events, and would keep
 * working if either were down for an hour - the events are already in the store, and the subscription resumes
 * where it left off.
 *
 * Reading another context's `events/` package is legal and deliberate. Injecting its write side - a decider, a
 * repository, a service - would not be.
 *
 * **Four subscriptions, four orderings.** Events within one aggregate type arrive in order, but the four streams
 * have no order relative to each other: a declined hold can be projected before the order is known to be placed,
 * and packaging before the address has landed. So no handler here reads a field another handler owns, every one
 * of them writes through [update], and [update] creates the row if it is missing - which is why the entity's
 * text fields default to `(pending)` instead of being required. A projection that assumed "details always
 * arrive before placed" would be right almost always, which is the worst kind of wrong.
 *
 * Every write is an assignment rather than an increment, so applying the same event twice leaves the same row
 * and no handler needs to compare event orders.
 */
@Service
class OrdersReadyForPackagingProjection(
    dependencies: ViewEventProcessorDependencies,
    private val repository: OrderReadyForPackagingViewRepository
) : ViewEventProcessor(dependencies) {

    companion object {
        private val logger = LoggerFactory.getLogger(OrdersReadyForPackagingProjection::class.java)
    }

    override fun getProcessorName(): String = "OrdersReadyForPackagingProjection"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(
            SalesAggregateTypes.ORDERS,
            ShippingAggregateTypes.SHIPPING_ORDERS,
            PaymentAggregateTypes.CREDIT_CARD_HOLDS
        )

    @MessageHandler
    fun on(e: ShippingDetailsAdded, message: OrderedMessage) =
        update(e.id.toString()) {
            // Also the correction case: overwriting both fields with the event's own values is idempotent, so a
            // corrected address needs no special handling and no event-order comparison.
            it.shippingAddress = with(e.shippingAddress) { "$street, $postalCode $city, $countryCode" }
            it.shippingMethod = e.shippingMethod.name
        }

    @MessageHandler
    fun on(e: OrderPlaced, message: OrderedMessage) =
        update(e.id.toString()) { it.readyToPack = true }

    @MessageHandler
    fun on(e: CreditCardHoldRejected, message: OrderedMessage) =
        // The work item stays on the list and stops being actionable. Dropping it instead would hide an order
        // that someone still has to decide about, which is how a customer ends up waiting for a parcel nobody
        // is ever going to pack.
        update(e.id.toString()) {
            it.paymentDeclineReason = e.reason
            logger.info("Order '{}' is blocked for packing - payment was declined: {}", e.id, e.reason)
        }

    @MessageHandler
    fun on(e: CreditCardHoldPlaced, message: OrderedMessage) =
        // An authorization after an earlier decline unblocks the row, and on the far more common first-time
        // authorization this writes the null that was already there.
        update(e.id.toString()) { it.paymentDeclineReason = null }

    @MessageHandler
    fun on(e: PaymentDetailsAdded, message: OrderedMessage) =
        // An order that is not paid by card has nothing to capture, so nothing for dispatch to wait for. This
        // is the one line that keeps the settlement gate from stranding every invoice order on the bench.
        update(e.id.toString()) {
            if (e.paymentMethod != PaymentMethod.CREDIT_CARD) {
                it.paymentSettled = true
            }
        }

    @MessageHandler
    fun on(e: FundsCaptured, message: OrderedMessage) =
        update(e.id.toString()) {
            it.paymentSettled = true
            it.captureFailureReason = null
        }

    @MessageHandler
    fun on(e: FundsCaptureFailed, message: OrderedMessage) =
        // The parcel is packed and the money is not coming. Nothing here undoes the packing - it stops the
        // dispatch, which is the only step that has not happened yet and the only one that is still cheap.
        update(e.id.toString()) {
            it.captureFailureReason = e.reason
            it.paymentSettled = false
        }

    @MessageHandler
    fun on(e: OrderPackagingRequested, message: OrderedMessage) =
        update(e.id.toString()) { it.packaged = true }

    @MessageHandler
    fun on(e: OrderShipped, message: OrderedMessage) {
        // The work is finished, so the row leaves the list. Deleting an already-deleted row is a no-op, which is
        // what makes this handler idempotent without a version check.
        repository.deleteById(e.id.toString())
    }

    @MessageHandler
    fun on(e: OrderCancelled, message: OrderedMessage) {
        // `sales` called the order off. `shipping` was not asked to do this and holds no opinion about why - it
        // simply stops carrying work that no longer needs doing.
        logger.info("Order '{}' was cancelled by sales - dropping it from the packing list", e.id)
        repository.deleteById(e.id.toString())
    }

    private fun update(orderId: String, change: (OrderReadyForPackagingView) -> Unit) {
        val row = repository.findById(orderId).orElseGet { OrderReadyForPackagingView(id = orderId) }
        change(row)
        repository.save(row)
    }

    override fun onSubscriptionsReset(aggregateType: AggregateType, resubscribeFromAndIncluding: GlobalEventOrder) {
        repository.deleteAll()
    }
}
