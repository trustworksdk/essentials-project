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

package dk.trustworks.essentials.examples.webshop.payment.automations.hold_funds_on_order_placed

import dk.trustworks.essentials.types.Amount
import dk.trustworks.essentials.types.springdata.jpa.converters.AmountAttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * The automation's own state: one row per order it is watching, holding the three facts it needs before it can
 * ask the card network for anything.
 *
 * This is the "to-do view" of the automation pattern, and it lives **inside the automation slice** rather than
 * in a view slice of its own. That placement is the whole point: the policy writes this row and reads it back in
 * the same handler, in the same transaction, so there is no window in which the policy has been told about an
 * order that its own state does not know about yet.
 *
 * The first version of this demo did split them - a view slice projected the row, and the policy read it on a
 * separate subscription. It worked most of the time, which is the problem: the two subscriptions have no order
 * relative to each other, so the policy regularly ran before the row existed, threw, and depended on redelivery
 * to recover. On a slower machine the retries ran out first and the message became a dead letter, which is an
 * order that silently never gets charged. Owning the state removed the race rather than tuning it.
 *
 * [outcome] is what makes the whole thing idempotent-by-construction: once an authorization has been recorded,
 * no redelivery of any of these events asks the gateway again.
 */
@Entity
@Table(name = "orders_awaiting_hold")
data class OrderAwaitingHold(
    @Id
    @Column(name = "order_id")
    val id: String,

    @Convert(converter = AmountAttributeConverter::class)
    var total: Amount? = null,

    var paymentMethod: String? = null,

    var placed: Boolean = false,

    var outcome: String? = null
) {
    /** A card order that is placed, priced and not yet authorized is work waiting to be done. */
    fun needsHold(cardPaymentMethod: String): Boolean =
        placed && total != null && paymentMethod == cardPaymentMethod && outcome == null
}

interface OrderAwaitingHoldRepository : JpaRepository<OrderAwaitingHold, String>
