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

package dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway

import dk.trustworks.essentials.examples.webshop.payment.config.WebshopPaymentProperties
import dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway.incoming.CaptureOutcomeWebhook
import dk.trustworks.essentials.examples.webshop.payment.external_systems.payment_gateway.incoming.CardGatewayWebhookAPI
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.Amount
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The card network, behind a port - and behind **two** call shapes, because the two things we ask it are not the
 * same kind of question.
 *
 * [placeHold] is synchronous. An authorization is a question with an answer: the bank says yes or no in a few
 * hundred milliseconds, there is nothing to record until it has, and waiting is the simplest correct thing to
 * do. This is the one place in the application where request/response is the right shape.
 *
 * [requestCapture] is not. A settlement is a *process* at the other end, and every real card platform today
 * answers it the same way: `202 Accepted`, here is a reference, we will call you back. So this method returns
 * an acknowledgement rather than an outcome, and the outcome arrives later at a webhook. Everything awkward
 * about the rest of this context follows from that one fact, and none of it is avoidable by wishing the API
 * were synchronous:
 *
 * - the answer may arrive **twice**, so the decider that records it must be idempotent;
 * - the answer may arrive **before** our own transaction commits, so the handler must retry rather than reject;
 * - the answer may **never** arrive, so a request we have no answer for has to be findable and re-askable -
 *   which is why the request is recorded as an event before this is ever called, and why
 *   [outcomeFor] exists at all;
 * - and because we may ask again without knowing whether the first ask arrived, every ask carries an
 *   [IdempotencyKey] that the gateway - not us - uses to tell "the same charge" from "another charge".
 *
 * Both calls sit in a translation slice rather than inside a decider, and that boundary is the point. A decider
 * must stay a pure function of `(command, events)` so it can be tested without a network and replayed without
 * re-charging anyone's card. So an automation calls this, and then sends a command carrying the **outcome**,
 * which the decider simply records.
 */
interface PaymentGateway {
    fun placeHold(orderId: OrderId, amount: Amount): HoldResult

    /**
     * Ask for the money. Returns as soon as the gateway has accepted the request, not when the money has moved.
     *
     * Sending the same [idempotencyKey] twice asks about the same charge: the gateway answers for the charge it
     * already has instead of creating a second one. That is the contract that makes a retry after a timeout
     * safe, and it is the gateway's promise rather than ours.
     */
    fun requestCapture(idempotencyKey: IdempotencyKey, orderId: OrderId, amount: Amount): CaptureAccepted

    /**
     * What the gateway currently knows about the charge under [idempotencyKey] - the question the reconciler
     * asks when no webhook ever arrived.
     *
     * A timeout is not a failure; it is an *unknown outcome*. The only honest way to resolve an unknown outcome
     * is to ask the system that knows, which is why a gateway without an endpoint like this one cannot be
     * integrated with safely at all.
     */
    fun outcomeFor(idempotencyKey: IdempotencyKey): CaptureState
}

/** The gateway has taken the request. [gatewayReference] is its handle on the charge, for support and disputes. */
data class CaptureAccepted(val gatewayReference: String)

/** What the gateway knows about a charge right now. */
sealed interface CaptureState {
    /** The gateway has never heard of this key: our request never arrived, and asking again is safe. */
    data object NeverReceived : CaptureState

    /** The gateway has the request and has not settled it yet. Nothing to record; keep waiting. */
    data object Pending : CaptureState

    /** Settled. */
    data class Captured(val gatewayReference: String) : CaptureState

    /** Refused. */
    data class Failed(val reason: String) : CaptureState
}

/** What the gateway answered. Not an event - it becomes one only after a decider has accepted it. */
sealed interface HoldResult {
    data class Authorized(val authorizationCode: String) : HoldResult
    data class Declined(val reason: String) : HoldResult
}

/**
 * The demo's stand-in for a real card platform, including the parts of one that are inconvenient.
 *
 * Authorizations are answered on the spot. Captures are accepted and answered later, by calling this
 * application's own webhook endpoint over HTTP - a real request, through the real controller, so the demo
 * exercises the actual inbound path rather than a shortcut into the Inbox.
 *
 * Every dial is in [WebshopPaymentProperties]: which amounts decline, which amounts authorize and then fail to
 * settle, how long the callback takes, whether it is delivered twice (it is), and which amounts never get a
 * callback at all. A real implementation would be the only thing that changes, and only this file would know.
 */
@Service
class InMemoryPaymentGateway(
    private val properties: WebshopPaymentProperties,
    @Value("\${local.server.port:\${server.port:8080}}") private val serverPort: Int
) : PaymentGateway {

    companion object {
        private val logger = LoggerFactory.getLogger(InMemoryPaymentGateway::class.java)
    }

    /** What this gateway believes about each charge. A real one has a database; the shape is the same. */
    private val charges = ConcurrentHashMap<String, CaptureState>()

    /**
     * Built directly rather than injected: Spring Boot 4 moved `RestClient.Builder`'s auto-configuration into
     * its own module, so there is no such bean on this application's classpath and asking for one fails the
     * context at startup. Nothing here needs the builder's shared configuration anyway.
     */
    private val webhookClient: RestClient = RestClient.create()

    private val callbacks: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "card-gateway-callbacks").apply { isDaemon = true }
        }

    override fun placeHold(orderId: OrderId, amount: Amount): HoldResult {
        logger.info("Asking the payment gateway to hold {} for order '{}'", amount, orderId)
        return if (amount.compareTo(properties.declineHoldAbove) > 0) {
            HoldResult.Declined("Amount $amount exceeds the authorization limit")
        } else {
            HoldResult.Authorized(UUID.randomUUID().toString().take(8).uppercase())
        }
    }

    override fun requestCapture(
        idempotencyKey: IdempotencyKey,
        orderId: OrderId,
        amount: Amount
    ): CaptureAccepted {
        val key = idempotencyKey.toString()
        val existing = charges[key]
        if (existing != null && existing !is CaptureState.NeverReceived) {
            // The contract that makes a retry after a timeout safe: the same key asks about the same charge.
            // Note what this does *not* do - it does not take the money a second time, and it does not answer
            // "already captured" as an error. It answers for the charge it has.
            logger.info("Capture for order '{}' already known under key '{}' - not charging again", orderId, key)
            return CaptureAccepted(referenceFor(key))
        }
        charges[key] = CaptureState.Pending
        logger.info("Capture of {} accepted for order '{}' under key '{}' - outcome to follow", amount, orderId, key)
        scheduleOutcome(key, orderId, amount)
        return CaptureAccepted(referenceFor(key))
    }

    override fun outcomeFor(idempotencyKey: IdempotencyKey): CaptureState =
        charges[idempotencyKey.toString()] ?: CaptureState.NeverReceived

    /**
     * Decide the outcome, remember it, and call back - twice, unless this amount is one of the ones whose
     * callback is deliberately lost.
     *
     * The outcome is settled here rather than at callback time on purpose: a gateway knows what happened
     * whether or not it manages to tell us, which is the entire reason [outcomeFor] can resolve a charge whose
     * webhook never arrived.
     */
    private fun scheduleOutcome(key: String, orderId: OrderId, amount: Amount) {
        val outcome: CaptureState = if (amount.compareTo(properties.failCaptureAbove) > 0) {
            CaptureState.Failed("The issuer refused to settle $amount against the existing authorization")
        } else {
            CaptureState.Captured(referenceFor(key))
        }

        callbacks.schedule({
            charges[key] = outcome
            if (amount.cents() == properties.loseWebhookForCents) {
                logger.warn(
                    "Gateway settled order '{}' as {} but the callback is being dropped - only the reconciler " +
                        "will ever find out",
                    orderId,
                    outcome::class.simpleName
                )
                return@schedule
            }
            deliver(key, orderId, outcome)
            if (properties.duplicateWebhook) {
                callbacks.schedule({ deliver(key, orderId, outcome) }, 800, TimeUnit.MILLISECONDS)
            }
        }, properties.webhookDelay.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun deliver(key: String, orderId: OrderId, outcome: CaptureState) {
        val body = CaptureOutcomeWebhook(
            idempotencyKey = key,
            orderId = orderId.toString(),
            gatewayReference = (outcome as? CaptureState.Captured)?.gatewayReference,
            failureReason = (outcome as? CaptureState.Failed)?.reason
        )
        val baseUrl = properties.webhookBaseUrl.ifBlank { "http://localhost:$serverPort" }
        try {
            webhookClient
                .post()
                .uri("$baseUrl/api/payment/webhooks/card-gateway")
                .header(CardGatewayWebhookAPI.SIGNATURE_HEADER, properties.webhookSecret)
                .body(body)
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            // A real gateway retries a failed callback for hours and then gives up. Ours logs and leaves the
            // charge for the reconciler, which is the same outcome by a shorter route.
            logger.warn("Delivering the capture callback for order '{}' failed: {}", orderId, e.message)
        }
    }

    private fun referenceFor(key: String): String =
        "CAP-" + Integer.toHexString(key.hashCode()).uppercase().takeLast(6)

    private fun Amount.cents(): Int =
        value().remainder(java.math.BigDecimal.ONE).movePointRight(2).abs().toInt()
}
