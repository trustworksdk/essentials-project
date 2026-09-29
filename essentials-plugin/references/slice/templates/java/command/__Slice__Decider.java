package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.events.{{Aggregate}}Event;
import {{packagePath}}.{{bc}}.events.{{Event}};
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.util.List;
import java.util.Optional;

/**
 * Decider for THIS slice only — the standard Essentials {@code EventStreamDecider<COMMAND, EVENT>}
 * design, one Decider class per command type (rules/slice-design.md §R1).
 *
 * NEVER a shared Decider with a {@code switch (command)} over many commands: that is the
 * god-Decider anti-pattern that makes every new slice edit the same file.
 *
 * Pure: {@code handle(cmd, events) -> Optional<EVENT>}. Return {@code Optional.empty()} for an
 * idempotent no-op, throw to reject. No I/O, no repositories, no read-model queries — a decider that
 * reads a read model to check an invariant is racy by construction (use a transaction-time
 * uniqueness projection instead).
 *
 * NOTE: Java uses {@code EventStreamDecider}; Kotlin uses {@code kotlin.eventsourcing.Decider}.
 * They are different APIs — do not mix them.
 */
public class {{Slice}}Decider implements EventStreamDecider<{{Command}}, {{Aggregate}}Event> {

    @Override
    public Optional<{{Aggregate}}Event> handle({{Command}} cmd, List<{{Aggregate}}Event> events) {
        // Idempotency: if this slice's effect is already recorded, do nothing.
        if (events.stream().anyMatch(e -> e instanceof {{Event}})) {
            return Optional.empty();
        }

        // TODO: enforce this slice's invariants here. Record each one in slice.yaml `invariants`
        //       with its `enforcedBy`. A non-trivial invariant warrants a property-based test.
        // if (cmd.placeholder().isBlank()) throw new IllegalArgumentException("placeholder required");

        return Optional.of(new {{Event}}(cmd.id(), cmd.placeholder()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return {{Command}}.class == command;
    }
}
