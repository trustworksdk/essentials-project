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

package dk.trustworks.essentials.examples.webshop.payment.types

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.CharSequenceType

/**
 * The key that makes "charge this card" safe to send twice.
 *
 * A capture request can be sent more than once for reasons that have nothing to do with the customer: the
 * request timed out and we do not know whether it arrived, the process died between recording the request and
 * making the call, a redelivered message brought us back here. The gateway therefore has to be able to
 * recognise the *same* charge rather than perform a second one, and this is what it recognises it by.
 *
 * Two properties matter, and both are easy to get wrong:
 *
 * - **It is derived, not random.** [forOrderCapture] computes it from the order id, so every retry of the same
 *   charge produces the same key. A key minted per attempt - `UUID.randomUUID()` in the retry loop - looks like
 *   an idempotency key and provides none of its protection: the gateway sees a new key and charges again.
 * - **It is recorded before the call.** The automation sends `RequestFundsCapture` first, so
 *   `FundsCaptureRequested` is in the stream before anyone talks to the gateway. A charge we cannot prove we
 *   asked for is a charge we cannot reconcile afterwards.
 *
 * This demo captures each order once, so the order id alone identifies the charge. A system that captures
 * partially, or re-captures after a failure, needs the attempt in the key as well - and then has to decide what
 * "the same charge" means, which is a business question and not a technical one.
 */
class IdempotencyKey : CharSequenceType<IdempotencyKey> {
    constructor(value: CharSequence) : super(value)
    constructor(value: String) : super(value)

    companion object {
        @JvmStatic
        fun of(value: CharSequence): IdempotencyKey = IdempotencyKey(value)

        /** The one and only key for capturing [orderId]'s funds, derived so that every retry recomputes it. */
        @JvmStatic
        fun forOrderCapture(orderId: OrderId): IdempotencyKey = IdempotencyKey("capture:$orderId")
    }
}
