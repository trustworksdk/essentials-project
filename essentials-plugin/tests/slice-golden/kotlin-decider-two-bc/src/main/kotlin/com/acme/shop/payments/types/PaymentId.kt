package com.acme.shop.payments.types

import dk.trustworks.essentials.kotlin.types.StringValueType
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator

/**
 * Strongly-typed aggregate id for the Payments bounded context.
 *
 * BC-internal value object — lives in `payments/types/` because it is shared by several slices within
 * this BC. A type used by only ONE slice stays inside that slice's directory; do not prematurely
 * promote.
 *
 * Never use a raw `String` or `UUID` for an identity — a semantic type is what stops an
 * PaymentId being passed where some other id was meant.
 */
@JvmInline
value class PaymentId(override val value: String) : StringValueType<PaymentId> {
    companion object {
        fun of(value: String) = PaymentId(value)
        fun random() = PaymentId(RandomIdGenerator.generate())
    }
}
