package {{packagePath}}.orders.routing

import {{packagePath}}.orders.types.OrderId

/**
 * Aggregate routing interface for the Order aggregate (BC-private).
 *
 * Every concrete command (in its own command slice under `use_cases/`) implements
 * this marker so the `DeciderAndAggregateTypeConfigurator` can route it to the Order
 * aggregate and extract the aggregate id. Routing interfaces live in `routing/`,
 * NEVER in `use_cases/` — see rules/slice-design.md § Directory vocabulary.
 */
interface OrderCommand {
    val id: OrderId
}
