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

package dk.trustworks.essentials.examples.webshop.sales.routing

import dk.trustworks.essentials.examples.webshop.sales.types.ProductId

/**
 * Marks a command as addressing a `Product`, and guarantees it can say which one.
 *
 * It lives in `routing/` because that is its only job: giving every product command a common way to expose the
 * aggregate id, so dispatch does not need to know each concrete type. The aggregate-type configuration's
 * `commandAggregateIdResolver` casts to this interface, and `HandlesCommandsThatInheritsFromCommandType` uses it
 * to decide which deciders belong to the `Products` aggregate type.
 *
 * It is **not** a sealed interface, deliberately. A Kotlin sealed hierarchy requires every implementation in the
 * same package, and commands live in their own slice packages - one command per slice. The events are sealed
 * (see `events/ProductEvent`), because they all share one package anyway and the evolvers benefit from
 * exhaustive `when`.
 */
interface ProductCommand {
    val id: ProductId
}
