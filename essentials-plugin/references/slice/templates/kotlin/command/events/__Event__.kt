package {{packagePath}}.{{bc}}.events

import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id
import com.fasterxml.jackson.annotation.JsonTypeName

/**
 * Emitted by the {{slice}} slice. One variant, one file (rules/slice-design.md §R3).
 *
 * This variant is logically OWNED by `use_cases/{{slice}}/` — record that in the slice's CLAUDE.md.
 * Never collect several variants into one file, and never edit another slice's variant.
 *
 * `@JsonTypeName` names this event's `@type` in its JSON; deserialization itself goes by the recorded
 * class name.
 */
@JsonTypeName("{{Event}}")
data class {{Event}}(
    override val id: {{Aggregate}}Id,
    // TODO: replace with the facts this event records
    val placeholder: String
) : {{Aggregate}}Event
