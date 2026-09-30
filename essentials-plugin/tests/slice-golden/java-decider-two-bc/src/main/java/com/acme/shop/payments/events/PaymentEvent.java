package com.acme.shop.payments.events;

import com.acme.shop.payments.types.PaymentId;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Public event contract for the Payment aggregate — the SEALED PARENT ONLY.
 *
 * Anti-god-class rule (rules/slice-design.md §R3): do NOT collect every variant in this one file.
 * Each variant lives in its OWN file in this same {@code events/} package, logically OWNED by the
 * slice that emits it — recorded in that slice's CLAUDE.md.
 *
 * JAVA SEALED MECHANICS — the one place Java differs from Kotlin: a sealed interface needs an
 * explicit {@code permits} clause when its subtypes are in sibling files. Adding a command slice
 * therefore means appending one name below. That append is the single sanctioned cross-slice edit
 * in the slice law: it is a declaration-list change, not a change to another slice's decision
 * logic, so it does not violate §R4.
 *
 * A {@code permits} clause may not be empty — a sealed type with no subtypes does not compile — so
 * this file is only emitted alongside the bounded context's FIRST command slice, which supplies the
 * first variant. A bounded context scaffolded by a view, automation, or translation slice has no
 * events of its own yet and gets no {@code events/} directory until its first command slice lands.
 *
 * If you would rather avoid the append entirely, a non-sealed marker interface is permitted — but
 * it is not the default, because it forfeits exhaustive {@code switch} checking in evolvers.
 *
 * The event store records each event's class name and deserializes by it, so this hierarchy needs no
 * Jackson type metadata to be read back. {@code @JsonTypeInfo} here only gives each event a logical
 * {@code @type} in its JSON. A sealed type used as a FIELD inside an event is different and does need
 * it (references/llm/LLM-kotlin-eventsourcing.md).
 *
 * {@code events/} and {@code types/} are the ONLY part of this bounded context other code may import.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
public sealed interface PaymentEvent
        permits PaymentRequested {   // /essentials:add-slice appends each new variant to this list

    PaymentId id();
}
