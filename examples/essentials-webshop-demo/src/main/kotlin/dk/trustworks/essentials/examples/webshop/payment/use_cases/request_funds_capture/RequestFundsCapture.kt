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

package dk.trustworks.essentials.examples.webshop.payment.use_cases.request_funds_capture

import dk.trustworks.essentials.examples.webshop.payment.routing.CreditCardHoldCommand
import dk.trustworks.essentials.examples.webshop.payment.types.IdempotencyKey
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId
import dk.trustworks.essentials.types.Amount

/**
 * Record that we are about to ask the gateway for the money.
 *
 * The [idempotencyKey] arrives on the command rather than being invented by the decider, for the same reason
 * the browser mints the order id: a decider is a pure function of its own stream and may be replayed, so it
 * cannot be the thing that produces a value the outside world will remember.
 */
data class RequestFundsCapture(
    override val id: OrderId,
    val amount: Amount,
    val idempotencyKey: IdempotencyKey
) : CreditCardHoldCommand
