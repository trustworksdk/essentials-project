package com.example.shop.orders.views.order_list;

import com.example.shop.IntegrationTestBase;
import com.example.shop.orders.events.OrderPlaced;
import com.example.shop.orders.types.OrderId;
import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CI-only behaviour check for the Java view template (tests/slice-compile/README.md), on the decider and the aggregate
 * lane alike: each test asserts one `implemented` entry of references/slice/templates/behaviours.json against the
 * rendered, untouched skeleton, and the `// behaviour:` line above it names the entry.
 * <p>
 * Events are appended to the Orders stream directly, so the test does not depend on which lane's write side made
 * them. Redelivery is driven through the projection's handler, as the Inbox would hand it a message again.
 */
class OrderListBehavioursIT extends IntegrationTestBase {

    private static final AggregateType ORDERS = AggregateType.of("Orders");
    private static final Duration PROJECTION_WAIT = Duration.ofSeconds(30);

    @Autowired
    private EventStore eventStore;

    @Autowired
    private DocumentDbRepository<OrderListView, String> repository;

    @Autowired
    private OrderListProjection projection;

    // behaviour: java/view view.projects-event
    @Test
    void the_projected_event_inserts_the_row_then_updates_it_with_the_event_order_as_version() {
        var id = OrderId.random();

        append(id, new OrderPlaced(id, "first"));
        var inserted = awaitRow(id.toString(), 0);
        assertThat(inserted.getOrderId()).isEqualTo(id.toString());

        append(id, new OrderPlaced(id, "second"));
        var updated = awaitRow(id.toString(), 1);
        assertThat(updated.getOrderId()).isEqualTo(id.toString());
    }

    // behaviour: java/view view.idempotent-redelivery
    @Test
    void an_event_not_newer_than_the_row_changes_nothing() {
        var id = OrderId.random();
        var event = new OrderPlaced(id, "redelivered");

        projection.on(event, OrderedMessage.of(event, id.toString(), 5));
        var first = repository.findById(id.toString());
        assertThat(first.getVersionValue()).isEqualTo(5);

        projection.on(event, OrderedMessage.of(event, id.toString(), 5));   // the same event again
        projection.on(event, OrderedMessage.of(event, id.toString(), 3));   // an older one
        var unchanged = repository.findById(id.toString());
        assertThat(unchanged.getVersionValue()).isEqualTo(5);
        assertThat(unchanged.getLastUpdated()).isEqualTo(first.getLastUpdated());

        projection.on(event, OrderedMessage.of(event, id.toString(), 6));   // a newer one still applies
        assertThat(repository.findById(id.toString()).getVersionValue()).isEqualTo(6);
    }

    // behaviour: java/view view.reset-rebuild
    @Test
    void a_subscription_reset_deletes_every_row_and_the_replay_rebuilds_them() {
        var id = OrderId.random();
        append(id, new OrderPlaced(id, "replayed"));
        awaitRow(id.toString(), 0);
        var orphan = "backed-by-no-event-" + id;
        repository.save(new OrderListView(orphan, "TODO"), 0L);   // only the reset's delete removes this row

        projection.resetAllSubscriptions();

        await().atMost(PROJECTION_WAIT).untilAsserted(() -> {
            assertThat(repository.findById(orphan)).isNull();
            assertThat(repository.findById(id.toString())).isNotNull();
        });
    }

    // behaviour: java/view view.query-endpoint
    @Test
    void a_get_on_the_view_path_returns_the_rows() throws Exception {
        var id = OrderId.random();
        append(id, new OrderPlaced(id, "listed"));
        awaitRow(id.toString(), 0);

        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/orders")).build(),
                                       HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"" + id + "\"");
        }
    }

    private void append(OrderId id, Object event) {
        eventStore.getUnitOfWorkFactory().usingUnitOfWork(() -> eventStore.appendToStream(ORDERS, id, event));
    }

    private OrderListView awaitRow(String id, long version) {
        return await().atMost(PROJECTION_WAIT)
                      .until(() -> repository.findById(id),
                             row -> Objects.nonNull(row) && row.getVersionValue() == version);
    }
}
