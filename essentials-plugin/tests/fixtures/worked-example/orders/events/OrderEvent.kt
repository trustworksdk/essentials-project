package {{packagePath}}.orders.events

import {{packagePath}}.orders.types.OrderId
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Public event contract for the Order aggregate — the SEALED PARENT ONLY.
 *
 * Anti-god-class rule (rules/slice-design.md §R3): do NOT collect every variant in
 * this one file. Each variant lives in its OWN file in this same `events/` package
 * (Kotlin requires sealed subtypes to share the parent's package + module), and is
 * logically OWNED by the slice that emits it — recorded in that slice's CLAUDE.md:
 *   - OrderPlaced     → owned by use_cases/place_order
 *   - OrderCancelled  → owned by use_cases/cancel_order
 * Adding a new command means adding a new variant FILE here + a new slice — never
 * editing another slice's variant.
 *
 * `@JsonTypeInfo` is required on sealed interfaces persisted via the Essentials
 * event store (its ObjectMapper does not use Jackson default typing) — without it
 * deserialization silently fails and projections get stuck. Each variant adds its
 * own `@JsonTypeName`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
sealed interface OrderEvent {
    val id: OrderId
}
