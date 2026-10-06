package com.example.shop.orders.use_cases.cancel_order;

import com.example.shop.orders.events.OrderCancelled;
import com.example.shop.orders.types.OrderId;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import org.junit.jupiter.api.Test;

/**
 * Decider unit test — no database, no Spring context, no mocks. Millisecond execution.
 *
 * This is the test floor for a command slice: every invariant recorded in {@code slice.yaml} should
 * have a case here. Invariants over a non-trivial input space (calculations, state machines, money,
 * temporal logic) warrant a property-based test in addition.
 */
class CancelOrderTest {

    @Test
    void cancelOrderEmitsOrderCancelled() {
        var scenario = new GivenWhenThenScenario<>(new CancelOrderDecider());
        var id = OrderId.random();

        scenario
                .given()
                .when(new CancelOrder(id, "value"))
                .then(new OrderCancelled(id, "value"));
    }

    @Test
    void cancelOrderIsIdempotent() {
        var scenario = new GivenWhenThenScenario<>(new CancelOrderDecider());
        var id = OrderId.random();

        scenario
                .given(new OrderCancelled(id, "value"))
                .when(new CancelOrder(id, "value"))
                .thenExpectNoEvent();
    }

    // TODO: one test per invariant enforced by CancelOrderDecider.
    // Rejections use .thenThrows(SomeException.class).
}
