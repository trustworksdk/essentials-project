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

import dk.trustworks.essentials.examples.webshop.payment.routing.CreditCardHoldCommand
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId

/**
 * What the gateway eventually said about a capture, on its way in from a webhook or from the reconciler.
 *
 * Exactly one of [gatewayReference] and [failureReason] is set, which the decider checks. The command carries
 * the [idempotencyKey] the outcome refers to so that a late answer about an old charge cannot be mistaken for
 * an answer about a new one.
 */
data class RecordCaptureOutcome(
    override val id: OrderId,
    val idempotencyKey: IdempotencyKey,
    val gatewayReference: String? = null,
    val failureReason: String? = null
) : CreditCardHoldCommand
