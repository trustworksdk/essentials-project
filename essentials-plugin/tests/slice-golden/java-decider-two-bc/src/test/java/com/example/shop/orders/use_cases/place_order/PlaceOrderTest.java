package com.example.shop.orders.use_cases.place_order;

import com.example.shop.orders.events.OrderPlaced;
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
class PlaceOrderTest {

    @Test
    void placeOrderEmitsOrderPlaced() {
        var scenario = new GivenWhenThenScenario<>(new PlaceOrderDecider());
        var id = OrderId.random();

        scenario
                .given()
                .when(new PlaceOrder(id, "value"))
                .then(new OrderPlaced(id, "value"));
    }

    @Test
    void placeOrderIsIdempotent() {
        var scenario = new GivenWhenThenScenario<>(new PlaceOrderDecider());
        var id = OrderId.random();

        scenario
                .given(new OrderPlaced(id, "value"))
                .when(new PlaceOrder(id, "value"))
                .thenExpectNoEvent();
    }

    // TODO: one test per invariant enforced by PlaceOrderDecider.
    // Rejections use .thenThrows(SomeException.class).
}
