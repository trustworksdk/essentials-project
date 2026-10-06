package {{packagePath}}.{{bc}}.use_cases.{{slice}}

import {{packagePath}}.{{bc}}.events.{{Event}}
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id
import dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario
import org.junit.jupiter.api.Test

/**
 * Decider unit test — no database, no Spring context, no mocks. Millisecond execution.
 *
 * This is the test floor for a command slice: every invariant recorded in `slice.yaml` should have
 * a case here. Invariants over a non-trivial input space (calculations, state machines, money,
 * temporal logic) warrant a property-based test in addition.
 */
class {{Slice}}Test {

    private val scenario = GivenWhenThenScenario({{Slice}}Decider())

    @Test
    fun `{{slice}} emits {{Event}}`() {
        val id = {{Aggregate}}Id.random()

        scenario
            .given()
            .when_({{Command}}(id, "value"))
            .then_({{Event}}(id, "value"))
    }

    @Test
    fun `{{slice}} is idempotent`() {
        val id = {{Aggregate}}Id.random()

        scenario
            .given({{Event}}(id, "value"))
            .when_({{Command}}(id, "value"))
            .thenExpectNoEvent()
    }

    // TODO: one test per invariant enforced by {{Slice}}Decider.
    // Rejections assert the thrown exception; see the Essentials GivenWhenThenScenario reference.
}
