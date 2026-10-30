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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.cancel_order

import dk.trustworks.essentials.examples.webshop.sales.routing.OrderCommand
import dk.trustworks.essentials.examples.webshop.sales.types.OrderId

/**
 * Call off a placed order, and say why.
 *
 * [reason] is carried by the command rather than derived by the decider, for the same reason the payment
 * automation carries the gateway's answer: the decider is a pure function of its own stream, and "the card was
 * declined" is a fact from another context that it has no way to look up.
 */
data class CancelOrder(
    override val id: OrderId,
    val reason: String
) : OrderCommand
