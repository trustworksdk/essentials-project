package com.acme.shop.orders.use_cases.cancel_order

import com.acme.shop.orders.events.OrderCancelled
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
class CancelOrderTest {

    private val scenario = GivenWhenThenScenario(CancelOrderDecider())

    @Test
    fun `cancel_order emits OrderCancelled`() {
        val id = OrderId.random()

        scenario
            .given()
            .when_(CancelOrder(id, "value"))
            .then_(OrderCancelled(id, "value"))
    }

    @Test
    fun `cancel_order is idempotent`() {
        val id = OrderId.random()

        scenario
            .given(OrderCancelled(id, "value"))
            .when_(CancelOrder(id, "value"))
            .thenExpectNoEvent()
    }

    // TODO: one test per invariant enforced by CancelOrderDecider.
    // Rejections assert the thrown exception; see the Essentials GivenWhenThenScenario reference.
}
