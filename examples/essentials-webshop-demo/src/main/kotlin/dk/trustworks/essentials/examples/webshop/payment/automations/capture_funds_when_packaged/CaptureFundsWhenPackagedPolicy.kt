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

package dk.trustworks.essentials.examples.webshop.payment.automations.capture_funds_when_packaged

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.examples.webshop.payment.config.PaymentAggregateTypes
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldPlaced
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureFailed
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureRequested
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptured
import dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway.PaymentGateway
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.examples.webshop.payment.use_cases.request_funds_capture.RequestFundsCapture
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.shipping.config.ShippingAggregateTypes
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderPackagingRequested
import dk.trustworks.essentials.reactive.command.CommandBus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Take the money when the parcel is packed, and not before.
 *
 * **Why here and not at checkout.** Authorizing reserves the money; capturing takes it. Retail captures at
 * dispatch, and that timing is not an accounting detail - it is what makes the failure case survivable. A
 * capture that fails here has cost nothing: the parcel is on a bench, not on a van, so the order simply stops
 * being dispatchable. Capture at checkout instead and the same refusal arrives after the warehouse has packed,
 * and the demo would have to model un-packing and refunding - compensation for work already done, which is
 * always more expensive than not starting it.
 *
 * **The order of the two steps below is the whole lesson.**
 *
 * 1. Send `RequestFundsCapture`, so `FundsCaptureRequested` - carrying the idempotency key - is committed to
 *    the stream.
 * 2. *Then* call the gateway.
 *
 * Never the other way round. Between those two steps the process can die, the call can time out, the answer can
 * be lost; in every one of those cases the recorded request is what lets us find the charge again and ask about
 * it. A call made before the request was recorded is a charge that may exist at the bank with nothing in our
 * system to match it against - the one state from which no reconciliation is possible.
 *
 * And because the decider returns `null` when a request already exists, a redelivered `OrderPackagingRequested`
 * does not produce a second request. If the gateway call itself failed, the reconciler - not this policy - is
 * what tries again, using the key already on the recorded event.
 */
@Service
class CaptureFundsWhenPackagedPolicy(
    dependencies: EventProcessorDependencies,
    private val paymentCommandBus: CommandBus,
    private val paymentGateway: PaymentGateway,
    private val awaitingCapture: OrderAwaitingCaptureRepository
) : EventProcessor(dependencies) {

    companion object {
        private val logger = LoggerFactory.getLogger(CaptureFundsWhenPackagedPolicy::class.java)
    }

    override fun getProcessorName(): String = "CaptureFundsWhenPackagedPolicy"

    /**
     * **A new automation must not act on history.**
     *
     * Subscriptions default to starting at the beginning of the stream, which is right for a projection - a
     * read model is *supposed* to be rebuildable by replaying everything - and catastrophic for a policy that
     * touches the outside world. Deployed with the default, this policy woke up, replayed every
     * `CreditCardHoldPlaced` and `OrderPackagingRequested` the store had ever seen, decided each one was
     * complete work, and charged the card for orders that shipped weeks ago.
     *
     * So a *new* subscription for this processor starts at the latest event. Replaying it deliberately is still
     * possible - resetting the subscription is an admin operation - and that is the right place for the
     * decision, because whoever resets it can be told that it will charge cards again.
     *
     * The projections have the opposite default for the opposite reason: replaying `FundsCaptured` rewrites a
     * row, which costs nothing. Replaying "charge this card" costs money.
     */
    override fun isStartSubscriptionFromLatestEvent(): Boolean = true

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(
            PaymentAggregateTypes.CREDIT_CARD_HOLDS,
            ShippingAggregateTypes.SHIPPING_ORDERS
        )

    @MessageHandler
    fun on(e: CreditCardHoldPlaced, message: OrderedMessage) =
        updateThenAct(e.id) { it.authorizedAmount = e.amount }

    @MessageHandler
    fun on(e: OrderPackagingRequested, message: OrderedMessage) =
        updateThenAct(e.id) { it.packaged = true }

    @MessageHandler
    fun on(e: FundsCaptured, message: OrderedMessage) =
        updateThenAct(e.id) { it.outcome = "CAPTURED" }

    @MessageHandler
    fun on(e: FundsCaptureFailed, message: OrderedMessage) =
        updateThenAct(e.id) { it.outcome = "FAILED" }

    private fun updateThenAct(orderId: OrderId, change: (OrderAwaitingCapture) -> Unit) {
        val row = awaitingCapture.findById(orderId.toString())
            .orElseGet { OrderAwaitingCapture(id = orderId.toString()) }
        change(row)
        awaitingCapture.save(row)

        if (!row.needsCapture()) {
            return
        }
        capture(orderId, row)
    }

    private fun capture(orderId: OrderId, row: OrderAwaitingCapture) {
        val amount = row.authorizedAmount!!
        // Derived, not random: every retry of this charge recomputes the same key. See IdempotencyKey.
        val idempotencyKey = IdempotencyKey.forOrderCapture(orderId)

        val requested: FundsCaptureRequested? =
            paymentCommandBus.send(RequestFundsCapture(orderId, amount, idempotencyKey))
        if (requested == null) {
            // Already asked. The answer is outstanding and the reconciler owns it - asking the gateway again
            // here would be safe thanks to the key, but it would also be this policy quietly becoming a retry
            // loop with no backoff and no visibility.
            return
        }

        logger.info("Capturing {} for order '{}' under key '{}'", amount, orderId, idempotencyKey)
        val accepted = paymentGateway.requestCapture(idempotencyKey, orderId, amount)
        logger.info(
            "Gateway accepted the capture for order '{}' as '{}' - the outcome will arrive on the webhook",
            orderId,
            accepted.gatewayReference
        )
    }
}
