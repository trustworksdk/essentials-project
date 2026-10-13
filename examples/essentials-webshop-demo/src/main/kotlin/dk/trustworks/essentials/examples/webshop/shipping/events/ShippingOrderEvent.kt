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

package dk.trustworks.essentials.examples.webshop.shipping.events

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.examples.webshop.shipping.types.TrackingNumber

/**
 * Every event in the `ShippingOrders` event stream.
 *
 * The stream is keyed by the `sales` [OrderId], but it is `shipping`'s own stream and nothing in `sales` writes
 * to it. Two contexts, two consistency boundaries, one shared identifier - and no shared table to lock.
 */
sealed interface ShippingOrderEvent {
    val id: OrderId
}

/** The warehouse has been told to pack this order. */
data class OrderPackagingRequested(
    override val id: OrderId
) : ShippingOrderEvent

/**
 * The parcel is on its way. This is the event that leaves the system: `order_management`'s publisher turns it
 * into an external event on a Kafka topic for whoever cares outside this application.
 */
data class OrderShipped(
    override val id: OrderId,
    val trackingNumber: TrackingNumber
) : ShippingOrderEvent
