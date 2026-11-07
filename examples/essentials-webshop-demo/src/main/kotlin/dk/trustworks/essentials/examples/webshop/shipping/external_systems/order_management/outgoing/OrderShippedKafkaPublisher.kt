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

package dk.trustworks.essentials.examples.webshop.shipping.external_systems.order_management.outgoing

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies
import dk.trustworks.essentials.components.foundation.messaging.MessageDeliveryErrorHandler
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.examples.webshop.shipping.config.ShippingAggregateTypes
import dk.trustworks.essentials.examples.webshop.shipping.config.WebshopShippingProperties
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderShipped
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * The answer to the dual write problem, and the last step of the flow.
 *
 * **The problem.** Shipping an order has to do two things: record the fact, and tell the outside world. Those
 * are two different systems - PostgreSQL and Kafka - and no transaction spans both. Write the database, crash,
 * and the message never leaves. Send the message first, crash, and the outside world believes something that
 * never happened. There is no ordering of the two writes that is safe.
 *
 * **The answer.** The decider writes *only* to the event store: one local transaction, nothing else in it. This
 * processor then reads that committed stream and publishes. The framework gives it a durable, ordered,
 * at-least-once subscription with a stored resume point and an Inbox in front of the handler, so a broker outage
 * or a restart continues where it left off instead of losing a message. The price is honest and stated on the
 * slide: the outside world learns a moment later, and it can learn twice - so consumers must be idempotent,
 * which is why [ExternalOrderShipped] carries the event order.
 *
 * The class is also the anti-corruption boundary: `shipping`'s `OrderId` and `TrackingNumber` become plain
 * strings here, and nowhere else.
 */
@Service
class OrderShippedKafkaPublisher(
    dependencies: EventProcessorDependencies,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val properties: WebshopShippingProperties
) : EventProcessor(dependencies) {

    companion object {
        private val logger = LoggerFactory.getLogger(OrderShippedKafkaPublisher::class.java)
    }

    override fun getProcessorName(): String = "OrderShippedKafkaPublisher"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(ShippingAggregateTypes.SHIPPING_ORDERS)

    override fun getNumberOfParallelInboxMessageConsumers(): Int = 1

    /**
     * Retry design, with the part people forget: some failures must not be retried.
     *
     * A broker that is down is worth twenty attempts with backoff. A message the broker rejects as malformed
     * will be rejected identically every time, so redelivering it twenty times only delays everything behind it.
     */
    override fun getInboxRedeliveryPolicy(): RedeliveryPolicy =
        RedeliveryPolicy.exponentialBackoff()
            .setInitialRedeliveryDelay(Duration.ofMillis(200))
            .setFollowupRedeliveryDelay(Duration.ofMillis(200))
            .setFollowupRedeliveryDelayMultiplier(1.1)
            .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofSeconds(3))
            .setMaximumNumberOfRedeliveries(20)
            .setDeliveryErrorHandler(
                MessageDeliveryErrorHandler.stopRedeliveryOn(IllegalArgumentException::class.java)
            )
            .build()

    @MessageHandler
    fun handle(e: OrderShipped, eventMessage: OrderedMessage) {
        val externalEvent = ExternalOrderShipped(
            orderId = e.id.toString(),
            trackingNumber = e.trackingNumber.toString(),
            eventOrder = eventMessage.order
        )
        logger.info(
            "Publishing {} for order '{}' to topic '{}'",
            ExternalOrderShipped::class.java.simpleName,
            externalEvent.orderId,
            properties.externalEventsTopic
        )
        kafkaTemplate.send(
            ProducerRecord<String, Any>(
                properties.externalEventsTopic,
                externalEvent.orderId,
                externalEvent
            )
        )
    }
}
