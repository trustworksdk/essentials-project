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

package dk.trustworks.essentials.examples.webshop.payment.use_cases.record_capture_outcome

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldEvent
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldPlaced
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureFailed
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureRequested
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptured
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.Amount
import org.junit.jupiter.api.Test

class RecordCaptureOutcomeDeciderTest {

    private val scenario = GivenWhenThenScenario<RecordCaptureOutcome, CreditCardHoldEvent>(RecordCaptureOutcomeDecider())

    private val orderId = OrderId.random()
    private val amount = Amount.of("1999.50")
    private val key = IdempotencyKey.forOrderCapture(orderId)

    private fun asked() = listOf(
        CreditCardHoldPlaced(orderId, amount, "AUTH-1234"),
        FundsCaptureRequested(orderId, amount, key)
    )

    @Test
    fun `A successful outcome settles the charge`() {
        scenario
            .given(*asked().toTypedArray())
            .when_(RecordCaptureOutcome(orderId, key, gatewayReference = "CAP-7788"))
            .then_(FundsCaptured(orderId, amount, key, "CAP-7788"))
    }

    @Test
    fun `A refusal is recorded as a fact, with the reason`() {
        scenario
            .given(*asked().toTypedArray())
            .when_(RecordCaptureOutcome(orderId, key, failureReason = "Card cancelled"))
            .then_(FundsCaptureFailed(orderId, amount, key, "Card cancelled"))
    }

    @Test
    fun `The duplicate webhook delivery records nothing the second time`() {
        scenario
            .given(*asked().toTypedArray(), FundsCaptured(orderId, amount, key, "CAP-7788"))
            .when_(RecordCaptureOutcome(orderId, key, gatewayReference = "CAP-7788"))
            .thenExpectNoEvent()
    }

    @Test
    fun `An outcome for a charge we never asked for is retried, not rejected`() {
        scenario
            .given(CreditCardHoldPlaced(orderId, amount, "AUTH-1234"))
            .when_(RecordCaptureOutcome(orderId, key, gatewayReference = "CAP-7788"))
            .thenFailsWithException(CaptureNotYetRequestedException(orderId))
    }

    @Test
    fun `An outcome carrying someone else's idempotency key is refused permanently`() {
        val otherKey = IdempotencyKey.of("capture:some-other-order")
        scenario
            .given(*asked().toTypedArray())
            .when_(RecordCaptureOutcome(orderId, otherKey, gatewayReference = "CAP-7788"))
            .thenFailsWithException(UnknownCaptureException(orderId, otherKey))
    }
}
