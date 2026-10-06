package com.example.shop.orders.views.order_list

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration test for the order_list view slice — the test floor on this lane.
 *
 * A projection interface is resolved by Spring Data at startup against the entity's properties, so a
 * renamed field breaks it at **runtime**, not at compile time. That is what this test is for: it is
 * the only thing standing between a rename in `entities/` and a broken endpoint.
 *
 * There is no idempotency or replay test here — this lane has no projector and no redelivery.
 *
 * Extend the project's `IntegrationTestBase` (Testcontainers) rather than standing up your own
 * container.
 */
class OrderListIT {

    @Autowired
    private lateinit var queries: OrderListQueries

    @Test
    fun `the projection resolves against the entity`() {
        // TODO: seed one Order row, query it, assert every property on OrderListView returns the
        //       seeded value. A property naming something the entity lacks fails here.
    }

    @Test
    fun `the query filters`() {
        // TODO: once the slice has a filtering query (findByStatus, …), seed rows that differ on it and
        //       assert it returns only the matching ones.
    }

    @Test
    fun `the read is strongly consistent with the write`() {
        // TODO: send the command that changes the row, then query without waiting.
        //       Same table, same transaction — no awaitility, no polling. If this test needs a
        //       wait, something has introduced asynchrony this lane does not have.
    }
}
