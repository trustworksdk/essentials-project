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

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldEvent
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldPlaced
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldRejected
import org.springframework.stereotype.Service

/**
 * Records the outcome of one authorization attempt, once.
 *
 * The idempotency check matters more here than anywhere else in the application: the automation that sends this
 * command runs on an at-least-once subscription, so the same `OrderPlaced` can reach it twice after a restart.
 * Without the check below, the second delivery would record a second hold - and in a real system, hold a second
 * amount on a customer's card.
 *
 * A previous **rejection** does not block a new attempt: a declined card can be retried once the customer fixes
 * it. A previous **hold** does.
 */
@Service
class PlaceHoldOnCreditCardDecider : Decider<PlaceHoldOnCreditCard, CreditCardHoldEvent> {

    override fun handle(cmd: PlaceHoldOnCreditCard, events: List<CreditCardHoldEvent>): CreditCardHoldEvent? {
        if (events.any { it is CreditCardHoldPlaced }) {
            return null   // already held - a redelivered OrderPlaced, not a second authorization
        }
        require(cmd.authorizationCode != null || cmd.declineReason != null) {
            "PlaceHoldOnCreditCard for order '${cmd.id}' carries neither an authorization code nor a decline reason"
        }
        return if (cmd.authorizationCode != null) {
            CreditCardHoldPlaced(cmd.id, cmd.amount, cmd.authorizationCode)
        } else {
            CreditCardHoldRejected(cmd.id, cmd.amount, cmd.declineReason!!)
        }
    }

    override fun canHandle(cmd: Any): Boolean = cmd is PlaceHoldOnCreditCard
}
