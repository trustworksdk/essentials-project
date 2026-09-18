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

package dk.trustworks.essentials.examples.webshop.payment.events

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.types.Amount

/**
 * Every event in the `CreditCardHolds` event stream: the money side of one order, from authorization to
 * settlement.
 *
 * Both outcomes of both steps are recorded. A rejected authorization is a fact the business needs - it is why
 * the order is stuck, it is what the customer service screen shows, and it is what a retry policy counts.
 * Modelling only the happy path would leave "we tried and the bank said no" living in a log file.
 *
 * **Authorization and capture are two separate steps, deliberately.** The hold reserves the money when the
 * order is placed; the capture takes it when the parcel is about to leave. That is how card payments actually
 * work in retail, and it is also what keeps the failure cases survivable: a capture that fails has cost nothing
 * yet, because nothing has shipped. Collapsing the two into one "charge" event would force the demo to model
 * un-shipping a parcel, which is the one compensation nobody can actually perform.
 */
sealed interface CreditCardHoldEvent {
    val id: OrderId
}

/** The bank authorized this amount against the customer's card. */
data class CreditCardHoldPlaced(
    override val id: OrderId,
    val amount: Amount,
    val authorizationCode: String
) : CreditCardHoldEvent

/** The bank declined. [reason] is the gateway's answer, kept verbatim. */
data class CreditCardHoldRejected(
    override val id: OrderId,
    val amount: Amount,
    val reason: String
) : CreditCardHoldEvent

/**
 * We have asked the gateway to take the money, and we do not yet know what happened.
 *
 * This event exists **because** the answer is not immediate. It is recorded before the gateway is called, so
 * that a request whose answer never arrives is still a request we know we made - which is the only way to
 * reconcile it later. Without it, a timeout would leave no trace of a charge that may or may not have gone
 * through, and "did we charge this customer?" would be unanswerable.
 *
 * [idempotencyKey] is what makes asking again safe. It is carried on the event, not recomputed by whoever
 * retries, so the reconciler asks about the same charge rather than starting a new one.
 */
data class FundsCaptureRequested(
    override val id: OrderId,
    val amount: Amount,
    val idempotencyKey: IdempotencyKey
) : CreditCardHoldEvent

/**
 * The money is ours. [gatewayReference] is the gateway's own identifier for the settled charge, which is what a
 * dispute, a refund or an accountant will ask for.
 */
data class FundsCaptured(
    override val id: OrderId,
    val amount: Amount,
    val idempotencyKey: IdempotencyKey,
    val gatewayReference: String
) : CreditCardHoldEvent

/**
 * The capture failed, and the order must not ship.
 *
 * An authorization can expire, a card can be cancelled between placing the order and packing it, and a bank can
 * simply refuse the settlement it previously approved. This is a normal business outcome, not an error - which
 * is why it is an event with a reason, and why the warehouse's work list reads it.
 */
data class FundsCaptureFailed(
    override val id: OrderId,
    val amount: Amount,
    val idempotencyKey: IdempotencyKey,
    val reason: String
) : CreditCardHoldEvent
