package {{packagePath}}.{{bc}}.views.{{view}};

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test for the {{view}} projection — the test floor for a view slice.
 *
 * A projector needs a real store and a real subscription, so this is an integration test, not a
 * unit test. Extend the project's {@code IntegrationTestBase} (Testcontainers PostgreSQL) rather
 * than standing up your own container — container reuse is what keeps the suite fast.
 *
 * The two behaviours worth asserting are the ones that break silently in production:
 * <ol>
 *   <li>the projection applies the event;</li>
 *   <li>redelivering the same event does NOT double-apply it.</li>
 * </ol>
 */
class {{View}}ProjectionIT {

    @Autowired
    private DocumentDbRepository<{{View}}View, String> repository;

    @Test
    void projects{{Event}}IntoTheReadModel() {
        // TODO: append {{Event}} to the aggregate's stream, await the projection, assert the row.
    }

    @Test
    void redeliveryOfTheSameEventIsIdempotent() {
        // TODO: deliver the same event twice; assert the read model applied it once.
        //       This is what the version = EventOrder check buys you — assert it, or it will rot.
    }

    @Test
    void subscriptionResetRebuildsTheReadModel() {
        // TODO: reset the subscription and assert onSubscriptionsReset(aggregateType,
        //       resubscribeFromAndIncluding) clears the store and a replay repopulates it.
    }
}
