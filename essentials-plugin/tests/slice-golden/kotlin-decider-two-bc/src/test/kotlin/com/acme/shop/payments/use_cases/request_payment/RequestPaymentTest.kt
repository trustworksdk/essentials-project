package com.acme.shop.payments.use_cases.request_payment

import com.acme.shop.payments.events.PaymentRequested
import com.acme.shop.payments.types.PaymentId
import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import org.junit.jupiter.api.Test

/**
 * Decider unit test — no database, no Spring context, no mocks. Millisecond execution.
 *
 * This is the test floor for a command slice: every invariant recorded in `slice.yaml` should have
 * a case here. Invariants over a non-trivial input space (calculations, state machines, money,
 * temporal logic) warrant a property-based test in addition.
 */
class RequestPaymentTest {

    private val scenario = GivenWhenThenScenario(RequestPaymentDecider())

    @Test
    fun `request_payment emits PaymentRequested`() {
        val id = PaymentId.random()

        scenario
            .given()
            .when_(RequestPayment(id, "value"))
            .then_(PaymentRequested(id, "value"))
    }

    @Test
    fun `request_payment is idempotent`() {
        val id = PaymentId.random()

        scenario
            .given(PaymentRequested(id, "value"))
            .when_(RequestPayment(id, "value"))
            .thenExpectNoEvent()
    }

    // TODO: one test per invariant enforced by RequestPaymentDecider.
    // Rejections assert the thrown exception; see the Essentials GivenWhenThenScenario reference.
}
