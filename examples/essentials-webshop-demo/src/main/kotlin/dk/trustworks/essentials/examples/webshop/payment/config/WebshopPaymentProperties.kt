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

package dk.trustworks.essentials.examples.webshop.payment.config

import dk.trustworks.essentials.types.Amount
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The `payment` context's own configuration, which is mostly a set of dials on the **simulated** card gateway.
 *
 * They exist so every path the context has to handle is reachable from the shop page during a talk, without a
 * test card, a sandbox account or a broker. Each one corresponds to something a real gateway does and a real
 * integration has to survive.
 */
@ConfigurationProperties(prefix = "webshop-demo.payment")
data class WebshopPaymentProperties(
    /** Authorizations above this are declined outright: the unhappy path before anything is packed. */
    val declineHoldAbove: Amount = Amount.of("10000.00"),

    /**
     * Captures above this fail even though the authorization succeeded.
     *
     * A real bank can refuse a settlement it previously approved - an expired authorization, a cancelled card,
     * a fraud rule that fired since. The order is already packed when it happens, which is exactly the case
     * worth showing: the warehouse's work item goes back to blocked instead of the parcel going out.
     */
    val failCaptureAbove: Amount = Amount.of("5000.00"),

    /** How long the gateway takes to call back. Long enough to see the pending state, short enough to demo. */
    val webhookDelay: Duration = Duration.ofMillis(1500),

    /**
     * Whether every webhook is delivered twice.
     *
     * On by default, because at-least-once is what real webhook delivery is, and a duplicate that only shows up
     * in production is a duplicate nobody designed for. With this on, the idempotency of
     * `RecordCaptureOutcomeDecider` is exercised in every single demo run.
     */
    val duplicateWebhook: Boolean = true,

    /**
     * Captures whose amount ends in these cents never get a webhook at all.
     *
     * The lost-callback case, made triggerable by typing a price: charge 1999.13 and no answer ever arrives, so
     * the only thing that resolves the charge is the reconciler asking the gateway what happened.
     */
    val loseWebhookForCents: Int = 13,

    /** Base URL the simulated gateway calls back on. Empty means "this application, on its own port". */
    val webhookBaseUrl: String = "",

    /**
     * Shared secret the webhook is signed with.
     *
     * A demo-grade stand-in for the real thing, which is an HMAC over the raw body plus a timestamp, compared in
     * constant time, with the timestamp checked so a captured callback cannot be replayed a week later. What is
     * *not* demo-grade is the decision to verify at all: a payment webhook endpoint is a public URL that moves
     * money, and an unverified one lets anyone mark any order as paid.
     */
    val webhookSecret: String = "demo-gateway-secret",

    /** A capture with no answer for this long is the reconciler's problem. */
    val reconcileCapturesAfter: Duration = Duration.ofSeconds(15),

    /** How often the reconciler looks. */
    val reconcileInterval: Duration = Duration.ofSeconds(5)
)
