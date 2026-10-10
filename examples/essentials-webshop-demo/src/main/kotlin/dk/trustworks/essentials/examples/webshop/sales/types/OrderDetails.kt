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

package dk.trustworks.essentials.examples.webshop.sales.types

/**
 * The value objects the order events carry. They live in `types/` because `shipping` and `payment` read them off
 * those events, and an exported event may only reference exported types.
 *
 * All three are immutable, and they are written into the event stream as-is, so their property names are part of
 * the persisted JSON just as the events' own are.
 */
data class Address(
    val street: String,
    val postalCode: String,
    val city: String,
    val countryCode: String
)

/**
 * How the order should reach the customer. `shipping` reads this off `ShippingDetailsAdded` and decides what
 * packaging and carrier that implies - which is its own business, not `sales`'.
 */
enum class ShippingMethod {
    STANDARD,
    EXPRESS,
    PICKUP_POINT
}

/**
 * How the customer intends to pay. `payment` only places a hold for [CREDIT_CARD]; an [INVOICE] order needs no
 * authorization and is let through.
 */
enum class PaymentMethod {
    CREDIT_CARD,
    INVOICE
}
