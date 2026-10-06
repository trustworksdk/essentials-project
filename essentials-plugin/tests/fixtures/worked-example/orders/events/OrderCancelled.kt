package {{packagePath}}.orders.events

import {{packagePath}}.orders.types.OrderId
import com.fasterxml.jackson.annotation.JsonTypeName

/** Emitted by the cancel_order slice. One variant, one file (rules/slice-design.md §R3). */
@JsonTypeName("OrderCancelled")
data class OrderCancelled(
    override val id: OrderId,
    val reason: String
) : OrderEvent
