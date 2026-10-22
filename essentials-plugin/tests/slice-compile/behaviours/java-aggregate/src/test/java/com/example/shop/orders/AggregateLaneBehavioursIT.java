package com.example.shop.orders;

import com.example.shop.IntegrationTestBase;
import com.example.shop.orders.aggregates.Order;
import com.example.shop.orders.aggregates.Orders;
import com.example.shop.orders.events.OrderPlaced;
import com.example.shop.orders.types.OrderId;
import com.example.shop.orders.use_cases.cancel_order.CancelOrder;
import com.example.shop.orders.use_cases.place_order.PlaceOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CI-only behaviour check for the Java aggregate lane (tests/slice-compile/README.md): each test asserts one
 * `implemented` entry of references/slice/templates/behaviours.json against the rendered, untouched skeleton, and the
 * `// behaviour:` line above it names the entry. tests/scripts/test_behaviours_catalog.py fails an `implemented`
 * entry no such line names.
 * <p>
 * Both command slices change an existing Order, so each test first creates one through the aggregate's creating
 * constructor and {@link Orders#saveNew}.
 */
class AggregateLaneBehavioursIT extends IntegrationTestBase {

    @Autowired
    private Orders orders;

    @Autowired
    private CommandBus commandBus;

    @Autowired
    private EventStore eventStore;

    // behaviour: java/bc-scaffold-aggregate bc.aggregate-creation
    @Test
    void the_creating_constructor_rejects_null_and_starts_the_aggregate_with_one_event() {
        var id = OrderId.random();

        assertThatThrownBy(() -> new Order(id, null)).isInstanceOf(IllegalArgumentException.class);
        create(id, "created");

        assertThat(events(id)).containsExactly(new OrderPlaced(id, "created"));
    }

    // behaviour: java/bc-scaffold-aggregate bc.aggregate-decision
    @Test
    void the_decision_method_applies_the_event_on_a_change_and_nothing_on_a_repeat() {
        var id = create(OrderId.random(), "initial");

        boolean changed = inUnitOfWork(() -> orders.getOrder(id).applyPlaceholder("decided"));
        boolean repeated = inUnitOfWork(() -> orders.getOrder(id).applyPlaceholder("decided"));

        assertThat(changed).isTrue();
        assertThat(repeated).isFalse();
        assertThat(events(id)).containsExactly(new OrderPlaced(id, "initial"), new OrderPlaced(id, "decided"));
    }

    // behaviour: java/bc-scaffold-aggregate bc.aggregate-repository
    @Test
    void the_repository_persists_a_new_aggregate_and_loads_it_rebuilt_from_its_events() {
        var missing = OrderId.random();
        assertThat(inUnitOfWork(() -> orders.findOrder(missing))).isEmpty();
        assertThat(inUnitOfWork(() -> orders.hasOrder(missing))).isFalse();
        assertThat(inUnitOfWork(() -> orders.isOrderMissing(missing))).isTrue();
        assertThatThrownBy(() -> inUnitOfWork(() -> orders.getOrder(missing))).isInstanceOf(RuntimeException.class);

        var id = create(OrderId.random(), "stored");

        assertThat(inUnitOfWork(() -> orders.hasOrder(id))).isTrue();
        assertThat(inUnitOfWork(() -> orders.getOrder(id).placeholder())).isEqualTo("stored");
        assertThat(inUnitOfWork(() -> orders.findOrder(id).map(Order::placeholder))).contains("stored");
    }

    // behaviour: java/bc-scaffold-aggregate bc.event-json-type
    @Test
    void the_event_is_written_with_its_type_and_reads_back_equal() {
        var id = create(OrderId.random(), "json");

        var persisted = stream(id);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).event().getJson()).containsPattern("\"@type\"\\s*:\\s*\"OrderPlaced\"");
        assertThat(events(id)).containsExactly(new OrderPlaced(id, "json"));
    }

    // behaviour: java/command_aggregate command.emits-event
    @Test
    void the_handler_loads_the_aggregate_and_its_decision_applies_the_event() {
        var id = create(OrderId.random(), "initial");

        commandBus.send(new PlaceOrder(id, "placed"));
        commandBus.send(new CancelOrder(id, "cancelled"));

        assertThat(events(id)).containsExactly(new OrderPlaced(id, "initial"),
                                               new OrderPlaced(id, "placed"),
                                               new OrderPlaced(id, "cancelled"));
    }

    // behaviour: java/command_aggregate command.idempotent-replay
    @Test
    void a_repeated_command_applies_no_second_event() {
        var id = create(OrderId.random(), "initial");

        commandBus.send(new PlaceOrder(id, "once"));
        commandBus.send(new PlaceOrder(id, "once"));

        assertThat(events(id)).containsExactly(new OrderPlaced(id, "initial"), new OrderPlaced(id, "once"));
    }

    // behaviour: java/command_aggregate command.http-endpoint
    @Test
    void a_post_with_the_id_in_the_path_sends_the_command_built_from_it_and_the_body() throws Exception {
        var id = create(OrderId.random(), "initial");

        assertThat(post("/api/orders/" + id, "{\"placeholder\":\"posted\"}")).isBetween(200, 299);
        assertThat(post("/api/orders/cancel/" + id, "{\"placeholder\":\"cancelled\"}")).isBetween(200, 299);

        assertThat(events(id)).containsExactly(new OrderPlaced(id, "initial"),
                                               new OrderPlaced(id, "posted"),
                                               new OrderPlaced(id, "cancelled"));
    }

    private OrderId create(OrderId id, String placeholder) {
        inUnitOfWork(() -> orders.saveNew(new Order(id, placeholder)));
        return id;
    }

    private <R> R inUnitOfWork(java.util.function.Supplier<R> action) {
        return eventStore.getUnitOfWorkFactory().withUnitOfWork(action::get);
    }

    private int post(String path, String json) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                                     .header("Content-Type", "application/json")
                                     .POST(HttpRequest.BodyPublishers.ofString(json))
                                     .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }

    private List<PersistedEvent> stream(OrderId id) {
        return inUnitOfWork(() -> eventStore.fetchStream(Orders.AGGREGATE_TYPE, id)
                                            .map(s -> s.eventList())
                                            .orElse(List.of()));
    }

    private List<Object> events(OrderId id) {
        return stream(id).stream().map(e -> (Object) e.event().deserialize()).toList();
    }
}
