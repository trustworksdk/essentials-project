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

package dk.trustworks.essentials.types.spring.web.kotlin

import dk.trustworks.essentials.kotlin.types.StringValueType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Semantic types that reject their own invalid values in an `init` block, used by
 * [ValidatingValueClassPathVariableTest] and [ValidatingValueClassPathVariableWebFluxTest].
 *
 * The `@JvmInline` one is the interesting case: its parameter is *unboxed* in every JVM signature, so it is not
 * obvious that `init` runs at all when Spring binds the path variable. It does - see those tests for by what.
 */

@JvmInline
value class KtValidatedOrderId(override val value: String) : StringValueType<KtValidatedOrderId> {
    init {
        require(value.startsWith("order-")) { "KtValidatedOrderId must start with 'order-': '$value'" }
    }
}

data class KtValidatedShipmentId(override val value: String) : StringValueType<KtValidatedShipmentId> {
    init {
        require(value.startsWith("shipment-")) { "KtValidatedShipmentId must start with 'shipment-': '$value'" }
    }
}

@RestController
class ValidatingValueTypeController {
    @GetMapping("/validated/orders/{orderId}")
    fun byOrderId(@PathVariable orderId: KtValidatedOrderId): String = orderId.value

    @GetMapping("/validated/shipments/{shipmentId}")
    fun byShipmentId(@PathVariable shipmentId: KtValidatedShipmentId): String = shipmentId.value
}

/**
 * A `suspend` handler goes down a different invocation path than a blocking one, so the guard is asserted on it
 * separately. Reactive stack only - WebMvc cannot dispatch to a suspending function.
 */
@RestController
class SuspendingValidatingController {
    @GetMapping("/validated/suspend/orders/{orderId}")
    suspend fun byOrderId(@PathVariable orderId: KtValidatedOrderId): String = orderId.value
}
