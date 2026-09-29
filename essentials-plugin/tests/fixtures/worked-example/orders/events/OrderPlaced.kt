package {{packagePath}}.orders.events

import {{packagePath}}.orders.types.OrderId
import com.fasterxml.jackson.annotation.JsonTypeName

/** Emitted by the place_order slice. One variant, one file (rules/slice-design.md §R3). */
@JsonTypeName("OrderPlaced")
data class OrderPlaced(
    override val id: OrderId,
    val sku: String,
    val quantity: Int
) : OrderEvent
