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
 * Identifier for a product, and the stream id of the `Products` aggregate type.
 *
 * This is a **Java-style** Essentials semantic type written in Kotlin, not a Kotlin `value class` over
 * [dk.trustworks.essentials.kotlin.types.StringValueType]. That is a deliberate choice for this application, and
 * the reason is on the wire: a Kotlin value class only serializes to a bare scalar when `jackson-module-kotlin` is
 * registered on the mapper doing the writing - without it, it writes `{"value":"..."}` and cannot be read back
 * (`types-spring-web` has tests asserting exactly that). The mapper that persists events is built by the framework
 * from `EssentialsObjectMappers`, so the application does not own its module list. A [CharSequenceType] needs no
 * such module: `types-jackson`/`types-jackson3` serialize it as a scalar, `types-springdata-jpa` has converters
 * for it, and `AggregateIdSerializer.serializerFor` resolves it to `CharSequenceTypeIdSerializer`.
 *
 * Both constructors exist because the framework's reflective lookup may ask for either.
 */
class ProductId : CharSequenceType<ProductId> {
    constructor(value: CharSequence) : super(value)
    constructor(value: String) : super(value)

    companion object {
        @JvmStatic
        fun of(value: CharSequence): ProductId = ProductId(value)

        /**
         * Ids are generated, never taken from user input: an aggregate id reaches SQL through string
         * concatenation in parts of the framework, so [RandomIdGenerator] is the safe source.
         */
        @JvmStatic
        fun random(): ProductId = ProductId(RandomIdGenerator.generate())
    }
}
