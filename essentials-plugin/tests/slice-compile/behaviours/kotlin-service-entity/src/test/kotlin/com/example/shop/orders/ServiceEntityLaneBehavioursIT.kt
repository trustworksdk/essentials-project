package com.example.shop.orders

import com.example.shop.IntegrationTestBase
import com.example.shop.orders.entities.Order
import com.example.shop.orders.entities.OrderRepository
import com.example.shop.orders.events.OrderCancelled
import com.example.shop.orders.events.OrderEvent
import com.example.shop.orders.events.OrderPlaced
import com.example.shop.orders.types.OrderId
import com.example.shop.orders.use_cases.place_order.PlaceOrder
import dk.trustworks.essentials.reactive.EventBus
import dk.trustworks.essentials.reactive.EventHandler
import dk.trustworks.essentials.reactive.command.CommandBus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CI-only behaviour check for the Kotlin service-entity lane (tests/slice-compile/README.md): each test asserts one
 * `implemented` entry of references/slice/templates/behaviours.json against the rendered, untouched skeleton, and the
 * `// behaviour:` line above it names the entry. tests/scripts/test_behaviours_catalog.py fails an `implemented`
 * entry no such line names.
 *
 * Both command slices change an existing Order, so each test first saves one through the seeded write repository —
 * the step a user's own creation slice would take.
 *
 * No view test here: the Kotlin view_service_entity entries are `placeholder`. Reading the closed projection makes
 * Spring Data Mongo build the entity through its constructor from the selected `id` alone, which fails for an entity
 * whose constructor takes a non-null property the view does not expose — the seeded `Order(id, placeholder)` included.
 */
class ServiceEntityLaneBehavioursIT : IntegrationTestBase() {

    @Autowired
    private lateinit var orders: OrderRepository

    @Autowired
    private lateinit var commandBus: CommandBus

    @Autowired
    private lateinit var eventBus: EventBus

    private val published = CopyOnWriteArrayList<Any>()
    private val recorder = EventHandler { published.add(it) }

    @BeforeEach
    fun recordPublishedEvents() {
        eventBus.addSyncSubscriber(recorder)
    }

    @AfterEach
    fun stopRecording() {
        eventBus.removeSyncSubscriber(recorder)
    }

    // behaviour: kotlin/command_service_entity command.emits-event
    @Test
    fun `the handler saves the changed entity and publishes the event`() {
        val id = seeded("initial")

        commandBus.send<Any?, PlaceOrder>(PlaceOrder(OrderId(id), "placed"))

        assertThat(placeholderOf(id)).isEqualTo("placed")
        assertThat(orderEvents()).containsExactly(OrderPlaced(OrderId(id), "placed"))
    }

    // The "same transaction" half of the entry: LocalEventBus.publish rethrows a sync subscriber's exception, so a
    // publish that fails after the save must take the save back with it. Were the save committed on its own, the
    // Order would read "placed" here. The refused event and the root cause's message tie the failure to this
    // subscriber: a command failing before the publish would leave the Order at "initial" too.
    // behaviour: kotlin/command_service_entity command.emits-event
    @Test
    fun `a publish that fails after the save rolls the save back`() {
        val id = seeded("initial")
        val refused = CopyOnWriteArrayList<Any>()
        val failing = EventHandler { event ->
            if (event is OrderPlaced) {
                refused.add(event)
                error("subscriber refuses $event")
            }
        }
        eventBus.addSyncSubscriber(failing)
        try {
            assertThatThrownBy { commandBus.send<Any?, PlaceOrder>(PlaceOrder(OrderId(id), "placed")) }
                .rootCause()
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageStartingWith("subscriber refuses ")
        } finally {
            eventBus.removeSyncSubscriber(failing)
        }

        assertThat(refused).containsExactly(OrderPlaced(OrderId(id), "placed"))
        assertThat(placeholderOf(id)).isEqualTo("initial")
    }

    // behaviour: kotlin/command_service_entity command.idempotent-replay
    @Test
    fun `a repeated command neither saves nor publishes again`() {
        val id = seeded("initial")

        commandBus.send<Any?, PlaceOrder>(PlaceOrder(OrderId(id), "once"))
        commandBus.send<Any?, PlaceOrder>(PlaceOrder(OrderId(id), "once"))

        assertThat(placeholderOf(id)).isEqualTo("once")
        assertThat(orderEvents()).containsExactly(OrderPlaced(OrderId(id), "once"))
    }

    // behaviour: kotlin/command_service_entity command.http-endpoint
    @Test
    fun `a post naming an existing entity changes it and answers with its id`() {
        val id = seeded("initial")

        val placed = post("/api/orders", """{"orderId":"$id","placeholder":"posted"}""")
        assertThat(placed.statusCode()).isBetween(200, 299)
        assertThat(placed.body()).contains("\"orderId\":\"$id\"")

        val cancelled = post("/api/orders/cancel", """{"orderId":"$id","placeholder":"cancelled"}""")
        assertThat(cancelled.statusCode()).isBetween(200, 299)
        assertThat(cancelled.body()).contains("\"orderId\":\"$id\"")

        assertThat(placeholderOf(id)).isEqualTo("cancelled")
        assertThat(orderEvents()).containsExactly(OrderPlaced(OrderId(id), "posted"), OrderCancelled(OrderId(id), "cancelled"))
    }

    private fun seeded(placeholder: String): String {
        val id = OrderId.random().value
        orders.save(Order(id, placeholder))
        return id
    }

    private fun placeholderOf(id: String): String? =
        mongoTemplate.findById(id, Document::class.java, mongoTemplate.getCollectionName(Order::class.java))?.getString("placeholder")

    private fun orderEvents(): List<Any> = published.filterIsInstance<OrderEvent>()

    private fun post(path: String, json: String): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }
}
