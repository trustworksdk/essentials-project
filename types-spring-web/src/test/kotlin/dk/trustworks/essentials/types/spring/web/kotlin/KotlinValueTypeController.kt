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

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Every endpoint takes a Kotlin semantic type directly and echoes its unwrapped `value` back as plain text.
 *
 * Returning `String` rather than the semantic type keeps the assertions about **binding** only - what happened on
 * the way in - with no Jackson involvement on the way out.
 */
@RestController
class KotlinValueTypeController {

    // --- @JvmInline value classes: erased to their underlying type, bound by Spring's built-in converters --------

    @GetMapping("/kt/orders/{orderId}")
    fun byOrderId(@PathVariable orderId: KtOrderId): String = orderId.value

    @GetMapping("/kt/orders/by-quantity/{quantity}")
    fun byQuantity(@PathVariable quantity: KtQuantity): String = quantity.value.toString()

    @GetMapping("/kt/orders/by-price/{price}")
    fun byPrice(@PathVariable price: KtPrice): String = price.value.toPlainString()

    @GetMapping("/kt/orders/by-due-date/{dueDate}")
    fun byDueDate(@PathVariable dueDate: KtDueDate): String = dueDate.value.toString()

    @GetMapping("/kt/orders")
    fun byExpeditedRequestParam(@RequestParam expedited: KtExpedited): String = expedited.value.toString()

    // --- Non-inline semantic types: these reach the ConversionService, and need KotlinValueTypeConverter ---------

    @GetMapping("/kt/shipments/{shipmentId}")
    fun byShipmentId(@PathVariable shipmentId: KtShipmentId): String = shipmentId.value

    @GetMapping("/kt/shipments/by-weight/{weight}")
    fun byWeight(@PathVariable weight: KtWeight): String = weight.value.toPlainString()

    @GetMapping("/kt/shipments/by-shipped-at/{shippedAt}")
    fun byShippedAt(@PathVariable shippedAt: KtShippedAt): String = shippedAt.value.toString()
}
