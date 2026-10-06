package {{packagePath}}.{{bc}}.views.{{view}};

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test for the {{view}} view slice — the test floor on this lane.
 *
 * A projection interface is resolved by Spring Data at startup against the entity's properties, so a
 * renamed field breaks it at **runtime**, not at compile time. That is what this test is for: it is
 * the only thing standing between a rename in `entities/` and a broken endpoint.
 *
 * There is no idempotency or replay test here — this lane has no projector and no redelivery. What
 * replaces them is the read-shape assertion below.
 *
 * Extend the project's {@code IntegrationTestBase} (Testcontainers) rather than standing up your own
 * container.
 */
class {{View}}IT {

    @Autowired
    private {{View}}Queries queries;

    @Test
    void theProjectionResolvesAgainstTheEntity() {
        // TODO: seed one {{Entity}} row, query it, assert every getter on {{View}}View returns the
        //       seeded value. A getter naming a property the entity does not have fails here.
    }

    @Test
    void theQueryFilters() {
        // TODO: once the slice has a filtering query (findByStatus, …), seed rows that differ on it and
        //       assert it returns only the matching ones.
    }

    @Test
    void theReadIsStronglyConsistentWithTheWrite() {
        // TODO: send the command that changes the row, then query without waiting.
        //       Same table, same transaction — no awaitility, no polling. If this test needs a
        //       wait, something has introduced asynchrony that this lane does not have.
    }
}
