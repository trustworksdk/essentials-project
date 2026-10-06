package {{packagePath}}.orders.use_cases.cancel_order

import {{packagePath}}.orders.types.OrderId
import {{packagePath}}.orders.types.OrderStatus

/**
 * Immutable Order state, rebuilt from the event stream by [OrderStateEvolver].
 *
 * PER-SLICE, which is the DEFAULT (rules/slice-design.md § The `_shared/` promotion
 * bar). It holds only what THIS Decider reads. `place_order` needs no folded state, so
 * there is nothing to share — moving this to `use_cases/_shared/` would be premature
 * promotion with a single consumer.
 *
 * The bar for `_shared/` is THREE Deciders needing this SAME state, none of them
 * requiring a field the others do not. Promotion is then a plain move: both type names
 * stay, only the package changes.
 */
data class OrderState(
    val orderId: OrderId,
    val status: OrderStatus
) {
    fun canBeCancelled() = status == OrderStatus.PLACED
}
