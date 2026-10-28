package com.example.shop.orders

import com.example.shop.IntegrationTestBase
import com.example.shop.orders.config.OrdersConfiguration
import com.example.shop.orders.events.OrderCancelled
import com.example.shop.orders.events.OrderPlaced
import com.example.shop.orders.types.OrderId
import com.example.shop.orders.use_cases.cancel_order.CancelOrder
import com.example.shop.orders.use_cases.place_order.PlaceOrder
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent
import dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator
import dk.trustworks.essentials.reactive.command.CommandBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * CI-only behaviour check for the Kotlin decider lane (tests/slice-compile/README.md): each test asserts one
 * `implemented` entry of references/slice/templates/behaviours.json against the rendered, untouched skeleton, and the
 * `// behaviour:` line above it names the entry. tests/scripts/test_behaviours_catalog.py fails an `implemented`
 * entry no such line names.
 */
class DeciderLaneBehavioursIT : IntegrationTestBase() {

    @Autowired
    private lateinit var commandBus: CommandBus

    @Autowired
    private lateinit var eventStore: EventStore

    @Autowired
    private lateinit var context: ApplicationContext

    // behaviour: kotlin/app-wiring app.decider-dispatch
    @Test
    fun `the one configurator hands each command to its decider over the loaded stream`() {
        assertThat(context.getBeansOfType(DeciderAndAggregateTypeConfigurator::class.java)).hasSize(1)
        val id = OrderId.random()

        send(PlaceOrder(id, "dispatched"))
        send(CancelOrder(id, "dispatched"))
        send(CancelOrder(id, "dispatched"))   // the loaded stream already holds OrderCancelled

        assertThat(events(id)).containsExactly(OrderPlaced(id, "dispatched"), OrderCancelled(id, "dispatched"))
    }

    // behaviour: kotlin/bc-scaffold bc.command-routing
    @Test
    fun `a command lands in the orders stream its id names`() {
        val id = OrderId.random()

        send(PlaceOrder(id, "routed"))
        send(PlaceOrder(OrderId.random(), "routed"))

        val persisted = stream(id)
        assertThat(persisted).hasSize(1)
        assertThat(persisted[0].aggregateType() as Any).isEqualTo(OrdersConfiguration.AGGREGATE_TYPE)
        assertThat(persisted[0].aggregateId() as Any).isEqualTo(id)
    }

    // behaviour: kotlin/bc-scaffold bc.event-json-type
    @Test
    fun `every event variant is written with its type and reads back equal`() {
        val id = OrderId.random()

        send(PlaceOrder(id, "json"))
        send(CancelOrder(id, "json"))

        val persisted = stream(id)
        assertThat(persisted).hasSize(2)
        assertThat(persisted[0].event().json).containsPattern("\"@type\"\\s*:\\s*\"OrderPlaced\"")
        assertThat(persisted[1].event().json).containsPattern("\"@type\"\\s*:\\s*\"OrderCancelled\"")
        assertThat(events(id)).containsExactly(OrderPlaced(id, "json"), OrderCancelled(id, "json"))
    }

    // behaviour: kotlin/command command.emits-event
    @Test
    fun `the decider answers the command with the event carrying its id and payload`() {
        val id = OrderId.random()

        val answer = send(PlaceOrder(id, "emitted"))

        assertThat(answer).isEqualTo(OrderPlaced(id, "emitted"))
        assertThat(events(id)).containsExactly(OrderPlaced(id, "emitted"))
    }

    // behaviour: kotlin/command command.idempotent-replay
    @Test
    fun `a repeated command appends nothing`() {
        val id = OrderId.random()

        send(PlaceOrder(id, "once"))
        send(PlaceOrder(id, "once"))

        assertThat(events(id)).containsExactly(OrderPlaced(id, "once"))
    }

    // behaviour: kotlin/command command.http-endpoint
    @Test
    fun `a post of the command is accepted and reaches the decider`() {
        val id = OrderId.random()

        assertThat(post("/api/orders", """{"id":"${id.value}","placeholder":"posted"}""")).isEqualTo(202)
        assertThat(post("/api/orders/cancel", """{"id":"${id.value}","placeholder":"posted"}""")).isEqualTo(202)

        assertThat(events(id)).containsExactly(OrderPlaced(id, "posted"), OrderCancelled(id, "posted"))
    }

    private fun <C : Any> send(command: C): Any? = commandBus.send<Any?, C>(command)

    private fun post(path: String, json: String): Int =
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
            client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
        }

    private fun stream(id: OrderId): List<PersistedEvent> =
        eventStore.unitOfWorkFactory.withUnitOfWork<List<PersistedEvent>> { _ ->
            eventStore.fetchStream(OrdersConfiguration.AGGREGATE_TYPE, id).map { it.eventList() }.orElse(emptyList())
        }

    private fun events(id: OrderId): List<Any> = stream(id).map { it.event().deserialize<Any>() }
}
