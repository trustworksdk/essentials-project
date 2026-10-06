package {{packagePath}}.{{bc}}.types

import dk.trustworks.essentials.kotlin.types.StringValueType
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator

/**
 * Strongly-typed aggregate id for the {{Bc}} bounded context.
 *
 * BC-internal value object — lives in `{{bc}}/types/` because it is shared by several slices within
 * this BC. A type used by only ONE slice stays inside that slice's directory; do not prematurely
 * promote.
 *
 * Never use a raw `String` or `UUID` for an identity — a semantic type is what stops an
 * {{Aggregate}}Id being passed where some other id was meant.
 */
@JvmInline
value class {{Aggregate}}Id(override val value: String) : StringValueType<{{Aggregate}}Id> {
    companion object {
        fun of(value: String) = {{Aggregate}}Id(value)
        fun random() = {{Aggregate}}Id(RandomIdGenerator.generate())
    }
}
