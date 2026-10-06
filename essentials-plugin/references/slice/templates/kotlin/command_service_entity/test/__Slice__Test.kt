package {{packagePath}}.{{bc}}.entities

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Entity unit test — the test floor's load-bearing half on this lane, and the one most often skipped.
 *
 * There is no `GivenWhenThenScenario` here: that harness replays an event stream, and this lane has
 * none. What replaces it is simpler and stricter — the invariant lives on the entity as a plain
 * method, so it is testable as a plain object. **No Spring, no database, no container.**
 *
 * The idempotency assertion is why this test exists. "Returns false the second time" is three lines
 * here and expensive through an integration test, so it is exactly the assertion that silently goes
 * missing — and it is the entity's whole reason for owning the guard.
 */
class {{Slice}}Test {

    @Test
    fun `applies the change once`() {
        val {{entity}} = {{Entity}}("{{aggregate}}-1", "initial")

        assertThat({{entity}}.applyPlaceholder("changed")).isTrue()
        // TODO: assert the resulting state through the entity's own read path
    }

    @Test
    fun `is idempotent`() {
        val {{entity}} = {{Entity}}("{{aggregate}}-1", "initial")
        {{entity}}.applyPlaceholder("changed")

        // Second application must be a no-op, not an error and not a second change.
        assertThat({{entity}}.applyPlaceholder("changed")).isFalse()
    }

    @Test
    fun `the invariant cannot be bypassed`() {
        // TODO: assert the field applyPlaceholder() guards has no public setter. In Kotlin, declare
        //       it `var` with a `private set` — if this test is awkward to write, that is the
        //       finding (rules/slice-design.md § The entity's own bar).
    }
}
