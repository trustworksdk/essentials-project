package com.example.shop.orders.use_cases.place_order

import dk.trustworks.essentials.reactive.command.CommandBus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Slice integration test — the other half of this lane's test floor.
 *
 * It proves the two things the unit test cannot: that the command reaches this slice's handler
 * through the `CommandBus`, and that the event is published. Auto-registration is convenient and
 * therefore silent — a handler that is not a bean, or a
 * `reactive-bean-post-processor-enabled=false` in some profile, fails no compile and no unit test.
 * This test makes that failure loud.
 *
 * Extend the project's `IntegrationTestBase` (Testcontainers) rather than standing up your own
 * container.
 */
class PlaceOrderIT {

    @Autowired
    private lateinit var commandBus: CommandBus

    @Test
    fun `the command reaches the handler and changes the row`() {
        // TODO: seed a Order, send PlaceOrder on the bus, assert the row changed.
    }

    @Test
    fun `publishes OrderPlaced`() {
        // TODO: subscribe to the EventBus (or use a test recorder bean) and assert OrderPlaced arrived.
        //       This is the integration contract other slices depend on.
    }

    @Test
    fun `redelivery of the same command does not change the row twice`() {
        // TODO: send the same command twice; assert one change and one event.
    }
}
