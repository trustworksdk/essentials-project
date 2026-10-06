package {{packagePath}}.{{bc}}.use_cases.{{slice}}

import {{packagePath}}.{{bc}}.events.{{Aggregate}}Event
import {{packagePath}}.{{bc}}.events.{{Event}}
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
class {{Slice}}Decider : Decider<{{Command}}, {{Aggregate}}Event> {

    override fun handle(cmd: {{Command}}, events: List<{{Aggregate}}Event>): {{Aggregate}}Event? {
        // Idempotency: if this slice's effect is already recorded, do nothing.
        if (events.any { it is {{Event}} }) return null

        // TODO: enforce this slice's invariants here. Record each one in slice.yaml `invariants`
        //       with its `enforcedBy`. A non-trivial invariant warrants a property-based test.
        // require(cmd.placeholder.isNotBlank()) { "placeholder must not be blank" }

        return {{Event}}(cmd.id, cmd.placeholder)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is {{Command}}
}
