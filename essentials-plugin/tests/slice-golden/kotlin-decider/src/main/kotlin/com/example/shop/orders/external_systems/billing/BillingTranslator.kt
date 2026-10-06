package com.example.shop.orders.external_systems.billing

/**
 * Anti-corruption layer for the Billing system — the ONLY place the external schema is
 * allowed to appear.
 *
 * This class is deliberately **pure**: no Spring, no Essentials, no I/O imports. That is what makes
 * the mapping unit-testable without either side running, and it is the test floor for a translation
 * slice.
 *
 * Everything past this boundary speaks Billing's language; nothing inside `orders` does.
 * If an external type leaks into a Decider, an event, or a view, the ACL has failed.
 */
class BillingTranslator {

    /** Inbound: external message -> internal command. Record it in slice.yaml `maps`. */
    fun toCommand(external: InvoiceIssuedPayload): Any {
        // TODO: map external fields onto the internal command, converting types at this boundary
        //       (external ids/strings/dates -> the BC's semantic types).
        TODO("map InvoiceIssuedPayload -> internal command")
    }

    /** Outbound: internal event -> external request shape. */
    fun toExternal(event: Any): BillingRequest {
        // TODO: map the internal event onto the external contract.
        TODO("map internal event -> BillingRequest")
    }
}

/** External wire shape — mirrors Billing's contract, not ours. Never used outside this slice. */
data class InvoiceIssuedPayload(
    val id: String,
    val payload: String
)

/** External request shape — mirrors Billing's contract, not ours. */
data class BillingRequest(
    val reference: String,
    val payload: String
)
