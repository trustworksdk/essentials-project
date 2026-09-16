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

package dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.Amount
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The card network, behind a port.
 *
 * This is the one place in the application where a **synchronous request/response** call is the right shape: an
 * authorization is a question to a third party that either answers or fails, and there is nothing to record
 * until it has. Everything else in the flow is an event.
 *
 * The call sits in a translation slice rather than inside a decider, and that boundary is the point. A decider
 * must stay a pure function of `(command, events)` so it can be tested without a network and replayed without
 * re-charging anyone's card. So the automation calls this, and then sends a command carrying the **outcome** -
 * the authorization code or the refusal - which the decider simply records.
 */
interface PaymentGateway {
    fun placeHold(orderId: OrderId, amount: Amount): HoldResult
}

/** What the gateway answered. Not an event - it becomes one only after a decider has accepted it. */
sealed interface HoldResult {
    data class Authorized(val authorizationCode: String) : HoldResult
    data class Declined(val reason: String) : HoldResult
}

/**
 * The demo's stand-in for a real gateway: it authorizes everything except amounts above a threshold, so the
 * rejection path can be demonstrated without a test card.
 *
 * A real implementation would be the only thing that changes, and only this file would know.
 */
@Service
class InMemoryPaymentGateway : PaymentGateway {

    companion object {
        private val logger = LoggerFactory.getLogger(InMemoryPaymentGateway::class.java)

        /** Anything above this is declined, to make the unhappy path reachable in a demo. */
        private val DECLINE_ABOVE: Amount = Amount.of("10000.00")
    }

    override fun placeHold(orderId: OrderId, amount: Amount): HoldResult {
        logger.info("Asking the payment gateway to hold {} for order '{}'", amount, orderId)
        return if (amount.compareTo(DECLINE_ABOVE) > 0) {
            HoldResult.Declined("Amount $amount exceeds the authorization limit")
        } else {
            HoldResult.Authorized(UUID.randomUUID().toString().take(8).uppercase())
        }
    }
}
