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

package dk.trustworks.essentials.examples.webshop.payment.views.captures_awaiting_outcome

import dk.trustworks.essentials.examples.webshop.config.MoneyAttributeConverter
import dk.trustworks.essentials.types.Amount
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * Every capture we have asked for and have no answer to: the to-do view whose work item is *an unknown*.
 *
 * This is the leg of the pattern that integrations usually leave out. Retrying a failed call is easy, because a
 * failure is an answer. A **timeout is not an answer** - the charge may have gone through, may not have, and
 * nothing local can tell which. The only honest resolutions are to ask the gateway what it knows, or to ask
 * again with the same idempotency key. Both need a list of outstanding charges, and this is that list.
 *
 * Rows arrive on `FundsCaptureRequested` and leave on `FundsCaptured` or `FundsCaptureFailed`, so a healthy
 * system keeps this table nearly empty and a row that has been here a while is a real operational signal - the
 * kind of thing worth an alert, because it means money is in an unknown state.
 */
@Entity
@Table(name = "captures_awaiting_outcome_view")
data class CaptureAwaitingOutcomeView(
    @Id
    @Column(name = "order_id")
    val id: String,

    var idempotencyKey: String = "",

    @Convert(converter = MoneyAttributeConverter::class)
    @Column(precision = 19, scale = 2)
    var amount: Amount? = null,

    var requestedAt: OffsetDateTime = OffsetDateTime.now()
)

interface CaptureAwaitingOutcomeViewRepository : JpaRepository<CaptureAwaitingOutcomeView, String> {
    fun findAllByRequestedAtBefore(cutoff: OffsetDateTime): List<CaptureAwaitingOutcomeView>

    fun findAllByOrderByRequestedAtAsc(): List<CaptureAwaitingOutcomeView>
}
