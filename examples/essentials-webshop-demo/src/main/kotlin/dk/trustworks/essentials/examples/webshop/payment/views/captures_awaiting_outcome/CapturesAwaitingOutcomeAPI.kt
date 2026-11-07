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

import dk.trustworks.essentials.types.Amount
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.OffsetDateTime

@RestController
class CapturesAwaitingOutcomeAPI(private val repository: CaptureAwaitingOutcomeViewRepository) {

    data class PendingCapture(
        val orderId: String,
        val idempotencyKey: String,
        val amount: Amount?,
        val waitingSeconds: Long
    )

    /** What the "captures awaiting outcome" panel renders, oldest wait first - the one most worth looking at. */
    @GetMapping("/api/payment/captures-awaiting-outcome")
    fun capturesAwaitingOutcome(): List<PendingCapture> =
        repository.findAllByOrderByRequestedAtAsc().map {
            PendingCapture(
                orderId = it.id,
                idempotencyKey = it.idempotencyKey,
                amount = it.amount,
                waitingSeconds = Duration.between(it.requestedAt, OffsetDateTime.now()).seconds
            )
        }
}
