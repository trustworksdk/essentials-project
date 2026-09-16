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

import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldEvent
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldPlaced
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldRejected
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.Amount
import org.junit.jupiter.api.Test

/**
 * The idempotency test here is the one that matters most in the whole application: the automation that sends
 * this command runs on an at-least-once subscription, so "the same command twice must not hold twice" is the
 * difference between a correct system and one that authorizes a customer's card repeatedly after a restart.
 */
class PlaceHoldOnCreditCardDeciderTest {

    private val scenario =
        GivenWhenThenScenario<PlaceHoldOnCreditCard, CreditCardHoldEvent>(PlaceHoldOnCreditCardDecider())

    private val orderId = OrderId.random()
    private val total = Amount.of("1999.50")

    @Test
    fun `An authorized hold is recorded`() {
        scenario
            .given()
            .when_(PlaceHoldOnCreditCard(orderId, total, authorizationCode = "AUTH-1234"))
            .then_(CreditCardHoldPlaced(orderId, total, "AUTH-1234"))
    }

    @Test
    fun `A decline is recorded too`() {
        scenario
            .given()
            .when_(PlaceHoldOnCreditCard(orderId, total, declineReason = "Insufficient funds"))
            .then_(CreditCardHoldRejected(orderId, total, "Insufficient funds"))
    }

    @Test
    fun `A redelivered command does not hold the amount a second time`() {
        scenario
            .given(CreditCardHoldPlaced(orderId, total, "AUTH-1234"))
            .when_(PlaceHoldOnCreditCard(orderId, total, authorizationCode = "AUTH-5678"))
            .thenExpectNoEvent()
    }

    @Test
    fun `A declined card may be retried`() {
        scenario
            .given(CreditCardHoldRejected(orderId, total, "Insufficient funds"))
            .when_(PlaceHoldOnCreditCard(orderId, total, authorizationCode = "AUTH-5678"))
            .then_(CreditCardHoldPlaced(orderId, total, "AUTH-5678"))
    }

    @Test
    fun `A command with neither outcome is a programming error`() {
        scenario
            .given()
            .when_(PlaceHoldOnCreditCard(orderId, total))
            .thenFailsWithExceptionType(IllegalArgumentException::class)
    }
}
