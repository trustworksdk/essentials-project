package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.events.{{Event}};
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import org.junit.jupiter.api.Test;

/**
 * Decider unit test — no database, no Spring context, no mocks. Millisecond execution.
 *
 * This is the test floor for a command slice: every invariant recorded in {@code slice.yaml} should
 * have a case here. Invariants over a non-trivial input space (calculations, state machines, money,
 * temporal logic) warrant a property-based test in addition.
 */
class {{Slice}}Test {

    @Test
    void {{sliceCamel}}Emits{{Event}}() {
        var scenario = new GivenWhenThenScenario<>(new {{Slice}}Decider());
        var id = {{Aggregate}}Id.random();

        scenario
                .given()
                .when(new {{Command}}(id, "value"))
                .then(new {{Event}}(id, "value"));
    }

    @Test
    void {{sliceCamel}}IsIdempotent() {
        var scenario = new GivenWhenThenScenario<>(new {{Slice}}Decider());
        var id = {{Aggregate}}Id.random();

        scenario
                .given(new {{Event}}(id, "value"))
                .when(new {{Command}}(id, "value"))
                .thenExpectNoEvent();
    }

    // TODO: one test per invariant enforced by {{Slice}}Decider.
    // Rejections use .thenThrows(SomeException.class).
}
