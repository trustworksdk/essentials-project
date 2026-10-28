package com.example.shop.orders;

import com.example.shop.IntegrationTestBase;
import com.example.shop.orders.config.OrdersConfiguration;
import com.example.shop.orders.events.OrderCancelled;
import com.example.shop.orders.events.OrderPlaced;
import com.example.shop.orders.types.OrderId;
import com.example.shop.orders.use_cases.cancel_order.CancelOrder;
import com.example.shop.orders.use_cases.place_order.PlaceOrder;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfigurator;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI-only behaviour check for the Java decider lane (tests/slice-compile/README.md): each test asserts one
 * `implemented` entry of references/slice/templates/behaviours.json against the rendered, untouched skeleton, and the
 * `// behaviour:` line above it names the entry. tests/scripts/test_behaviours_catalog.py fails an `implemented`
 * entry no such line names.
 */
class DeciderLaneBehavioursIT extends IntegrationTestBase {

    @Autowired
    private CommandBus commandBus;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private ApplicationContext context;

    // behaviour: java/app-wiring app.decider-dispatch
    @Test
    void the_one_configurator_hands_each_command_to_its_decider_over_the_loaded_stream() {
        assertThat(context.getBeansOfType(EventStreamDeciderAndAggregateTypeConfigurator.class)).hasSize(1);
        var id = OrderId.random();

        commandBus.send(new PlaceOrder(id, "dispatched"));
        commandBus.send(new CancelOrder(id, "dispatched"));
        commandBus.send(new CancelOrder(id, "dispatched"));   // the loaded stream already holds OrderCancelled

        assertThat(events(id)).containsExactly(new OrderPlaced(id, "dispatched"), new OrderCancelled(id, "dispatched"));
    }

    // behaviour: java/bc-scaffold bc.command-routing
    @Test
    void a_command_lands_in_the_orders_stream_its_id_names() {
        var id = OrderId.random();
        var other = OrderId.random();

        commandBus.send(new PlaceOrder(id, "routed"));
        commandBus.send(new PlaceOrder(other, "routed"));

        assertThat(stream(id)).singleElement().satisfies(event -> {
            assertThat((Object) event.aggregateType()).isEqualTo(OrdersConfiguration.AGGREGATE_TYPE);
            assertThat((Object) event.aggregateId()).isEqualTo(id);
        });
    }

    // behaviour: java/bc-scaffold bc.event-json-type
    @Test
    void every_event_variant_is_written_with_its_type_and_reads_back_equal() {
        var id = OrderId.random();

        commandBus.send(new PlaceOrder(id, "json"));
        commandBus.send(new CancelOrder(id, "json"));

        var persisted = stream(id);
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).event().getJson()).containsPattern("\"@type\"\\s*:\\s*\"OrderPlaced\"");
        assertThat(persisted.get(1).event().getJson()).containsPattern("\"@type\"\\s*:\\s*\"OrderCancelled\"");
        assertThat(events(id)).containsExactly(new OrderPlaced(id, "json"), new OrderCancelled(id, "json"));
    }

    // behaviour: java/command command.emits-event
    @Test
    void the_decider_answers_the_command_with_the_event_carrying_its_id_and_payload() {
        var id = OrderId.random();

        Object answer = commandBus.send(new PlaceOrder(id, "emitted"));

        assertThat(answer).isEqualTo(new OrderPlaced(id, "emitted"));
        assertThat(events(id)).containsExactly(new OrderPlaced(id, "emitted"));
    }

    // behaviour: java/command command.idempotent-replay
    @Test
    void a_repeated_command_appends_nothing() {
        var id = OrderId.random();

        commandBus.send(new PlaceOrder(id, "once"));
        commandBus.send(new PlaceOrder(id, "once"));

        assertThat(events(id)).containsExactly(new OrderPlaced(id, "once"));
    }

    // behaviour: java/command command.http-endpoint
    @Test
    void a_post_of_the_command_is_accepted_and_reaches_the_decider() throws Exception {
        var id = OrderId.random();

        assertThat(post("/api/orders", "{\"id\":\"" + id + "\",\"placeholder\":\"posted\"}")).isEqualTo(202);
        assertThat(post("/api/orders/cancel", "{\"id\":\"" + id + "\",\"placeholder\":\"posted\"}")).isEqualTo(202);

        assertThat(events(id)).containsExactly(new OrderPlaced(id, "posted"), new OrderCancelled(id, "posted"));
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
        return eventStore.getUnitOfWorkFactory().withUnitOfWork(
                () -> eventStore.fetchStream(OrdersConfiguration.AGGREGATE_TYPE, id)
                                .map(s -> s.eventList())
                                .orElse(List.of()));
    }

    private List<Object> events(OrderId id) {
        return stream(id).stream().map(e -> (Object) e.event().deserialize()).toList();
    }
}
