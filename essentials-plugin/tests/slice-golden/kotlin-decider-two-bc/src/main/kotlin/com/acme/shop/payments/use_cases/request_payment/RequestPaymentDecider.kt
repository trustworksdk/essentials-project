package com.acme.shop.payments.use_cases.request_payment

import com.acme.shop.payments.events.PaymentEvent
import com.acme.shop.payments.events.PaymentRequested
import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider

/**
 * Decider for THIS slice only — the standard Essentials `Decider<COMMAND, EVENT>` design, one
 * Decider class per command type (rules/slice-design.md §R1).
 *
 * NEVER a shared Decider with a `when (command)` over many commands: that is the god-Decider
 * anti-pattern that makes every new slice edit the same file.
 *
 * Pure: `handle(cmd, events) -> event?`. Return null for an idempotent no-op, throw to reject.
 * No I/O, no repositories, no read-model queries — a decider that reads a read model to check an
 * invariant is racy by construction (use a transaction-time uniqueness projection instead).
 */
class RequestPaymentDecider : Decider<RequestPayment, PaymentEvent> {

    override fun handle(cmd: RequestPayment, events: List<PaymentEvent>): PaymentEvent? {
        // Idempotency: if this slice's effect is already recorded, do nothing.
        if (events.any { it is PaymentRequested }) return null

        // TODO: enforce this slice's invariants here. Record each one in slice.yaml `invariants`
        //       with its `enforcedBy`. A non-trivial invariant warrants a property-based test.
        // require(cmd.placeholder.isNotBlank()) { "placeholder must not be blank" }

        return PaymentRequested(cmd.id, cmd.placeholder)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is RequestPayment
}
