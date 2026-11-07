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

package dk.trustworks.essentials.examples.webshop.sales.types

import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator
import dk.trustworks.essentials.types.CharSequenceType

/**
 * Identifier for a shopping basket, and the stream id of the `ShoppingBaskets` aggregate type.
 *
 * The browser generates one of these before the first item is added - a basket exists as soon as the shopper
 * behaves as if it does, and no round trip is needed to find out what it is called.
 *
 * See [ProductId] for why these are Java-style Essentials types rather than Kotlin value classes.
 */
class ShoppingBasketId : CharSequenceType<ShoppingBasketId> {
    constructor(value: CharSequence) : super(value)
    constructor(value: String) : super(value)

    companion object {
        @JvmStatic
        fun of(value: CharSequence): ShoppingBasketId = ShoppingBasketId(value)

        @JvmStatic
        fun random(): ShoppingBasketId = ShoppingBasketId(RandomIdGenerator.generate())
    }
}
