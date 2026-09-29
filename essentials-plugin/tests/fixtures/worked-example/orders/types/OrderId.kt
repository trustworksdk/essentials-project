package {{packagePath}}.orders.types

import dk.trustworks.essentials.kotlin.types.StringValueType
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator

/**
 * Strongly-typed aggregate id for the Orders bounded context.
 *
 * BC-internal value object — lives in `orders/types/` because it is shared by
 * several slices within this BC. A type used by only ONE slice would stay inside
 * that slice's directory (do not prematurely promote).
 */
@JvmInline
value class OrderId(override val value: String) : StringValueType<OrderId> {
    companion object {
        fun of(value: String) = OrderId(value)
        fun random() = OrderId(RandomIdGenerator.generate())
    }
}
