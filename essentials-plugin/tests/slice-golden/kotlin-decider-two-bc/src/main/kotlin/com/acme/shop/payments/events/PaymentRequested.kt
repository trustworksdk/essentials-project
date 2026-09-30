package com.acme.shop.payments.events

import com.acme.shop.payments.types.PaymentId
import com.fasterxml.jackson.annotation.JsonTypeName

/**
 * Emitted by the request_payment slice. One variant, one file (rules/slice-design.md §R3).
 *
 * This variant is logically OWNED by `use_cases/request_payment/` — record that in the slice's CLAUDE.md.
 * Never collect several variants into one file, and never edit another slice's variant.
 *
 * `@JsonTypeName` names this event's `@type` in its JSON; deserialization itself goes by the recorded
 * class name.
 */
@JsonTypeName("PaymentRequested")
data class PaymentRequested(
    override val id: PaymentId,
    // TODO: replace with the facts this event records
    val placeholder: String
) : PaymentEvent
