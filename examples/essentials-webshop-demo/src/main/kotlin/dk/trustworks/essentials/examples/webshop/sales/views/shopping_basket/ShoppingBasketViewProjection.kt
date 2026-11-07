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

package dk.trustworks.essentials.examples.webshop.sales.views.shopping_basket

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.examples.webshop.sales.config.SalesAggregateTypes
import dk.trustworks.essentials.examples.webshop.sales.events.CheckOutRequested
import dk.trustworks.essentials.examples.webshop.sales.events.ItemAddedToShoppingBasket
import dk.trustworks.essentials.examples.webshop.sales.events.ItemRemovedFromShoppingBasket
import org.springframework.stereotype.Service

/**
 * The basket page's read model.
 *
 * This projection is the clearest case for the `lastEventOrder` guard: `quantity + 1` applied twice is wrong,
 * unlike "set the price to X" applied twice. The framework guarantees order and at-least-once delivery, and
 * at-least-once is precisely why this code has to be able to recognise an event it has already seen.
 *
 * The basket's lines are deleted when checkout happens - from that point the order summary view is the thing
 * that answers questions about it, and leaving both alive would let the shop page show a basket the customer has
 * already paid for.
 */
@Service
class ShoppingBasketViewProjection(
    dependencies: ViewEventProcessorDependencies,
    private val repository: ShoppingBasketLineViewRepository
) : ViewEventProcessor(dependencies) {

    override fun getProcessorName(): String = "ShoppingBasketViewProjection"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(SalesAggregateTypes.SHOPPING_BASKETS)

    @MessageHandler
    fun on(e: ItemAddedToShoppingBasket, message: OrderedMessage) {
        val basketId = e.id.toString()
        val productId = e.product.toString()
        val lineId = ShoppingBasketLineView.lineId(basketId, productId)
        val existing = repository.findById(lineId).orElse(null)

        if (existing == null) {
            repository.save(
                ShoppingBasketLineView(
                    id = lineId,
                    basketId = basketId,
                    productId = productId,
                    quantity = 1,
                    linePrice = e.price,
                    lastEventOrder = message.order
                )
            )
            return
        }
        if (existing.lastEventOrder >= message.order) {
            return   // already applied
        }
        existing.quantity += 1
        existing.linePrice = existing.linePrice.add(e.price)
        existing.lastEventOrder = message.order
        repository.save(existing)
    }

    @MessageHandler
    fun on(e: ItemRemovedFromShoppingBasket, message: OrderedMessage) {
        val lineId = ShoppingBasketLineView.lineId(e.id.toString(), e.product.toString())
        val existing = repository.findById(lineId).orElse(null) ?: return
        if (existing.lastEventOrder >= message.order) {
            return   // already applied
        }
        if (existing.quantity <= 1) {
            repository.delete(existing)
            return
        }
        // The event carries the price of the unit that was removed, so there is nothing to derive here.
        existing.quantity -= 1
        existing.linePrice = existing.linePrice.subtract(e.price)
        existing.lastEventOrder = message.order
        repository.save(existing)
    }

    @MessageHandler
    fun on(e: CheckOutRequested, message: OrderedMessage) {
        repository.deleteByBasketId(e.id.toString())
    }

    override fun onSubscriptionsReset(aggregateType: AggregateType, resubscribeFromAndIncluding: GlobalEventOrder) {
        repository.deleteAll()
    }
}
