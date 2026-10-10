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

package dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway.incoming

import dk.trustworks.essentials.components.foundation.messaging.MessageDeliveryErrorHandler
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inbox
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.InboxConfig
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.InboxName
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.MessageConsumptionMode
import dk.trustworks.essentials.examples.webshop.payment.config.WebshopPaymentProperties
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.examples.webshop.payment.use_cases.record_capture_outcome.RecordCaptureOutcome
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.reactive.command.CommandBus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * The inbound half of the integration: the gateway's callback, arriving as an HTTP request we do not control.
 *
 * **What this endpoint must not do is the work.** A webhook caller is a third party with a short timeout and a
 * retry policy of its own. If the outcome were applied inline, a slow projection or a locked row would turn
 * into a timeout at the gateway, the gateway would retry, and the retry would race the request still running.
 * So the endpoint does exactly two things - verify the call is really from the gateway, and store the message
 * durably - and answers `202 Accepted`. The work happens afterwards, on a queue that can retry on *our* terms.
 *
 * **Why an [Inbox] and not a thread pool.** Storing the message and acknowledging the caller must not come
 * apart: acknowledge first and a crash loses a settled payment, work first and the caller times out. The Inbox
 * writes the message in the same transaction the request runs in - hence `@Transactional` - so the `202` is
 * only sent if the message is safely stored, and the handler below is driven by durable queue delivery with
 * redelivery and, eventually, a dead letter.
 *
 * **The ordering hazard this is built for.** The callback can arrive before the transaction that recorded
 * `FundsCaptureRequested` has committed - the gateway is fast and our own commit is not instant. The command
 * then fails with `CaptureNotYetRequestedException`, which is a plain `RuntimeException` so that the Inbox
 * retries it a moment later, by which time the request is there. This is also why the redelivery policy below
 * stops on `IllegalArgumentException` only: that is the framework's permanent-error class, and an outcome for a
 * key we never asked with will not become valid by waiting.
 */
@RestController
class CardGatewayWebhookAPI(
    inboxes: Inboxes,
    private val commandBus: CommandBus,
    private val properties: WebshopPaymentProperties
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CardGatewayWebhookAPI::class.java)

        /** Where the gateway puts the signature. Named here because the gateway simulator sends it. */
        const val SIGNATURE_HEADER: String = "X-Gateway-Signature"
    }

    /**
     * `SingleGlobalConsumer`, not competing consumers: two instances processing outcomes for the same order at
     * once would each load the stream, each see no outcome recorded, and each record one. The decider's
     * idempotency check is a check against *what it can see*, and two concurrent transactions cannot see each
     * other. One consumer at a time across the cluster is the cheap answer.
     *
     * The message is forwarded straight to the command bus - the framework has an overload for exactly this -
     * so the outcome is recorded off the HTTP thread, inside our own transaction, with redelivery behind it.
     */
    private val inbox: Inbox = inboxes.getOrCreateInbox(
        InboxConfig.builder()
            .inboxName(InboxName.of("Payment:CardGatewayCaptureOutcomes"))
            .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
            .numberOfParallelMessageConsumers(1)
            .redeliveryPolicy(
                RedeliveryPolicy.exponentialBackoff()
                    .setInitialRedeliveryDelay(Duration.ofMillis(200))
                    .setFollowupRedeliveryDelay(Duration.ofMillis(200))
                    .setFollowupRedeliveryDelayMultiplier(1.2)
                    .setMaximumFollowupRedeliveryDelayThreshold(Duration.ofSeconds(3))
                    .setMaximumNumberOfRedeliveries(20)
                    .setDeliveryErrorHandler(
                        MessageDeliveryErrorHandler.stopRedeliveryOn(IllegalArgumentException::class.java)
                    )
                    .build()
            )
            .build(),
        commandBus
    )

    @PostMapping("/api/payment/webhooks/card-gateway")
    @Transactional
    fun onCaptureOutcome(
        @RequestHeader(value = SIGNATURE_HEADER, required = false) signature: String?,
        @RequestBody webhook: CaptureOutcomeWebhook
    ): ResponseEntity<Void> {
        if (signature != properties.webhookSecret) {
            // This URL is public and it moves money. An unverified one lets anyone mark any order as paid.
            logger.warn("Rejected a capture callback for order '{}': bad signature", webhook.orderId)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        logger.info(
            "Capture callback for order '{}': {}",
            webhook.orderId,
            webhook.gatewayReference?.let { "captured, reference $it" } ?: "failed - ${webhook.failureReason}"
        )

        inbox.addMessageReceived(
            RecordCaptureOutcome(
                id = OrderId.of(webhook.orderId),
                idempotencyKey = IdempotencyKey.of(webhook.idempotencyKey),
                gatewayReference = webhook.gatewayReference,
                failureReason = webhook.failureReason
            )
        )
        return ResponseEntity.accepted().build()
    }
}
