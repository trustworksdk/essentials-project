package {{packagePath}}.orders.use_cases.cancel_order

import {{packagePath}}.orders.routing.OrderCommand
import {{packagePath}}.orders.types.OrderId

/** Concrete command for the cancel_order slice. */
data class CancelOrder(
    override val id: OrderId,
    val reason: String
) : OrderCommand
