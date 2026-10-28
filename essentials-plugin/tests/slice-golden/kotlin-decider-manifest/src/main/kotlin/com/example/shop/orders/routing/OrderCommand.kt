package com.example.shop.orders.routing

import com.example.shop.orders.types.OrderId

/**
 * Aggregate routing interface for the Order aggregate (BC-private).
 *
 * Every concrete command — each in its own command slice under `use_cases/` — implements this
 * marker so the `DeciderAndAggregateTypeConfigurator` can route it to the Order aggregate
 * and extract the aggregate id.
 *
 * Routing interfaces live in `routing/`, never in `use_cases/`. This interface is deliberately NOT
 * sealed: adding a command is an open/closed extension, so no existing file changes.
 */
interface OrderCommand {
    val id: OrderId
}
