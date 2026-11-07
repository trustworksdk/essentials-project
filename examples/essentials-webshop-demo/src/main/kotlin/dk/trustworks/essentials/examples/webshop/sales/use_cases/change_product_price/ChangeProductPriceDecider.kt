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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.change_product_price

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.sales.events.HasProductPrice
import dk.trustworks.essentials.examples.webshop.sales.events.ProductEvent
import dk.trustworks.essentials.examples.webshop.sales.events.ProductPriceChanged
import org.springframework.stereotype.Service

/**
 * The three outcomes a decider can have, in one class:
 *
 * - **an event** - the price is different, so `ProductPriceChanged` is appended;
 * - **no event** - the price is already what the command asks for, so nothing happened and nothing is recorded;
 * - **an exception** - there is no such product, so the command is refused.
 *
 * Current state is read straight off the stream: the last event that carries a price *is* the current price.
 * No state class and no evolver are needed for a question this small.
 */
@Service
class ChangeProductPriceDecider : Decider<ChangeProductPrice, ProductEvent> {

    override fun handle(cmd: ChangeProductPrice, events: List<ProductEvent>): ProductPriceChanged? {
        if (events.isEmpty()) {
            throw ProductHasNotBeenAddedException(cmd.id)
        }
        val currentPrice = events.last { it is HasProductPrice } as HasProductPrice

        // Amount is a BigDecimal type, and BigDecimal.equals is scale-sensitive: 100.00 does not equal 100.0.
        // An idempotency check has to compare values, not representations, or re-sending "set the price to
        // 100.0" after "100.00" would append a change event that changes nothing.
        val isPriceTheSame = currentPrice.price.compareTo(cmd.price) == 0

        return if (isPriceTheSame) {
            null
        } else {
            ProductPriceChanged(cmd.id, cmd.price)
        }
    }

    override fun canHandle(cmd: Any): Boolean = cmd is ChangeProductPrice
}
