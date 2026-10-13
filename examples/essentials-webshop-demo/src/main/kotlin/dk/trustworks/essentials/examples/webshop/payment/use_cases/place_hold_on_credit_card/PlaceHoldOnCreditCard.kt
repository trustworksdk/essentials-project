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

package dk.trustworks.essentials.examples.webshop.payment.use_cases.place_hold_on_credit_card

import dk.trustworks.essentials.examples.webshop.payment.routing.CreditCardHoldCommand
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.Amount

/**
 * Record what the card network answered.
 *
 * The command carries the **result** of the authorization, not a request to perform one: the gateway call
 * already happened, in the automation that sent this. That split is what keeps the decider pure - it can be
 * replayed, retried and unit-tested without touching anyone's card.
 *
 * [authorizationCode] is set when the bank approved, [declineReason] when it refused. Exactly one of the two is
 * present, which the decider checks.
 */
data class PlaceHoldOnCreditCard(
    override val id: OrderId,
    val amount: Amount,
    val authorizationCode: String? = null,
    val declineReason: String? = null
) : CreditCardHoldCommand
