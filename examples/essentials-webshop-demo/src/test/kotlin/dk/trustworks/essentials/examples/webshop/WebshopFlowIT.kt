/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.webshop

import dk.trustworks.essentials.examples.webshop.payment.automations.capture_funds_when_packaged.OrderAwaitingCaptureRepository
import dk.trustworks.essentials.examples.webshop.payment.automations.hold_funds_on_order_placed.OrderAwaitingHoldRepository
import dk.trustworks.essentials.examples.webshop.payment.views.captures_awaiting_outcome.CaptureAwaitingOutcomeViewRepository
import dk.trustworks.essentials.examples.webshop.sales.types.Address
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.PaymentMethod
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.examples.webshop.sales.types.ShippingMethod
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import dk.trustworks.essentials.examples.webshop.sales.use_cases.add_item_to_shopping_basket.AddItemToShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.use_cases.add_payment_details_to_order.AddPaymentDetailsToOrder
import dk.trustworks.essentials.examples.webshop.sales.use_cases.add_product.AddProduct
import dk.trustworks.essentials.examples.webshop.sales.use_cases.add_shipping_details_to_order.AddShippingDetailsToOrder
import dk.trustworks.essentials.examples.webshop.sales.use_cases.cancel_order.CancelOrder
import dk.trustworks.essentials.examples.webshop.sales.use_cases.place_order.PlaceOrder
import dk.trustworks.essentials.examples.webshop.sales.use_cases.request_checkout.RequestCheckOut
import dk.trustworks.essentials.examples.webshop.sales.views.order_summary.OrderSummaryAPI
import dk.trustworks.essentials.examples.webshop.sales.views.order_summary.OrderSummaryViewRepository
import dk.trustworks.essentials.examples.webshop.sales.views.products_for_sale.ProductsForSaleViewRepository
import dk.trustworks.essentials.examples.webshop.shipping.types.TrackingNumber
import dk.trustworks.essentials.examples.webshop.shipping.use_cases.package_order.PackageOrder
import dk.trustworks.essentials.examples.webshop.shipping.use_cases.ship_order.ShipOrder
import dk.trustworks.essentials.examples.webshop.shipping.views.orders_ready_for_packaging.OrderReadyForPackagingViewRepository
import dk.trustworks.essentials.reactive.command.CommandBus
import dk.trustworks.essentials.types.Amount
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

/**
 * The whole flow, through the command bus and back out of the read models: catalogue, basket, checkout, order
 * details, placement, the payment hold the `payment` context decides on by itself, the packaging list `shipping`
 * projects from `sales`' events, and dispatch.
 *
 * Every assertion about a read model is wrapped in [await] rather than asserted straight after the command,
 * because every projection here runs on its own subscription. A test that asserts a projection synchronously is
 * asserting a race, and it will pass on a fast machine until it does not.
 *
 * Kafka is deliberately *not* asserted here - the publisher is exercised in the flow, and what leaves the topic
 * is the sibling examples' territory. Keeping one broker-free integration test makes this suite runnable with
 * one container.
 */
/*
 * A real servlet container, not a mock one: the capture outcome arrives over HTTP at this application's own
 * webhook endpoint, so the simulated gateway needs a port to call back on. `local.server.port` is what it
 * resolves the callback URL from.
 */
@SpringBootTest(
    classes = [WebshopDemoApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class WebshopFlowIT {

    companion object {
        /** Pinned, in step with `EssentialsTestContainers.POSTGRES_IMAGE` - a floating tag changes the database major underneath the suite. */
        private const val POSTGRES_IMAGE = "postgres:18.4"

        /**
         * One container for the whole class, as `.claude/rules/testing.md` requires - but started here
         * explicitly rather than through `@Testcontainers` + `@Container`.
         *
         * That idiom does not survive a Kotlin `companion object` on Testcontainers 2.0.5: with `@Container`
         * and with `@field:Container` alike, the container is stopped once the first test method finishes, and
         * every later test in the class then talks to a database that no longer exists. It surfaces as
         * `FATAL: terminating connection due to unexpected postmaster exit`, which points at nothing. Both
         * forms were tried here before settling on this one; a Java IT in this repository keeps the
         * annotations, where they work as documented.
         *
         * Ryuk still removes the container when the JVM exits, so nothing leaks.
         */
        val postgres: PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("webshop")
            .withUsername("test")
            .withPassword("test")
            .also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            // The same subscription polling the application runs with. Without it the defaults apply, the
            // projections lag by seconds, and the payment automation spends its redeliveries waiting for a
            // to-do view that has not caught up - which is a slow test, not a broken system, but it makes the
            // suite time-dependent.
            registry.add("essentials.event-store.subscription-manager.event-store-polling-interval") { "200" }
            registry.add("essentials.event-store.subscription-manager.event-store-polling-batch-size") { "5" }
            registry.add("essentials.durable-queues.max-polling-interval") { "200ms" }
            registry.add("essentials.durable-queues.polling-delay-interval-increment-factor") { "0.2" }
            // No broker in this test: the publisher's send fails and the Inbox retries it, which must not make
            // the flow itself fail. That is the point of publishing from a subscription rather than inline.
            registry.add("spring.kafka.bootstrap-servers") { "localhost:1" }
            // The reconciler's real defaults are tens of seconds, which is right for a demo and far too slow
            // for a test. The behaviour under test is what it does when it runs, not how long it waits first.
            registry.add("webshop-demo.payment.reconcile-captures-after") { "1s" }
            registry.add("webshop-demo.payment.reconcile-interval") { "500ms" }
        }
    }

    @Autowired
    private lateinit var commandBus: CommandBus

    @Autowired
    private lateinit var productsForSale: ProductsForSaleViewRepository

    @Autowired
    private lateinit var orderSummaries: OrderSummaryViewRepository

    @Autowired
    private lateinit var packagingList: OrderReadyForPackagingViewRepository

    @Autowired
    private lateinit var awaitingHold: OrderAwaitingHoldRepository

    @Autowired
    private lateinit var pendingCaptures: CaptureAwaitingOutcomeViewRepository

    @Autowired
    private lateinit var awaitingCapture: OrderAwaitingCaptureRepository

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `a product is bought, paid for and shipped`() {
        val productId = ProductId.random()
        val basketId = ShoppingBasketId.random()
        val orderId = OrderId.random()
        val price = Amount.of("1999.50")
        val address = Address("Vestergade 1", "8000", "Aarhus", "DK")

        commandBus.send<Any?, AddProduct>(AddProduct(productId, "Espresso machine", price))

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(productsForSale.findById(productId.toString())).isPresent
        }

        commandBus.send<Any?, AddItemToShoppingBasket>(AddItemToShoppingBasket(basketId, productId, price))
        commandBus.send<Any?, AddItemToShoppingBasket>(AddItemToShoppingBasket(basketId, productId, price))
        commandBus.send<Any?, RequestCheckOut>(RequestCheckOut(basketId, orderId))

        // The total is the basket's own fold over its own events: two units at 1999.50.
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            // Exact equality on purpose, scale included: this is the regression test for
            // MoneyAttributeConverter. The framework's AmountAttributeConverter stores an Amount as a
            // floating-point double, and under it this assertion failed with "but was: 3999.0". A numeric
            // column round-trips the scale, so money read back from a view equals money put in.
            assertThat(summary.get().total).isEqualTo(Amount.of("3999.00"))
        }

        commandBus.send<Any?, AddShippingDetailsToOrder>(
            AddShippingDetailsToOrder(orderId, address, ShippingMethod.STANDARD)
        )
        commandBus.send<Any?, AddPaymentDetailsToOrder>(
            AddPaymentDetailsToOrder(orderId, address, PaymentMethod.CREDIT_CARD)
        )
        commandBus.send<Any?, PlaceOrder>(PlaceOrder(orderId))

        // Nobody told `payment` to do this: it subscribed to OrderPlaced, read its own to-do view for the
        // total, called the gateway and sent its own command.
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            // Everything inside untilAsserted has to fail as an *assertion*: Awaitility retries AssertionErrors
            // and aborts on anything else, so an `orElseThrow()` here would end the wait on the first miss
            // rather than poll until the projection catches up.
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().placed).isTrue()
            assertThat(summary.get().paymentStatus).isEqualTo("HELD")
        }
        // The automation's own work item is closed once the outcome is recorded, so nothing is left waiting.
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val workItem = awaitingHold.findById(orderId.toString())
            assertThat(workItem).isPresent
            assertThat(workItem.get().outcome).isEqualTo("HELD")
        }

        // ... and nobody told `shipping` either: the order is on its packaging list because it projected
        // `sales`' events into one.
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(packagingList.findByReadyToPackTrue()).anyMatch { it.id == orderId.toString() }
        }

        commandBus.send<Any?, PackageOrder>(PackageOrder(orderId))

        // The work item survives its first step. It used to be deleted here, which took the dispatch action off
        // the warehouse screen with it and left `ShipOrder` reachable only from a test like this one.
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val workItem = packagingList.findById(orderId.toString())
            assertThat(workItem).isPresent
            assertThat(workItem.get().packaged).isTrue()
            assertThat(workItem.get().paymentDeclineReason).isNull()
        }

        // Packing is what charges the card: the automation records the request, calls the gateway, and the
        // gateway answers later on the webhook - twice, as real ones do. Nothing here tells it to; `payment`
        // subscribed to `shipping`'s packaging event and decided for itself.
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().paymentStatus).isEqualTo("CAPTURED")
        }
        // Settled, so the parcel may leave - and the unknown-outcome list is empty again.
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val workItem = packagingList.findById(orderId.toString())
            assertThat(workItem).isPresent
            assertThat(workItem.get().paymentSettled).isTrue()
            assertThat(pendingCaptures.findById(orderId.toString())).isEmpty()
        }

        commandBus.send<Any?, ShipOrder>(ShipOrder(orderId, TrackingNumber.of("TRACK-12345")))

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().shippingStatus).isEqualTo("SHIPPED: TRACK-12345")
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(packagingList.findByReadyToPackTrue()).noneMatch { it.id == orderId.toString() }
        }

        // The order-history panel asks the same read model without an id, so a shipped order is still listed -
        // it left the warehouse's work list, not the business's record of what happened. It is also the most
        // recently touched order, so it is on the first page.
        mockMvc.perform(get("/api/orders"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orders[?(@.orderId=='" + orderId + "')].shippingStatus").value("SHIPPED: TRACK-12345"))

        // Paging is done in SQL, so a page carries only its own rows while still counting the whole set.
        mockMvc.perform(get("/api/orders").param("page", "0").param("size", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orders.length()").value(1))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.orders[0].orderId").value(orderId.toString()))

        // A size nobody should be able to ask for comes back clamped rather than honoured.
        mockMvc.perform(get("/api/orders").param("size", "100000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(OrderSummaryAPI.MAX_PAGE_SIZE))
    }

    @Test
    fun `an order above the authorization limit is rejected, and the rejection is recorded`() {
        val productId = ProductId.random()
        val basketId = ShoppingBasketId.random()
        val orderId = OrderId.random()
        val price = Amount.of("25000.00")
        val address = Address("Vestergade 1", "8000", "Aarhus", "DK")

        commandBus.send<Any?, AddProduct>(AddProduct(productId, "Commercial roaster", price))
        commandBus.send<Any?, AddItemToShoppingBasket>(AddItemToShoppingBasket(basketId, productId, price))
        commandBus.send<Any?, RequestCheckOut>(RequestCheckOut(basketId, orderId))
        commandBus.send<Any?, AddShippingDetailsToOrder>(
            AddShippingDetailsToOrder(orderId, address, ShippingMethod.STANDARD)
        )
        commandBus.send<Any?, AddPaymentDetailsToOrder>(
            AddPaymentDetailsToOrder(orderId, address, PaymentMethod.CREDIT_CARD)
        )
        commandBus.send<Any?, PlaceOrder>(PlaceOrder(orderId))

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().paymentStatus).isEqualTo("REJECTED")
            // The gateway's own words reach the screen, so "REJECTED" explains itself.
            assertThat(summary.get().paymentDeclineReason).contains("exceeds the authorization limit")
        }

        // `shipping` never asked `payment` anything, but its work list knows not to pack this one: the decline
        // is projected onto the row, and the row is what the warehouse screen guards on. `PackageOrderDecider`
        // could not make this call - it sees the `ShippingOrders` stream and nothing else.
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val workItem = packagingList.findById(orderId.toString())
            assertThat(workItem).isPresent
            assertThat(workItem.get().readyToPack).isTrue()
            assertThat(workItem.get().paymentDeclineReason).isNotNull()
        }

        commandBus.send<Any?, CancelOrder>(CancelOrder(orderId, "Payment was declined by the card issuer"))

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().cancelled).isTrue()
            assertThat(summary.get().cancellationReason).isEqualTo("Payment was declined by the card issuer")
        }
        // `sales` cancelled it; `shipping` decided on its own what that meant for its work list.
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(packagingList.findById(orderId.toString())).isEmpty()
        }
    }

    @Test
    fun `an invoice order needs no hold and still reaches the warehouse`() {
        val productId = ProductId.random()
        val basketId = ShoppingBasketId.random()
        val orderId = OrderId.random()
        val price = Amount.of("125.95")
        val address = Address("Vestergade 1", "8000", "Aarhus", "DK")

        commandBus.send<Any?, AddProduct>(AddProduct(productId, "Coffee beans", price))
        commandBus.send<Any?, AddItemToShoppingBasket>(AddItemToShoppingBasket(basketId, productId, price))
        commandBus.send<Any?, RequestCheckOut>(RequestCheckOut(basketId, orderId))
        commandBus.send<Any?, AddShippingDetailsToOrder>(
            AddShippingDetailsToOrder(orderId, address, ShippingMethod.PICKUP_POINT)
        )
        commandBus.send<Any?, AddPaymentDetailsToOrder>(
            AddPaymentDetailsToOrder(orderId, address, PaymentMethod.INVOICE)
        )
        commandBus.send<Any?, PlaceOrder>(PlaceOrder(orderId))

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(packagingList.findByReadyToPackTrue()).anyMatch { it.id == orderId.toString() }
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().paymentStatus).isEqualTo("NOT_REQUIRED")
        }
    }

    /**
     * A refused command has to reach the caller as a refusal, not as a 500.
     *
     * This is the half the shop page depends on: it prints the reason it gets back, and when the reason was an
     * anonymous 500 the page could only guess - which is how "add to basket" on a checked-out basket came to be
     * logged as an event that never happened.
     */
    @Test
    fun `a refused command answers 409 with the reason, not 500`() {
        val productId = ProductId.random()
        val basketId = ShoppingBasketId.random()
        val orderId = OrderId.random()
        val price = Amount.of("125.95")

        commandBus.send<Any?, AddProduct>(AddProduct(productId, "Coffee beans", price))
        commandBus.send<Any?, AddItemToShoppingBasket>(AddItemToShoppingBasket(basketId, productId, price))
        commandBus.send<Any?, RequestCheckOut>(RequestCheckOut(basketId, orderId))

        mockMvc.perform(
            post("/api/shopping-baskets/{basketId}/items", basketId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"product":"$productId","price":"125.95"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("ShoppingBasketAlreadyCheckedOutException"))
            .andExpect(jsonPath("$.message").value("Shopping basket '$basketId' has already been checked out"))
    }

    /**
     * The case that justifies capturing at dispatch rather than at checkout: the bank authorizes the money and
     * then refuses to settle it, *after* the warehouse has packed the parcel.
     *
     * Nothing compensates, because nothing irreversible happened. The order stays packed, the work item goes
     * back to blocked, and the parcel does not leave - which is only possible because the capture was left
     * until the last step that could still be stopped.
     */
    @Test
    fun `a capture that fails after packing blocks dispatch instead of unwinding a shipment`() {
        val productId = ProductId.random()
        val basketId = ShoppingBasketId.random()
        val orderId = OrderId.random()
        // Authorized (under the 10000 hold limit) but not settleable (over the 5000 capture limit).
        val price = Amount.of("6000.00")
        val address = Address("Vestergade 1", "8000", "Aarhus", "DK")

        placeCardOrder(productId, "Grinder", price, basketId, orderId, address)

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().paymentStatus).isEqualTo("HELD")
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(packagingList.findByReadyToPackTrue()).anyMatch { it.id == orderId.toString() }
        }

        commandBus.send<Any?, PackageOrder>(PackageOrder(orderId))

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().paymentStatus).isEqualTo("CAPTURE_FAILED")
            assertThat(summary.get().paymentDeclineReason).contains("refused to settle")
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val workItem = packagingList.findById(orderId.toString())
            assertThat(workItem).isPresent
            assertThat(workItem.get().packaged).isTrue()
            assertThat(workItem.get().paymentSettled).isFalse()
            assertThat(workItem.get().captureFailureReason).isNotNull()
        }
        // `ShipOrder` itself still only knows about its own stream - the guard is the work item's state, which
        // is what the warehouse screen renders. The decider would happily ship an unpaid parcel if asked.
        assertThat(packagingList.findById(orderId.toString()).get().paymentSettled).isFalse()
    }

    /**
     * The webhook that never arrives.
     *
     * The gateway settles the charge and drops the callback, so nothing will ever tell this application what
     * happened - no retry, no redelivery, no dead letter, because no message was ever sent. The only thing that
     * resolves it is the reconciler noticing a request with no answer and *asking*. A timeout is not a failure;
     * it is an unknown, and an unknown has to be chased.
     */
    @Test
    fun `a lost capture callback is resolved by asking the gateway instead of assuming`() {
        val productId = ProductId.random()
        val basketId = ShoppingBasketId.random()
        val orderId = OrderId.random()
        // Cents of 13: this gateway settles the charge and deliberately never calls back. See WebshopPaymentProperties.
        val price = Amount.of("1234.13")
        val address = Address("Vestergade 1", "8000", "Aarhus", "DK")

        placeCardOrder(productId, "Kettle", price, basketId, orderId, address)

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(packagingList.findByReadyToPackTrue()).anyMatch { it.id == orderId.toString() }
        }
        commandBus.send<Any?, PackageOrder>(PackageOrder(orderId))

        // First it becomes an unknown: we asked, and there is a row saying so.
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(pendingCaptures.findById(orderId.toString())).isPresent
        }

        // Then the reconciler asks the gateway directly, and the money turns out to have moved after all.
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().paymentStatus).isEqualTo("CAPTURED")
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(pendingCaptures.findById(orderId.toString())).isEmpty()
            assertThat(awaitingCapture.findById(orderId.toString()).get().outcome).isEqualTo("CAPTURED")
        }
    }

    /** The steps every card order shares, up to and including placement. */
    private fun placeCardOrder(
        productId: ProductId,
        productName: String,
        price: Amount,
        basketId: ShoppingBasketId,
        orderId: OrderId,
        address: Address
    ) {
        commandBus.send<Any?, AddProduct>(AddProduct(productId, productName, price))
        commandBus.send<Any?, AddItemToShoppingBasket>(AddItemToShoppingBasket(basketId, productId, price))
        commandBus.send<Any?, RequestCheckOut>(RequestCheckOut(basketId, orderId))
        commandBus.send<Any?, AddShippingDetailsToOrder>(
            AddShippingDetailsToOrder(orderId, address, ShippingMethod.STANDARD)
        )
        commandBus.send<Any?, AddPaymentDetailsToOrder>(
            AddPaymentDetailsToOrder(orderId, address, PaymentMethod.CREDIT_CARD)
        )
        commandBus.send<Any?, PlaceOrder>(PlaceOrder(orderId))
    }
}
