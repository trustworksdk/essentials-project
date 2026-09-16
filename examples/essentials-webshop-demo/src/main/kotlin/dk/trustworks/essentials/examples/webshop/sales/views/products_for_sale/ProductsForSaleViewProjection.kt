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

package dk.trustworks.essentials.examples.webshop.sales.views.products_for_sale

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.examples.webshop.sales.config.SalesAggregateTypes
import dk.trustworks.essentials.examples.webshop.sales.events.ProductAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ProductPriceChanged
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Events in, read model out. A view slice never produces events.
 *
 * [ViewEventProcessor] is the asynchronous, replayable, eventually consistent processor - the right one for a
 * catalogue. The framework owns the hard parts: the subscription delivers events **in order** per stream, stores
 * a resume point so a restart does not skip or re-run the whole history, and holds a fenced lock so only one
 * instance of this projection processes at a time.
 *
 * What the framework cannot do for us is idempotence, because only this code knows what applying an event twice
 * would mean to this table. Every handler therefore takes [OrderedMessage] as its second parameter:
 * `message.order` is the event's `EventOrder` inside its stream, and comparing it against the row's stored
 * `version` is what makes redelivery and replay harmless.
 */
@Service
class ProductsForSaleViewProjection(
    dependencies: ViewEventProcessorDependencies,
    private val repository: ProductsForSaleViewRepository
) : ViewEventProcessor(dependencies) {

    companion object {
        private val logger = LoggerFactory.getLogger(ProductsForSaleViewProjection::class.java)
    }

    override fun getProcessorName(): String = "ProductsForSaleViewProjection"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(SalesAggregateTypes.PRODUCTS)

    @MessageHandler
    fun on(e: ProductAdded, message: OrderedMessage) {
        val id = e.id.toString()
        if (repository.existsById(id)) {
            // A replay of the opening event, or a redelivery. Either way the row is already there.
            return
        }
        repository.save(
            ProductForSaleView(
                id = id,
                name = e.name,
                price = e.price,
                version = message.order,
                lastUpdated = OffsetDateTime.now()
            )
        )
    }

    @MessageHandler
    fun on(e: ProductPriceChanged, message: OrderedMessage) {
        val existing = repository.findById(e.id.toString()).orElse(null)
        if (existing == null) {
            // ProductAdded is always the first event of the stream, so this can only mean the row was wiped
            // mid-replay. Skipping keeps the projection from inventing a product with no name.
            logger.warn("No ProductForSaleView for '{}' - skipping", e.id)
            return
        }
        if (existing.version >= message.order) {
            return   // already applied
        }
        existing.price = e.price
        existing.version = message.order
        existing.lastUpdated = OffsetDateTime.now()
        repository.save(existing)
    }

    /**
     * Rebuild support: wipe the read model so a subscription reset replays cleanly. Called once per
     * [AggregateType] this processor subscribes to - there is only one here, so deleting everything is correct.
     * A projection spanning several aggregate types would have to delete only the rows belonging to
     * `aggregateType`.
     */
    override fun onSubscriptionsReset(aggregateType: AggregateType, resubscribeFromAndIncluding: GlobalEventOrder) {
        logger.info("Resetting ProductForSaleView for '{}' from {}", aggregateType, resubscribeFromAndIncluding)
        repository.deleteAll()
    }
}
