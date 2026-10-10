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

package dk.trustworks.essentials.examples.webshop.payment.automations.capture_funds_when_packaged

import dk.trustworks.essentials.examples.webshop.config.MoneyAttributeConverter
import dk.trustworks.essentials.types.Amount
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnDefault
import org.springframework.data.jpa.repository.JpaRepository

/**
 * The capture automation's own state: one row per order, holding the two facts that have to be true before we
 * take anyone's money, and the outcome that closes the row.
 *
 * Same shape and same reasoning as `hold_funds_on_order_placed`'s [OrderAwaitingHold] - the state lives inside
 * the automation slice, written and read back in one handler and one transaction, so the policy never runs
 * ahead of its own knowledge. The two facts arrive on two different streams with no order between them: the
 * authorized amount from `CreditCardHolds`, the go-ahead from `shipping`'s `ShippingOrders`. Whichever lands
 * last is the one that triggers the capture.
 *
 * Note which fact is *not* here: whether the customer paid by card. An order that never had a hold placed can
 * never satisfy [needsCapture], because [authorizedAmount] only ever gets set by `CreditCardHoldPlaced`. An
 * invoice order simply never becomes work for this policy, and needed no special case to be excluded.
 */
@Entity
@Table(name = "orders_awaiting_capture")
data class OrderAwaitingCapture(
    @Id
    @Column(name = "order_id")
    val id: String,

    @Convert(converter = MoneyAttributeConverter::class)
    @Column(precision = 19, scale = 2)
    var authorizedAmount: Amount? = null,

    @ColumnDefault("false")
    var packaged: Boolean = false,

    var outcome: String? = null
) {
    /** A packed order with money authorized against it, and no settlement yet, is work waiting to be done. */
    fun needsCapture(): Boolean = packaged && authorizedAmount != null && outcome == null
}

interface OrderAwaitingCaptureRepository : JpaRepository<OrderAwaitingCapture, String>
