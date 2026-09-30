package com.acme.shop.payments.use_cases.request_payment;

import com.acme.shop.payments.events.PaymentRequested;
import com.acme.shop.payments.types.PaymentId;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import org.junit.jupiter.api.Test;

/**
 * Decider unit test — no database, no Spring context, no mocks. Millisecond execution.
 *
 * This is the test floor for a command slice: every invariant recorded in {@code slice.yaml} should
 * have a case here. Invariants over a non-trivial input space (calculations, state machines, money,
 * temporal logic) warrant a property-based test in addition.
 */
class RequestPaymentTest {

    @Test
    void requestPaymentEmitsPaymentRequested() {
        var scenario = new GivenWhenThenScenario<>(new RequestPaymentDecider());
        var id = PaymentId.random();

        scenario
                .given()
                .when(new RequestPayment(id, "value"))
                .then(new PaymentRequested(id, "value"));
    }

    @Test
    void requestPaymentIsIdempotent() {
        var scenario = new GivenWhenThenScenario<>(new RequestPaymentDecider());
        var id = PaymentId.random();

        scenario
                .given(new PaymentRequested(id, "value"))
                .when(new RequestPayment(id, "value"))
                .thenExpectNoEvent();
    }

    // TODO: one test per invariant enforced by RequestPaymentDecider.
    // Rejections use .thenThrows(SomeException.class).
}
