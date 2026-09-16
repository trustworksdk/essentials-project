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

import dk.trustworks.essentials.examples.webshop.payment.automations.hold_funds_on_order_placed.OrderAwaitingHoldRepository
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
import dk.trustworks.essentials.examples.webshop.sales.use_cases.place_order.PlaceOrder
import dk.trustworks.essentials.examples.webshop.sales.use_cases.request_checkout.RequestCheckOut
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
import org.springframework.boot.test.context.SpringBootTest
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
@SpringBootTest(classes = [WebshopDemoApplication::class])
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
            // Compared by value, not by representation: the JPA converter for Amount round-trips through a
            // double, so the stored figure comes back as 3999.0 where 3999.00 went in - and BigDecimal.equals
            // is scale-sensitive. The same trap the change_product_price decider guards against.
            assertThat(summary.get().total!!.compareTo(Amount.of("3999.00"))).isZero()
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
        commandBus.send<Any?, ShipOrder>(ShipOrder(orderId, TrackingNumber.of("TRACK-12345")))

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val summary = orderSummaries.findById(orderId.toString())
            assertThat(summary).isPresent
            assertThat(summary.get().shippingStatus).isEqualTo("SHIPPED: TRACK-12345")
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(packagingList.findByReadyToPackTrue()).noneMatch { it.id == orderId.toString() }
        }
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
}
