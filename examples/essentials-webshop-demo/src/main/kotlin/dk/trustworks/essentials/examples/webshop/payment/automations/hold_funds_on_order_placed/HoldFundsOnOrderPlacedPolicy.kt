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

package dk.trustworks.essentials.examples.webshop.payment.automations.hold_funds_on_order_placed

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.examples.webshop.payment.config.PaymentAggregateTypes
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldPlaced
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldRejected
import dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway.HoldResult
import dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway.PaymentGateway
import dk.trustworks.essentials.examples.webshop.payment.use_cases.place_hold_on_credit_card.PlaceHoldOnCreditCard
import dk.trustworks.essentials.examples.webshop.sales.config.SalesAggregateTypes
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.PaymentDetailsAdded
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.reactive.command.CommandBus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The automation pattern, end to end: **events -> the policy's own state -> automated trigger -> command ->
 * event**.
 *
 * `payment` decided on its own that a placed card order needs an authorization. `sales` did not ask it to, does
 * not know it happened, and would not change if this policy were deleted. That is the practical difference
 * between publishing an event and sending a command across a boundary.
 *
 * It needs three facts that arrive on three different streams - the total from `CheckOutRequested`, the method
 * from `PaymentDetailsAdded`, the go-ahead from `OrderPlaced` - so it keeps [OrderAwaitingHold] as its own
 * state and updates it from whichever event turns up first. **Each handler ends by asking the same question**:
 * is this row now complete work? Whichever event completes it triggers the authorization, so the policy never
 * depends on the events arriving in a particular order, and never depends on another processor having caught up.
 *
 * Then, and only then:
 *
 * 1. call the gateway, synchronously, because an authorization is a question to a third party;
 * 2. send a command carrying the answer, so a **decider** records the fact and the write side stays pure.
 *
 * Two things keep a redelivered event from charging a customer twice: [OrderAwaitingHold.outcome] here, and the
 * decider's own idempotency check. Neither alone would be enough - this row is a projection and could be rebuilt.
 */
@Service
class HoldFundsOnOrderPlacedPolicy(
    dependencies: EventProcessorDependencies,
    // Named `paymentCommandBus`, not `commandBus`: EventProcessor already has a protected `commandBus` field,
    // and a Kotlin property of the same name hides it.
    private val paymentCommandBus: CommandBus,
    private val paymentGateway: PaymentGateway,
    private val awaitingHold: OrderAwaitingHoldRepository
) : EventProcessor(dependencies) {

    companion object {
        private val logger = LoggerFactory.getLogger(HoldFundsOnOrderPlacedPolicy::class.java)
        private const val CARD = "CREDIT_CARD"
    }

    override fun getProcessorName(): String = "HoldFundsOnOrderPlacedPolicy"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(
            SalesAggregateTypes.SHOPPING_BASKETS,
            SalesAggregateTypes.ORDERS,
            PaymentAggregateTypes.CREDIT_CARD_HOLDS
        )

    @MessageHandler
    fun on(e: CheckOutRequested, message: OrderedMessage) =
        updateThenAct(e.orderId) { it.total = e.total }

    @MessageHandler
    fun on(e: PaymentDetailsAdded, message: OrderedMessage) =
        updateThenAct(e.id) { it.paymentMethod = e.paymentMethod.name }

    @MessageHandler
    fun on(e: OrderPlaced, message: OrderedMessage) =
        updateThenAct(e.id) { it.placed = true }

    @MessageHandler
    fun on(e: CreditCardHoldPlaced, message: OrderedMessage) =
        updateThenAct(e.id) { it.outcome = "HELD" }

    @MessageHandler
    fun on(e: CreditCardHoldRejected, message: OrderedMessage) =
        updateThenAct(e.id) { it.outcome = "REJECTED" }

    /**
     * Apply one fact, save, and then act if the row has become complete work.
     *
     * Every write here is an assignment rather than an increment, so applying the same event twice leaves the
     * same row - the cheapest form of idempotence, and the reason no handler compares event orders.
     */
    private fun updateThenAct(orderId: OrderId, change: (OrderAwaitingHold) -> Unit) {
        val row = awaitingHold.findById(orderId.toString())
            .orElseGet { OrderAwaitingHold(id = orderId.toString()) }
        change(row)

        if (row.outcome == null && row.placed && row.paymentMethod != null && row.paymentMethod != CARD) {
            // An invoice order needs no authorization. Recording that as an outcome closes the work item
            // instead of leaving it open forever.
            row.outcome = "NOT_REQUIRED"
        }
        awaitingHold.save(row)

        if (!row.needsHold(CARD)) {
            return
        }
        placeHold(orderId, row)
    }

    private fun placeHold(orderId: OrderId, row: OrderAwaitingHold) {
        val total = row.total!!
        logger.debug("Order '{}' is complete work: asking the gateway to hold {}", orderId, total)
        val command = when (val result = paymentGateway.placeHold(orderId, total)) {
            is HoldResult.Authorized ->
                PlaceHoldOnCreditCard(orderId, total, authorizationCode = result.authorizationCode)

            is HoldResult.Declined ->
                PlaceHoldOnCreditCard(orderId, total, declineReason = result.reason)
        }
        paymentCommandBus.send<Any?, PlaceHoldOnCreditCard>(command)
    }
}
