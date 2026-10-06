package com.example.shop.payments.events

import com.example.shop.payments.types.PaymentId
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Public event contract for the Payment aggregate — the SEALED PARENT ONLY.
 *
 * Anti-god-class rule (rules/slice-design.md §R3): do NOT collect every variant in this one file.
 * Each variant lives in its OWN file in this same `events/` package (Kotlin requires sealed subtypes
 * to share the parent's package + module), and is logically OWNED by the slice that emits it —
 * recorded in that slice's CLAUDE.md. Adding a command means adding a new variant FILE here plus a
 * new slice, never editing another slice's variant.
 *
 * The event store records each event's class name and deserializes by it, so this hierarchy needs no
 * Jackson type metadata to be read back. `@JsonTypeInfo` here only gives each event a logical
 * `@type` in its JSON. A sealed type used as a FIELD inside an event is different and does need
 * it (references/llm/LLM-kotlin-eventsourcing.md).
 *
 * `events/` and `types/` are the ONLY part of this bounded context other code may import.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
sealed interface PaymentEvent {
    val id: PaymentId
}
