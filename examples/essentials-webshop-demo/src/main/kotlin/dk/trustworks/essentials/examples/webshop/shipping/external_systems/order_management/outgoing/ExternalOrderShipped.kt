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

/**
 * The shape that leaves the building.
 *
 * It is a separate class from the internal `OrderShipped` on purpose. The internal event is free to change with
 * the domain; this one is a published contract with consumers nobody here controls, so it carries plain types,
 * no semantic types and no Kotlin-specific shapes, and gains fields only additively.
 *
 * [eventOrder] is the position of the internal event in its stream, passed through so a consumer can deduplicate
 * and order what it receives - the same job `EventOrder` does inside, exposed deliberately rather than leaked.
 */
data class ExternalOrderShipped(
    val orderId: String,
    val trackingNumber: String,
    val eventOrder: Long
)
