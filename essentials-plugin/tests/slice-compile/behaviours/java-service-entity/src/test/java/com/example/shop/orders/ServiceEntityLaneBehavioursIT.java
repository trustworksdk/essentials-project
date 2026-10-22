package com.example.shop.orders;

import com.example.shop.IntegrationTestBase;
import com.example.shop.orders.entities.Order;
import com.example.shop.orders.entities.OrderRepository;
import com.example.shop.orders.events.OrderCancelled;
import com.example.shop.orders.events.OrderEvent;
import com.example.shop.orders.events.OrderPlaced;
import com.example.shop.orders.types.OrderId;
import com.example.shop.orders.use_cases.place_order.PlaceOrder;
import com.example.shop.orders.views.order_list.OrderListQueries;
import com.example.shop.orders.views.order_list.OrderListView;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.EventHandler;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI-only behaviour check for the Java service-entity lane (tests/slice-compile/README.md): each test asserts one
 * `implemented` entry of references/slice/templates/behaviours.json against the rendered, untouched skeleton, and the
 * `// behaviour:` line above it names the entry. tests/scripts/test_behaviours_catalog.py fails an `implemented`
 * entry no such line names.
 * <p>
 * Both command slices change an existing Order, so each test first saves one through the seeded write repository —
 * the step a user's own creation slice would take.
 */
class ServiceEntityLaneBehavioursIT extends IntegrationTestBase {

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OrderListQueries queries;

    @Autowired
    private CommandBus commandBus;

    @Autowired
    private EventBus eventBus;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final EventHandler recorder = published::add;

    @BeforeEach
    void recordPublishedEvents() {
        eventBus.addSyncSubscriber(recorder);
    }

    @AfterEach
    void stopRecording() {
        eventBus.removeSyncSubscriber(recorder);
    }

    // behaviour: java/command_service_entity command.emits-event
    @Test
    void the_handler_saves_the_changed_entity_and_publishes_the_event() {
        var id = seeded("initial");

        commandBus.send(new PlaceOrder(OrderId.of(id), "placed"));

        assertThat(placeholderOf(id)).isEqualTo("placed");
        assertThat(orderEvents()).containsExactly(new OrderPlaced(OrderId.of(id), "placed"));
    }

    // behaviour: java/command_service_entity command.idempotent-replay
    @Test
    void a_repeated_command_neither_saves_nor_publishes_again() {
        var id = seeded("initial");

        commandBus.send(new PlaceOrder(OrderId.of(id), "once"));
        commandBus.send(new PlaceOrder(OrderId.of(id), "once"));

        assertThat(placeholderOf(id)).isEqualTo("once");
        assertThat(orderEvents()).containsExactly(new OrderPlaced(OrderId.of(id), "once"));
    }

    // behaviour: java/command_service_entity command.http-endpoint
    @Test
    void a_post_naming_an_existing_entity_changes_it_and_answers_with_its_id() throws Exception {
        var id = seeded("initial");

        var placed = post("/api/orders", "{\"orderId\":\"" + id + "\",\"placeholder\":\"posted\"}");
        assertThat(placed.statusCode()).isBetween(200, 299);
        assertThat(placed.body()).contains("\"orderId\":\"" + id + "\"");

        var cancelled = post("/api/orders/cancel", "{\"orderId\":\"" + id + "\",\"placeholder\":\"cancelled\"}");
        assertThat(cancelled.statusCode()).isBetween(200, 299);
        assertThat(cancelled.body()).contains("\"orderId\":\"" + id + "\"");

        assertThat(placeholderOf(id)).isEqualTo("cancelled");
        assertThat(orderEvents()).containsExactly(new OrderPlaced(OrderId.of(id), "posted"),
                                                  new OrderCancelled(OrderId.of(id), "cancelled"));
    }

    // behaviour: java/view_service_entity view.query-endpoint
    @Test
    void a_get_on_the_view_path_returns_every_entity_row() throws Exception {
        var first = seeded("listed");
        var second = seeded("listed");

        var response = get("/api/orders");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":\"" + first + "\"", "\"id\":\"" + second + "\"");
    }

    // behaviour: java/view_service_entity view.query-by-id
    @Test
    void a_get_with_an_id_returns_that_row_and_an_unknown_id_is_not_a_404() throws Exception {
        var id = seeded("found");

        var found = get("/api/orders/" + id);
        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).contains("\"id\":\"" + id + "\"");

        // NoSuchElementException, which no handler maps: a server error, not a 404
        assertThat(get("/api/orders/no-such-order-" + id).statusCode()).isEqualTo(500);
    }

    // behaviour: java/view_service_entity view.strongly-consistent-read
    @Test
    void a_committed_write_is_visible_to_the_next_query_without_waiting() {
        var id = seeded("written");

        assertThat(queries.findOrderById(id)).map(OrderListView::getId).contains(id);
        assertThat(queries.findAllBy()).extracting(OrderListView::getId).contains(id);
    }

    private String seeded(String placeholder) {
        var id = OrderId.random().toString();
        orders.save(new Order(id, placeholder));
        return id;
    }

    private String placeholderOf(String id) {
        var document = mongoTemplate.findById(id, Document.class, mongoTemplate.getCollectionName(Order.class));
        assertThat(document).as("Order %s", id).isNotNull();
        return document.getString("placeholder");
    }

    private List<Object> orderEvents() {
        return published.stream().filter(OrderEvent.class::isInstance).toList();
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                                     .header("Content-Type", "application/json")
                                     .POST(HttpRequest.BodyPublishers.ofString(json))
                                     .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
