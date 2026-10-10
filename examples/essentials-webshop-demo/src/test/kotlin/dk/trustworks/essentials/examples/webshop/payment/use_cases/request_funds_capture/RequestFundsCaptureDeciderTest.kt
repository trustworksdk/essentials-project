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

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldEvent
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldPlaced
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldRejected
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureRequested
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptured
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.Amount
import org.junit.jupiter.api.Test

class RequestFundsCaptureDeciderTest {

    private val scenario = GivenWhenThenScenario<RequestFundsCapture, CreditCardHoldEvent>(RequestFundsCaptureDecider())

    private val orderId = OrderId.random()
    private val amount = Amount.of("1999.50")
    private val key = IdempotencyKey.forOrderCapture(orderId)

    private fun holdPlaced() = CreditCardHoldPlaced(orderId, amount, "AUTH-1234")

    @Test
    fun `An authorized order can have its funds captured`() {
        scenario
            .given(holdPlaced())
            .when_(RequestFundsCapture(orderId, amount, key))
            .then_(FundsCaptureRequested(orderId, amount, key))
    }

    @Test
    fun `Asking twice records one request, so the gateway is asked about one charge`() {
        scenario
            .given(holdPlaced(), FundsCaptureRequested(orderId, amount, key))
            .when_(RequestFundsCapture(orderId, amount, key))
            .thenExpectNoEvent()
    }

    @Test
    fun `A settled order is never captured again`() {
        scenario
            .given(holdPlaced(), FundsCaptureRequested(orderId, amount, key), FundsCaptured(orderId, amount, key, "CAP-1"))
            .when_(RequestFundsCapture(orderId, amount, key))
            .thenExpectNoEvent()
    }

    @Test
    fun `Funds that were never authorized cannot be captured`() {
        scenario
            .given(CreditCardHoldRejected(orderId, amount, "Amount exceeds the authorization limit"))
            .when_(RequestFundsCapture(orderId, amount, key))
            .thenFailsWithException(NoHoldToCaptureException(orderId))
    }
}
