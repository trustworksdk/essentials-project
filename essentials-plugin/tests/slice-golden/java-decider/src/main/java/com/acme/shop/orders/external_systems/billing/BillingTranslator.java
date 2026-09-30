package com.acme.shop.orders.external_systems.billing;

/**
 * Anti-corruption layer for the Billing system — the ONLY place the external schema is
 * allowed to appear.
 *
 * This class is deliberately <strong>pure</strong>: no Spring, no Essentials, no I/O imports. That
 * is what makes the mapping unit-testable without either side running, and it is the test floor for
 * a translation slice.
 *
 * Everything past this boundary speaks Billing's language; nothing inside {@code orders}
 * does. If an external type leaks into a Decider, an event, or a view, the ACL has failed.
 */
public class BillingTranslator {

    /** Inbound: external message -> internal command. Record it in slice.yaml {@code maps}. */
    public Object toCommand(InvoiceIssuedPayload external) {
        // TODO: map external fields onto the internal command, converting types at this boundary
        //       (external ids/strings/dates -> the BC's semantic types).
        throw new UnsupportedOperationException("map InvoiceIssuedPayload -> internal command");
    }

    /** Outbound: internal event -> external request shape. */
    public BillingRequest toExternal(Object event) {
        // TODO: map the internal event onto the external contract.
        throw new UnsupportedOperationException("map internal event -> BillingRequest");
    }

    /** External wire shape — mirrors Billing's contract, not ours. */
    public record InvoiceIssuedPayload(String id, String payload) {}

    /** External request shape — mirrors Billing's contract, not ours. */
    public record BillingRequest(String reference, String payload) {}
}
