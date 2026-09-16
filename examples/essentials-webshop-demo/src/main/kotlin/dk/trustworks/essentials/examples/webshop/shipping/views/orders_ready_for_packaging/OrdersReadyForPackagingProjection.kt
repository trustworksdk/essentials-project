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

package dk.trustworks.essentials.examples.webshop.shipping.views.orders_ready_for_packaging

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage
import dk.trustworks.essentials.examples.webshop.sales.config.SalesAggregateTypes
import dk.trustworks.essentials.examples.webshop.sales.events.OrderPlaced
import dk.trustworks.essentials.examples.webshop.sales.events.ShippingDetailsAdded
import dk.trustworks.essentials.examples.webshop.shipping.config.ShippingAggregateTypes
import dk.trustworks.essentials.examples.webshop.shipping.events.OrderPackagingRequested
import org.springframework.stereotype.Service

/**
 * The packaging list, built from two contexts' events and one of its own.
 *
 * `shipping` learns everything it needs about an order by **subscribing** to `sales`' event stream: the address
 * from [ShippingDetailsAdded], the go-ahead from [OrderPlaced]. It never calls `sales`, holds no reference to
 * any of its classes beyond the exported events, and would keep working if `sales` were down for an hour - the
 * events are already in the store, and the subscription resumes where it left off.
 *
 * Reading another context's `events/` package is legal and deliberate. Injecting its write side - a decider, a
 * repository, a service - would not be.
 *
 * Two subscriptions, two orderings: events within one aggregate type arrive in order, but `sales`' and
 * `shipping`'s streams have no order relative to each other. Hence [on] for `ShippingDetailsAdded` creating the
 * row if it is missing, and the packaging handler tolerating a row that is not there yet. A projection that
 * assumed "details always arrive before placed" would be right almost always, which is the worst kind of wrong.
 */
@Service
class OrdersReadyForPackagingProjection(
    dependencies: ViewEventProcessorDependencies,
    private val repository: OrderReadyForPackagingViewRepository
) : ViewEventProcessor(dependencies) {

    override fun getProcessorName(): String = "OrdersReadyForPackagingProjection"

    override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> =
        listOf(SalesAggregateTypes.ORDERS, ShippingAggregateTypes.SHIPPING_ORDERS)

    @MessageHandler
    fun on(e: ShippingDetailsAdded, message: OrderedMessage) {
        val id = e.id.toString()
        val address = with(e.shippingAddress) { "$street, $postalCode $city, $countryCode" }
        val existing = repository.findById(id).orElse(null)
        if (existing == null) {
            repository.save(
                OrderReadyForPackagingView(
                    id = id,
                    shippingAddress = address,
                    shippingMethod = e.shippingMethod.name
                )
            )
            return
        }
        // A correction to the address. Overwriting is idempotent on its own - the same event applied twice
        // writes the same two fields - so this handler needs no event-order comparison.
        existing.shippingAddress = address
        existing.shippingMethod = e.shippingMethod.name
        repository.save(existing)
    }

    @MessageHandler
    fun on(e: OrderPlaced, message: OrderedMessage) {
        val id = e.id.toString()
        val existing = repository.findById(id).orElse(null)
        if (existing == null) {
            // Placed before the details reached this projection: keep the work item, fill the address in when
            // it arrives. Dropping the event here is how an order silently never gets packed.
            repository.save(
                OrderReadyForPackagingView(
                    id = id,
                    shippingAddress = "(pending)",
                    shippingMethod = "(pending)",
                    readyToPack = true
                )
            )
            return
        }
        existing.readyToPack = true
        repository.save(existing)
    }

    @MessageHandler
    fun on(e: OrderPackagingRequested, message: OrderedMessage) {
        // The work is done, so the row leaves the list. Deleting an already-deleted row is a no-op, which is
        // what makes this handler idempotent without a version check.
        repository.deleteById(e.id.toString())
    }

    override fun onSubscriptionsReset(aggregateType: AggregateType, resubscribeFromAndIncluding: GlobalEventOrder) {
        repository.deleteAll()
    }
}
