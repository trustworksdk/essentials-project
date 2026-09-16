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

package dk.trustworks.essentials.examples.webshop.shipping.config

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.shipping.events.ShippingOrderEvent
import dk.trustworks.essentials.examples.webshop.shipping.routing.ShippingOrderCommand
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The `shipping` context's one event stream.
 *
 * Its aggregate id is `sales`' [OrderId] - the same id the `Orders` and `CreditCardHolds` streams use. Each
 * context appends only to its own stream, so the shared id buys correlation without buying contention.
 */
@Configuration
class ShippingAggregateTypes {

    companion object {
        @JvmStatic
        val SHIPPING_ORDERS: AggregateType = AggregateType.of("ShippingOrders")
    }

    @Bean
    fun shippingOrdersAggregateTypeConfiguration(): AggregateTypeConfiguration =
        AggregateTypeConfiguration(
            aggregateType = SHIPPING_ORDERS,
            aggregateIdType = OrderId::class.java,
            aggregateIdSerializer = AggregateIdSerializer.serializerFor(OrderId::class.java),
            deciderSupportsAggregateTypeChecker =
                DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(
                    ShippingOrderCommand::class
                ),
            commandAggregateIdResolver = { cmd -> (cmd as ShippingOrderCommand).id },
            eventAggregateIdResolver = { event -> (event as ShippingOrderEvent).id }
        )
}
