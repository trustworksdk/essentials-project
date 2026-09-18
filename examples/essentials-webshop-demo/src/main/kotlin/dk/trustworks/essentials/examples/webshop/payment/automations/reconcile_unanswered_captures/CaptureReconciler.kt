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

package dk.trustworks.essentials.examples.webshop.payment.automations.reconcile_unanswered_captures

import dk.trustworks.essentials.examples.webshop.payment.config.WebshopPaymentProperties
import dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway.CaptureState
import dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway.PaymentGateway
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.examples.webshop.payment.use_cases.record_capture_outcome.RecordCaptureOutcome
import dk.trustworks.essentials.examples.webshop.payment.views.captures_awaiting_outcome.CaptureAwaitingOutcomeViewRepository
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.reactive.command.CommandBus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * The automation that closes the case nobody plans for: we asked for money and never got an answer.
 *
 * Every other automation in this application is triggered by an **event**. This one is triggered by the
 * **absence** of one, which is why it is on a clock rather than on a subscription - there is no event for "the
 * webhook never came", and there never can be. A system that only reacts to messages it receives cannot detect
 * a message it did not receive.
 *
 * For each capture that has been outstanding longer than
 * [WebshopPaymentProperties.reconcileCapturesAfter], it asks the gateway what it knows, and there are exactly
 * three useful answers:
 *
 * - **Settled, either way** - record the outcome. The webhook was lost; the money still moved, or did not. Note
 *   that this goes through the same `RecordCaptureOutcome` command as the webhook does, so the same idempotency
 *   check applies and a webhook arriving at the same moment cannot produce a second event.
 * - **Never received** - our request never reached them, so ask again *with the same key*. This is the only
 *   branch where a retry actually charges anyone, and it is safe precisely because the key is derived rather
 *   than minted per attempt.
 * - **Still pending** - the gateway is working on it. Do nothing. Impatience here is how a duplicate charge
 *   gets created by the system that was supposed to prevent one.
 *
 * What this deliberately never does is **assume**. A charge whose outcome is unknown is not "probably fine" and
 * not "probably failed"; the parcel stays put and the row stays on the list until a third party tells us which.
 *
 * One honest limitation: with more than one instance of this application running, two reconcilers would ask at
 * the same time. Asking twice is harmless here - `outcomeFor` is a query and a re-request carries the same key -
 * but the real answer is a `FencedLock` around the tick, which the framework provides and this demo skips to
 * keep the moving parts visible.
 */
@Service
class CaptureReconciler(
    private val pendingCaptures: CaptureAwaitingOutcomeViewRepository,
    private val paymentGateway: PaymentGateway,
    private val paymentCommandBus: CommandBus,
    private val properties: WebshopPaymentProperties
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CaptureReconciler::class.java)
    }

    @Scheduled(fixedDelayString = "\${webshop-demo.payment.reconcile-interval:5s}")
    fun reconcileUnansweredCaptures() {
        val cutoff = OffsetDateTime.now().minus(properties.reconcileCapturesAfter)
        val overdue = pendingCaptures.findAllByRequestedAtBefore(cutoff)
        if (overdue.isEmpty()) {
            return
        }

        logger.info("{} capture(s) have been unanswered for too long - asking the gateway", overdue.size)
        overdue.forEach { pending ->
            val orderId = OrderId.of(pending.id)
            val key = IdempotencyKey.of(pending.idempotencyKey)
            when (val state = paymentGateway.outcomeFor(key)) {
                is CaptureState.Captured -> {
                    logger.info("Order '{}' was captured after all - the callback was lost", orderId)
                    paymentCommandBus.send<Any?, RecordCaptureOutcome>(
                        RecordCaptureOutcome(orderId, key, gatewayReference = state.gatewayReference)
                    )
                }

                is CaptureState.Failed -> {
                    logger.info("Order '{}' failed to capture: {} (callback was lost)", orderId, state.reason)
                    paymentCommandBus.send<Any?, RecordCaptureOutcome>(
                        RecordCaptureOutcome(orderId, key, failureReason = state.reason)
                    )
                }

                CaptureState.NeverReceived -> {
                    logger.warn(
                        "The gateway never received the capture for order '{}' - asking again with key '{}'",
                        orderId,
                        key
                    )
                    paymentGateway.requestCapture(key, orderId, pending.amount!!)
                }

                CaptureState.Pending ->
                    logger.info("Order '{}' is still pending at the gateway - waiting rather than asking again", orderId)
            }
        }
    }
}
