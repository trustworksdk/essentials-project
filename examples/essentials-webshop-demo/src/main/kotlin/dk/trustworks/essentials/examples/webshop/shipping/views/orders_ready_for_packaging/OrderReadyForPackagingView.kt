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

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * The warehouse's work list: orders that are placed and not yet packed.
 *
 * A **to-do view** is a read model whose rows are outstanding work, and it is the third of the event-modelling
 * patterns - the one that turns an event into an action. Here the action is taken by a person; in `payment` the
 * equivalent list is drained by an automation. Same shape, different trigger.
 *
 * Rows are removed when the work is done, so the list is short by construction rather than by filtering a
 * status column across the whole order history.
 */
@Entity
@Table(name = "orders_ready_for_packaging_view")
data class OrderReadyForPackagingView(
    @Id
    @Column(name = "order_id")
    val id: String,

    var shippingAddress: String,

    var shippingMethod: String,

    var readyToPack: Boolean = false
)

interface OrderReadyForPackagingViewRepository : JpaRepository<OrderReadyForPackagingView, String> {
    fun findByReadyToPackTrue(): List<OrderReadyForPackagingView>
}
