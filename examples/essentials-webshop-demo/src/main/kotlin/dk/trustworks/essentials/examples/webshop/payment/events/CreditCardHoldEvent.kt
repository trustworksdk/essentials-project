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
import dk.trustworks.essentials.types.Amount

/**
 * Every event in the `CreditCardHolds` event stream.
 *
 * Both outcomes are recorded. A rejected authorization is a fact the business needs - it is why the order is
 * stuck, it is what the customer service screen shows, and it is what a retry policy counts. Modelling only the
 * happy path would leave "we tried and the bank said no" living in a log file.
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
