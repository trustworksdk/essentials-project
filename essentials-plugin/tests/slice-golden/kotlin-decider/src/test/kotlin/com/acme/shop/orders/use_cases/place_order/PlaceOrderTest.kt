package com.acme.shop.orders.use_cases.place_order

import com.acme.shop.orders.events.OrderPlaced
import com.acme.shop.orders.types.OrderId
import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import org.junit.jupiter.api.Test

/**
 * Decider unit test — no database, no Spring context, no mocks. Millisecond execution.
 *
 * This is the test floor for a command slice: every invariant recorded in `slice.yaml` should have
 * a case here. Invariants over a non-trivial input space (calculations, state machines, money,
 * temporal logic) warrant a property-based test in addition.
 */
class PlaceOrderTest {

    private val scenario = GivenWhenThenScenario(PlaceOrderDecider())

    @Test
    fun `place_order emits OrderPlaced`() {
        val id = OrderId.random()

        scenario
            .given()
            .when_(PlaceOrder(id, "value"))
            .then_(OrderPlaced(id, "value"))
    }

    @Test
    fun `place_order is idempotent`() {
        val id = OrderId.random()

        scenario
            .given(OrderPlaced(id, "value"))
            .when_(PlaceOrder(id, "value"))
            .thenExpectNoEvent()
    }

    // TODO: one test per invariant enforced by PlaceOrderDecider.
    // Rejections assert the thrown exception; see the Essentials GivenWhenThenScenario reference.
}
