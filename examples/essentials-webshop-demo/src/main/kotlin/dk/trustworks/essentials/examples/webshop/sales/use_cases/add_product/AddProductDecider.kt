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

package dk.trustworks.essentials.examples.webshop.sales.use_cases.add_product

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider
import dk.trustworks.essentials.examples.webshop.sales.events.ProductAdded
import dk.trustworks.essentials.examples.webshop.sales.events.ProductEvent
import org.springframework.stereotype.Service

/**
 * Command plus past events equals event.
 *
 * The decider is a pure function: it reads the stream it is handed, decides, and returns. It does not load
 * anything, does not write anything, and has no dependencies to mock - which is why its tests need neither a
 * database nor Spring. Loading the stream, appending the event and owning the transaction all happen in the
 * adapter the command bus runs this inside.
 *
 * Returning `null` means "already done": adding the same product twice is not an error, it is a no-op. That is
 * the idempotency rule the whole write side leans on, because a command can always arrive twice.
 */
@Service
class AddProductDecider : Decider<AddProduct, ProductEvent> {

    override fun handle(cmd: AddProduct, events: List<ProductEvent>): ProductAdded? {
        if (events.isNotEmpty()) {
            return null
        }
        return ProductAdded(cmd.id, cmd.name, cmd.price)
    }

    override fun canHandle(cmd: Any): Boolean = cmd is AddProduct
}
