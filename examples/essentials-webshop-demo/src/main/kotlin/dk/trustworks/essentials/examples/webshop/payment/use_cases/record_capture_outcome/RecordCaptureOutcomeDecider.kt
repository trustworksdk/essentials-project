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

package dk.trustworks.essentials.examples.webshop.payment.use_cases.record_capture_outcome

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.payment.events.CreditCardHoldEvent
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureFailed
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptureRequested
import dk.trustworks.essentials.examples.webshop.payment.events.FundsCaptured
import org.springframework.stereotype.Service

/**
 * The idempotent end of an at-least-once world.
 *
 * Webhooks are delivered at least once, and this demo's gateway delivers every one of them **twice** on purpose
 * so that the second delivery is visible in every run. The reconciler can also ask for an outcome that a
 * webhook is delivering at the same moment. So this decider is written for the case where it is told the same
 * thing more than once, and answers `null`: no second event, nothing downstream re-triggered, no second parcel
 * released.
 *
 * It refuses two things rather than guessing:
 *
 * - an outcome for a capture this stream has no request for, by throwing a **retryable** exception, because the
 *   webhook may simply have overtaken our own commit;
 * - an outcome whose [RecordCaptureOutcome.idempotencyKey] is not the key we asked with, because that answer is
 *   about a different charge and recording it here would attribute someone else's money to this order.
 */
@Service
class RecordCaptureOutcomeDecider : Decider<RecordCaptureOutcome, CreditCardHoldEvent> {

    override fun handle(cmd: RecordCaptureOutcome, events: List<CreditCardHoldEvent>): CreditCardHoldEvent? {
        if (events.any { it is FundsCaptured || it is FundsCaptureFailed }) {
            return null   // the outcome is already recorded - this is the duplicate delivery
        }
        val requested = events.filterIsInstance<FundsCaptureRequested>().lastOrNull()
            ?: throw CaptureNotYetRequestedException(cmd.id)
        if (requested.idempotencyKey != cmd.idempotencyKey) {
            throw UnknownCaptureException(cmd.id, cmd.idempotencyKey)
        }
        require(cmd.gatewayReference != null || cmd.failureReason != null) {
            "RecordCaptureOutcome for order '${cmd.id}' carries neither a gateway reference nor a failure reason"
        }
        return if (cmd.gatewayReference != null) {
            FundsCaptured(cmd.id, requested.amount, requested.idempotencyKey, cmd.gatewayReference)
        } else {
            FundsCaptureFailed(cmd.id, requested.amount, requested.idempotencyKey, cmd.failureReason!!)
        }
    }

    override fun canHandle(cmd: Any): Boolean = cmd is RecordCaptureOutcome
}
