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

package dk.trustworks.essentials.examples.webshop.payment.config

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer
import dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration
import dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldEvent
import dk.trustworks.essentials.examples.webshop.payment.routing.CreditCardHoldCommand
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The `payment` context's one event stream, keyed by the order it authorizes. */
@Configuration
class PaymentAggregateTypes {

    companion object {
        @JvmStatic
        val CREDIT_CARD_HOLDS: AggregateType = AggregateType.of("CreditCardHolds")
    }

    @Bean
    fun creditCardHoldsAggregateTypeConfiguration(): AggregateTypeConfiguration =
        AggregateTypeConfiguration(
            aggregateType = CREDIT_CARD_HOLDS,
            aggregateIdType = OrderId::class.java,
            aggregateIdSerializer = AggregateIdSerializer.serializerFor(OrderId::class.java),
            deciderSupportsAggregateTypeChecker =
                DeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritsFromCommandType(
                    CreditCardHoldCommand::class
                ),
            commandAggregateIdResolver = { cmd -> (cmd as CreditCardHoldCommand).id },
            eventAggregateIdResolver = { event -> (event as CreditCardHoldEvent).id }
        )
}
