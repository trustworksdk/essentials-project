package {{packagePath}}.{{bc}}.automations.{{slice}}

import org.junit.jupiter.api.Test

/**
 * Integration test for the {{slice}} automation — the test floor for an automation slice.
 *
 * An automation reacts to real events and dispatches real commands, so it needs the event store and
 * the command bus. Extend the project's `IntegrationTestBase` rather than standing up containers
 * here.
 *
 * Assert the process rules, not the plumbing — the guards in `{{Slice}}TodoList` are what this
 * slice actually promises.
 */
class {{Slice}}IT {

    @Test
    fun `{{Event}} advances the process and dispatches the next command`() {
        // TODO: append {{Event}}; assert the TodoList advanced and the command was dispatched.
    }

    @Test
    fun `redelivery of the same event does not dispatch twice`() {
        // The Inbox redelivers. This is the single most important automation test.
    }

    @Test
    fun `retries are bounded and terminal failure compensates`() {
        // TODO: drive the failure path; assert attempts stop at the cap and the compensating
        //       command is issued rather than the process silently stalling.
    }
}
