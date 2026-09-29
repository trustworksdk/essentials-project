package {{packagePath}}.orders.use_cases.place_order

import {{packagePath}}.orders.routing.OrderCommand
import {{packagePath}}.orders.types.OrderId

/** Concrete command for the place_order slice. Implements the OrderCommand routing interface. */
data class PlaceOrder(
    override val id: OrderId,
    val sku: String,
    val quantity: Int
) : OrderCommand
