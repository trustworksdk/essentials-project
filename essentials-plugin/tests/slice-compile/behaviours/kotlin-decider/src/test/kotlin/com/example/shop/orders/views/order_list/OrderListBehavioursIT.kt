package com.example.shop.orders.views.order_list

import com.example.shop.IntegrationTestBase
import com.example.shop.orders.events.OrderPlaced
import com.example.shop.orders.types.OrderId
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * CI-only behaviour check for the Kotlin view template (tests/slice-compile/README.md): each test asserts one
 * `implemented` entry of references/slice/templates/behaviours.json against the rendered, untouched skeleton, and the
 * `// behaviour:` line above it names the entry.
 *
 * Events are appended to the Orders stream directly. Redelivery is driven through the projection's handler, as the
 * Inbox would hand it a message again.
 */
class OrderListBehavioursIT : IntegrationTestBase() {

    @Autowired
    private lateinit var eventStore: EventStore

    @Autowired
    private lateinit var repository: OrderListRepository

    @Autowired
    private lateinit var projection: OrderListProjection

    // behaviour: kotlin/view view.projects-event
    @Test
    fun `the projected event inserts the row then updates it with the event order as version`() {
        val id = OrderId.random()

        append(id, OrderPlaced(id, "first"))
        awaitRow(id, 0)

        append(id, OrderPlaced(id, "second"))
        awaitRow(id, 1)
    }

    // behaviour: kotlin/view view.idempotent-redelivery
    @Test
    fun `an event not newer than the row changes nothing`() {
        val id = OrderId.random()
        val event = OrderPlaced(id, "redelivered")

        projection.on(event, OrderedMessage.of(event, id.value, 5))
        val first = repository.findById(id)!!
        assertThat(first.version.value).isEqualTo(5L)

        projection.on(event, OrderedMessage.of(event, id.value, 5))   // the same event again
        projection.on(event, OrderedMessage.of(event, id.value, 3))   // an older one
        val unchanged = repository.findById(id)!!
        assertThat(unchanged.version.value).isEqualTo(5L)
        assertThat(unchanged.lastUpdated).isEqualTo(first.lastUpdated)

        projection.on(event, OrderedMessage.of(event, id.value, 6))   // a newer one still applies
        assertThat(repository.findById(id)!!.version.value).isEqualTo(6L)
    }

    // behaviour: kotlin/view view.reset-rebuild
    @Test
    fun `a subscription reset deletes every row and the replay rebuilds them`() {
        val id = OrderId.random()
        append(id, OrderPlaced(id, "replayed"))
        awaitRow(id, 0)
        val orphan = OrderId("backed-by-no-event-${id.value}")
        repository.save(OrderListView(orderId = orphan, status = "TODO"))   // only the reset's delete removes this row

        projection.resetAllSubscriptions()

        await().atMost(PROJECTION_WAIT).untilAsserted {
            assertThat(repository.findById(orphan)).isNull()
            assertThat(repository.findById(id)).isNotNull()
        }
    }

    // behaviour: kotlin/view view.query-endpoint
    @Test
    fun `a get on the view path returns the rows`() {
        val id = OrderId.random()
        append(id, OrderPlaced(id, "listed"))
        awaitRow(id, 0)

        HttpClient.newHttpClient().use { client ->
            val response = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/orders")).build(),
                                       HttpResponse.BodyHandlers.ofString())

            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.body()).contains("\"${id.value}\"")
        }
    }

    private fun append(id: OrderId, event: Any) {
        eventStore.unitOfWorkFactory.usingUnitOfWork { _ -> eventStore.appendToStream(ORDERS, id, event) }
    }

    private fun awaitRow(id: OrderId, version: Long) {
        await().atMost(PROJECTION_WAIT).until { repository.findById(id)?.version?.value == version }
    }

    companion object {
        private val ORDERS = AggregateType.of("Orders")
        private val PROJECTION_WAIT = Duration.ofSeconds(30)
    }
}
