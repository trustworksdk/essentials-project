package com.example.shop.payments.events;

import com.example.shop.payments.types.PaymentId;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Emitted by the request_payment slice. One variant, one file (rules/slice-design.md §R3).
 *
 * This variant is logically OWNED by {@code use_cases/request_payment/} — record that in the slice's
 * CLAUDE.md. Never collect several variants into one file, and never edit another slice's variant.
 *
 * JAVA SEALED MECHANICS: this record must also be added to the {@code permits} clause of
 * {@link PaymentEvent}. That one-name append is the single sanctioned cross-slice edit in the
 * slice law — it is a declaration-list change, not a change to another slice's decision-making.
 *
 * {@code @JsonTypeName} names this event's {@code @type} in its JSON; deserialization itself goes by
 * the recorded class name.
 */
@JsonTypeName("PaymentRequested")
public record PaymentRequested(
        PaymentId id,
        // TODO: replace with the facts this event records
        String placeholder
) implements PaymentEvent {
}
