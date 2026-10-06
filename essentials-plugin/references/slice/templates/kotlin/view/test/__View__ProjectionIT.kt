package {{packagePath}}.{{bc}}.views.{{view}}

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration test for the {{view}} projection — the test floor for a view slice.
 *
 * A projector needs a real store and a real subscription, so this is an integration test, not a
 * unit test. Extend the project's `IntegrationTestBase` (Testcontainers PostgreSQL) rather than
 * standing up your own container — container reuse is what keeps the suite fast.
 *
 * The two behaviours worth asserting are the ones that break silently in production:
 *   1. the projection applies the event;
 *   2. redelivering the same event does NOT double-apply it.
 */
class {{View}}ProjectionIT {

    @Autowired
    private lateinit var repository: {{View}}Repository

    @Test
    fun `projects {{Event}} into the read model`() {
        // TODO: append {{Event}} to the aggregate's stream, await the projection, assert the row.
    }

    @Test
    fun `redelivery of the same event is idempotent`() {
        // TODO: deliver the same event twice; assert the read model applied it once.
        //       This is what the version = EventOrder check buys you — assert it, or it will rot.
    }

    @Test
    fun `subscription reset rebuilds the read model`() {
        // TODO: reset the subscription and assert onSubscriptionsReset(aggregateType,
        //       resubscribeFromAndIncluding) clears the store and a replay repopulates it.
    }
}
