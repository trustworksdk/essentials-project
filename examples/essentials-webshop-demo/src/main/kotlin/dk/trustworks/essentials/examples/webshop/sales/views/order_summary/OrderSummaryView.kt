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

package dk.trustworks.essentials.examples.webshop.sales.views.order_summary

import dk.trustworks.essentials.types.Amount
import dk.trustworks.essentials.types.springdata.jpa.converters.AmountAttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * One row per order: everything the confirmation page shows, in the shape it shows it.
 *
 * The row is assembled from events published by **three** bounded contexts - `sales` for the basket total and
 * the order's own steps, `payment` for the hold, `shipping` for packaging and dispatch. That is the composite
 * screen: one query, no joins, no fan-out of HTTP calls to three services at render time.
 *
 * Every column is nullable-by-absence rather than by contract: a summary for an order that has not been placed
 * yet simply has no `placed` timestamp. The projection never invents a row's missing halves.
 */
@Entity
@Table(name = "order_summary_view")
data class OrderSummaryView(
    @Id
    @Column(name = "order_id")
    val id: String,

    @Column(name = "basket_id")
    var basketId: String? = null,

    @Convert(converter = AmountAttributeConverter::class)
    var total: Amount? = null,

    var shippingAddress: String? = null,

    var shippingMethod: String? = null,

    var paymentMethod: String? = null,

    var placed: Boolean = false,

    var paymentStatus: String = "NOT_REQUIRED",

    var shippingStatus: String = "NOT_STARTED"
)

interface OrderSummaryViewRepository : JpaRepository<OrderSummaryView, String>
