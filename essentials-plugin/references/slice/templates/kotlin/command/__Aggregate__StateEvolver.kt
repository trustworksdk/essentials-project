package {{packagePath}}.{{bc}}.use_cases.{{slice}}

import {{packagePath}}.{{bc}}.events.{{Aggregate}}Event
import dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver

/**
 * Pure left-fold `(event, state) -> state` rebuilding [{{Aggregate}}State] from the stream.
 *
 * Owned by THIS slice — used by its Decider via `Evolver.applyEvents(...)`. Pure: no I/O, no
 * validation, no side effects. Validation is the Decider's job; the Evolver only says what is
 * currently true.
 *
 * Moves to `use_cases/_shared/` only under the three-consumer promotion bar — see
 * [{{Aggregate}}State].
 *
 * The `when` is exhaustive over the sealed `{{Aggregate}}Event` hierarchy, so adding a variant
 * makes the compiler point at every evolver that must consider it.
 */
class {{Aggregate}}StateEvolver : Evolver<{{Aggregate}}Event, {{Aggregate}}State> {
    override fun applyEvent(event: {{Aggregate}}Event, state: {{Aggregate}}State?): {{Aggregate}}State? =
        when (event) {
            // TODO: one branch per event variant this BC emits
            else -> state
        }
}
