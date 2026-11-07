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

package dk.trustworks.essentials.examples.webshop.payment.use_cases.request_funds_capture

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldEvent
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldPlaced
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureRequested
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptured
import org.springframework.stereotype.Service

/**
 * You cannot take money you were never authorized to take, and you must not ask twice for the same money.
 *
 * Both rules come out of this order's own `CreditCardHolds` stream, which is the whole argument for putting the
 * hold and the capture in one stream: they are the same consistency boundary. "Has this been authorized?" and
 * "have we already asked?" are questions about the same aggregate, so no other context and no read model is
 * involved in answering them.
 *
 * The idempotency check here is the *inner* of two. This one stops a second **request** from being recorded;
 * the key carried on the event stops a second **charge** at the gateway if the request is sent twice anyway.
 * Neither alone is enough: this decider cannot prevent a timed-out call from having succeeded at the bank, and
 * the key cannot prevent a stream from accumulating duplicate requests.
 */
@Service
class RequestFundsCaptureDecider : Decider<RequestFundsCapture, CreditCardHoldEvent> {

    override fun handle(cmd: RequestFundsCapture, events: List<CreditCardHoldEvent>): FundsCaptureRequested? {
        if (events.none { it is CreditCardHoldPlaced }) {
            throw NoHoldToCaptureException(cmd.id)
        }
        if (events.any { it is FundsCaptured }) {
            return null   // settled already - a redelivered packaging event, not a second charge
        }
        if (events.any { it is FundsCaptureRequested }) {
            return null   // asked already; the answer is outstanding, and the reconciler owns it from here
        }
        return FundsCaptureRequested(cmd.id, cmd.amount, cmd.idempotencyKey)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is RequestFundsCapture
}
