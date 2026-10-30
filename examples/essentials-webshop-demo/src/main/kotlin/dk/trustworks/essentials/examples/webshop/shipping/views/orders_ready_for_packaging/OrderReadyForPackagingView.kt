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
import org.hibernate.annotations.ColumnDefault
import org.springframework.data.jpa.repository.JpaRepository

/**
 * The warehouse's work list: orders that are placed and not yet shipped.
 *
 * A **to-do view** is a read model whose rows are outstanding work, and it is the third of the event-modelling
 * patterns - the one that turns an event into an action. Here the action is taken by a person; in `payment` the
 * equivalent list is drained by an automation. Same shape, different trigger.
 *
 * A row carries the work item through both of its steps rather than leaving on the first one. Packing and
 * dispatching are two actions, so a row that vanished on `OrderPackagingRequested` took the second action's
 * button with it and left the order unshippable from any screen. It leaves on `OrderShipped` - the work is
 * finished - or on `OrderCancelled` - the work is called off. Either way the list stays short by construction
 * rather than by filtering a status column across the whole order history.
 *
 * [paymentDeclineReason] is the only field here that `shipping` does not derive from its own or `sales`' events,
 * and it is what makes this list a **guard**: an order whose card was declined stays visible and explains
 * itself, but offers no action. `PackageOrderDecider` cannot enforce that - the decision needs `payment`'s
 * stream, which it cannot see - so the check lives in the read model, where being a moment stale is acceptable.
 */
@Entity
@Table(name = "orders_ready_for_packaging_view")
data class OrderReadyForPackagingView(
    @Id
    @Column(name = "order_id")
    val id: String,

    var shippingAddress: String = PENDING,

    var shippingMethod: String = PENDING,

    var readyToPack: Boolean = false,

    /** See `OrderSummaryView.cancelled` for why a new non-null column needs this to survive `ddl-auto: update`. */
    @ColumnDefault("false")
    var packaged: Boolean = false,

    var paymentDeclineReason: String? = null,

    /**
     * Whether the money for this order is actually ours.
     *
     * A hold is not payment - it is a promise the bank can still break. So dispatch waits for
     * `FundsCaptured`, and this flag is what the warehouse screen gates the button on. It is also set for
     * orders that are not paid by card at all, because "no capture is coming" and "the capture succeeded" are
     * the same answer to the only question being asked here: may this parcel leave?
     */
    @ColumnDefault("false")
    var paymentSettled: Boolean = false,

    /** Why the settlement failed, when it did. Distinct from a declined authorization: this one is post-packing. */
    var captureFailureReason: String? = null
) {
    companion object {
        /** What a field says while the event carrying it has not been projected yet. */
        const val PENDING: String = "(pending)"
    }
}

interface OrderReadyForPackagingViewRepository : JpaRepository<OrderReadyForPackagingView, String> {
    fun findByReadyToPackTrue(): List<OrderReadyForPackagingView>
}
