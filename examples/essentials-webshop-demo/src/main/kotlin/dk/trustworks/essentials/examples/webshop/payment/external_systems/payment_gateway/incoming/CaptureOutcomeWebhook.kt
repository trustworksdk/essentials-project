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

/**
 * The gateway's callback body, in the gateway's own shape.
 *
 * Plain strings on purpose. This is the outside world's vocabulary, and it stops here: the translation slice
 * turns it into this context's own types and commands, and nothing downstream ever sees this class. That is the
 * anti-corruption boundary doing its only job - if the provider renames a field, this file changes and nothing
 * else does.
 *
 * Exactly one of [gatewayReference] and [failureReason] is set, which is the provider's convention and not
 * something the domain should have to know.
 */
data class CaptureOutcomeWebhook(
    val idempotencyKey: String,
    val orderId: String,
    val gatewayReference: String? = null,
    val failureReason: String? = null
)
