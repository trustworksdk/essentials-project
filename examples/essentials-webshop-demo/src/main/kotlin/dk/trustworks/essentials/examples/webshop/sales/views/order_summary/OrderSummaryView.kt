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
import dk.trustworks.essentials.examples.webshop.config.MoneyAttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnDefault
import java.time.OffsetDateTime
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
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

    @Convert(converter = MoneyAttributeConverter::class)
    @Column(precision = 19, scale = 2)
    var total: Amount? = null,

    var shippingAddress: String? = null,

    var shippingMethod: String? = null,

    var paymentMethod: String? = null,

    var placed: Boolean = false,

    var paymentStatus: String = "NOT_REQUIRED",

    /**
     * The gateway's own words, carried here from `CreditCardHoldRejected`, because "REJECTED" on its own sends
     * whoever reads this screen to the server log to find out why.
     */
    var paymentDeclineReason: String? = null,

    var shippingStatus: String = "NOT_STARTED",

    /**
     * `@ColumnDefault` is load-bearing, not decoration. `ddl-auto: update` adds a new non-null column with
     * `alter table ... add column cancelled boolean not null`, which PostgreSQL refuses on a table that already
     * has rows - and Hibernate logs that refusal as a WARN and starts anyway, so the column is simply missing
     * and every read of this view fails with a 500 at runtime. A default makes the statement valid, and it is
     * invisible to a test suite, which always builds the schema from nothing.
     */
    @ColumnDefault("false")
    var cancelled: Boolean = false,

    var cancellationReason: String? = null,

    /**
     * When this row was last written, which is what the order-history panel sorts on.
     *
     * It is a *display* property and nothing else. Ordering of events is `EventOrder` and `GlobalEventOrder`,
     * never a timestamp, and nothing in this application decides anything from this column - it exists so a
     * human looking at a list of orders sees the one they just touched at the top.
     *
     * `@ColumnDefault` for the same reason as [cancelled]: a new non-null column cannot be added to a table
     * that already has rows without one.
     */
    @ColumnDefault("now()")
    var lastUpdated: OffsetDateTime = OffsetDateTime.now()
)

interface OrderSummaryViewRepository : JpaRepository<OrderSummaryView, String> {
    /**
     * One page of orders, most recently touched first.
     *
     * Paging belongs in the query rather than in the caller, because the alternative is loading every order the
     * demo has ever recorded in order to show ten of them - a read model is cheap to query precisely because it
     * is queried precisely.
     */
    fun findAllByOrderByLastUpdatedDesc(pageable: Pageable): Page<OrderSummaryView>
}
