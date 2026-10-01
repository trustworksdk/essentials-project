package com.example.shop.orders.entities;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity unit test — the test floor's load-bearing half on this lane, and the one most often skipped.
 *
 * There is no {@code GivenWhenThenScenario} here: that harness replays an event stream, and this lane
 * has none. What replaces it is simpler and stricter — the invariant lives on the entity as a plain
 * method, so it is testable as a plain object. **No Spring, no database, no container.**
 *
 * The idempotency assertion is why this test exists. "Returns false the second time" is three lines
 * to assert here and expensive to assert through an integration test, so it is exactly the assertion
 * that silently goes missing — and it is the entity's whole reason for owning the guard.
 */
class PlaceOrderTest {

    @Test
    void appliesTheChangeOnce() {
        var order = new Order("order-1", "initial");

        assertThat(order.applyPlaceholder("changed")).isTrue();
        // TODO: assert the resulting state through the entity's own read path
    }

    @Test
    void isIdempotent() {
        var order = new Order("order-1", "initial");
        order.applyPlaceholder("changed");

        // Second application must be a no-op, not an error and not a second change.
        assertThat(order.applyPlaceholder("changed")).isFalse();
    }

    @Test
    void theInvariantCannotBeBypassed() {
        // TODO: assert there is no public setter for the field applyPlaceholder() guards.
        //       If this test is awkward to write, that is the finding — see
        //       rules/slice-design.md § The entity's own bar.
    }
}
