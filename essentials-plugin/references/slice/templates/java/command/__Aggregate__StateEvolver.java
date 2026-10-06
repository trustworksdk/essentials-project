package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.events.{{Aggregate}}Event;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamEvolver;

import java.util.Optional;

/**
 * Pure left-fold {@code (event, state) -> state} rebuilding {@link {{Aggregate}}State} from the
 * stream.
 *
 * Owned by THIS slice — used by its Decider via
 * {@code EventStreamEvolver.applyEvents(evolver, events)}. Pure: no I/O, no validation, no side
 * effects. Validation is the Decider's job; the Evolver only says what is currently true.
 *
 * Moves to {@code use_cases/_shared/} only under the three-consumer promotion bar — see
 * {@link {{Aggregate}}State}.
 *
 * The {@code switch} is exhaustive over the sealed {@link {{Aggregate}}Event} hierarchy, so adding a
 * variant makes the compiler point at every evolver that must consider it — which is the whole
 * reason the event parent is sealed.
 */
public class {{Aggregate}}StateEvolver implements EventStreamEvolver<{{Aggregate}}Event, {{Aggregate}}State> {

    @Override
    public Optional<{{Aggregate}}State> applyEvent({{Aggregate}}Event event, Optional<{{Aggregate}}State> current) {
        return switch (event) {
            // TODO: one branch per event variant this BC emits, e.g.
            // case {{Event}} e -> Optional.of(new {{Aggregate}}State(e.id(), "TODO"));
            default -> current;
        };
    }
}
