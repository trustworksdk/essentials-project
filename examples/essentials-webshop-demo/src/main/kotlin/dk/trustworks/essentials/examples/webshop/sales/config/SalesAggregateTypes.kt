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

package dk.trustworks.essentials.examples.webshop.sales.config

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.examples.webshop.sales.events.OrderEvent
import dk.trustworks.essentials.examples.webshop.sales.events.ProductEvent
import dk.trustworks.essentials.examples.webshop.sales.events.ShoppingBasketEvent
import dk.trustworks.essentials.examples.webshop.sales.routing.OrderCommand
import dk.trustworks.essentials.examples.webshop.sales.routing.ProductCommand
import dk.trustworks.essentials.examples.webshop.sales.routing.ShoppingBasketCommand
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.sales.types.ProductId
import dk.trustworks.essentials.examples.webshop.sales.types.ShoppingBasketId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The `sales` context's event streams, and how the framework should handle each.
 *
 * One [AggregateTypeConfiguration] per aggregate type. Registering it as a bean is all it takes: the
 * application's single `DeciderAndAggregateTypeConfigurator` picks every one of them up, creates the event-stream
 * table, and binds the matching deciders to the command bus.
 *
 * The aggregate type name is the identity of the event stream **and** part of the table name, which the framework
 * builds by string concatenation - so it is a hardcoded constant here and never derived from input.
 */
@Configuration
class SalesAggregateTypes {

    companion object {
        @JvmStatic
        val PRODUCTS: AggregateType = AggregateType.of("Products")

        @JvmStatic
        val SHOPPING_BASKETS: AggregateType = AggregateType.of("ShoppingBaskets")

        @JvmStatic
        val ORDERS: AggregateType = AggregateType.of("Orders")
    }

    @Bean
    fun productsAggregateTypeConfiguration(): AggregateTypeConfiguration =
        AggregateTypeConfiguration(
            aggregateType = PRODUCTS,
            aggregateIdType = ProductId::class.java,
            aggregateIdSerializer = AggregateIdSerializer.serializerFor(ProductId::class.java),
            // Any Decider whose command type implements ProductCommand handles this aggregate type. This is why
            // a command slice needs no registration of its own - `@Service` on the decider is the whole wiring.
            deciderSupportsAggregateTypeChecker =
                DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(ProductCommand::class),
            commandAggregateIdResolver = { cmd -> (cmd as ProductCommand).id },
            eventAggregateIdResolver = { event -> (event as ProductEvent).id }
        )

    @Bean
    fun shoppingBasketsAggregateTypeConfiguration(): AggregateTypeConfiguration =
        AggregateTypeConfiguration(
            aggregateType = SHOPPING_BASKETS,
            aggregateIdType = ShoppingBasketId::class.java,
            aggregateIdSerializer = AggregateIdSerializer.serializerFor(ShoppingBasketId::class.java),
            deciderSupportsAggregateTypeChecker =
                DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(
                    ShoppingBasketCommand::class
                ),
            commandAggregateIdResolver = { cmd -> (cmd as ShoppingBasketCommand).id },
            eventAggregateIdResolver = { event -> (event as ShoppingBasketEvent).id }
        )

    /**
     * The order's own stream, which starts at the first shipping or payment detail rather than at checkout - the
     * basket's `CheckOutRequested` lives in the basket's stream, where it belongs, and every context learns the
     * order exists from there.
     */
    @Bean
    fun ordersAggregateTypeConfiguration(): AggregateTypeConfiguration =
        AggregateTypeConfiguration(
            aggregateType = ORDERS,
            aggregateIdType = OrderId::class.java,
            aggregateIdSerializer = AggregateIdSerializer.serializerFor(OrderId::class.java),
            deciderSupportsAggregateTypeChecker =
                DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(OrderCommand::class),
            commandAggregateIdResolver = { cmd -> (cmd as OrderCommand).id },
            eventAggregateIdResolver = { event -> (event as OrderEvent).id }
        )
}
