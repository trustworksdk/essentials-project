package com.acme.shop.orders.use_cases.place_order;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Slice integration test — the other half of this lane's test floor.
 *
 * It exists to prove the two things the unit test cannot: that the command actually reaches this
 * slice's handler through the {@code CommandBus}, and that the event is published. Auto-registration
 * is convenient and therefore silent — a handler that is not a bean, or a
 * {@code reactive-bean-post-processor-enabled=false} in some profile, fails no compile and no unit
 * test. This test is what makes that failure loud.
 *
 * Extend the project's {@code IntegrationTestBase} (Testcontainers) rather than standing up your own
 * container — reuse is what keeps the suite fast.
 */
class PlaceOrderIT {

    @Autowired
    private CommandBus commandBus;

    @Test
    void theCommandReachesTheHandlerAndChangesTheRow() {
        // TODO: seed a Order, send PlaceOrder on the bus, assert the row changed.
    }

    @Test
    void publishesOrderPlaced() {
        // TODO: subscribe to the EventBus (or use a test recorder bean) and assert OrderPlaced arrived.
        //       This is the integration contract other slices depend on.
    }

    @Test
    void redeliveryOfTheSameCommandDoesNotChangeTheRowTwice() {
        // TODO: send the same command twice; assert one change and one event.
        //       The guard is on the entity — this asserts the wiring preserves it.
    }
}
