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

import dk.trustworks.essentials.kotlin.types.BigDecimalValueType
import dk.trustworks.essentials.kotlin.types.BooleanValueType
import dk.trustworks.essentials.kotlin.types.LocalDateValueType
import dk.trustworks.essentials.kotlin.types.LongValueType
import dk.trustworks.essentials.kotlin.types.StringValueType
import dk.trustworks.essentials.kotlin.types.ZonedDateTimeValueType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZonedDateTime

// ---------------------------------------------------------------------------------------------------------------
// @JvmInline value classes - the idiomatic Kotlin id shape.
//
// These never reach Spring's ConversionService as themselves: Kotlin *unboxes* a value class in a JVM method
// signature, so `fun byOrderId(orderId: KtOrderId)` compiles to `byOrderId-GEJpfBY(String)`. Spring binds the
// underlying String/Long/BigDecimal/… directly and KotlinValueTypeConverter is never consulted. See
// KotlinValueTypeBindingTest for what that does and does not buy you.
// ---------------------------------------------------------------------------------------------------------------

@JvmInline
value class KtOrderId(override val value: String) : StringValueType<KtOrderId>

@JvmInline
value class KtQuantity(override val value: Long) : LongValueType<KtQuantity>

@JvmInline
value class KtPrice(override val value: BigDecimal) : BigDecimalValueType<KtPrice>

@JvmInline
value class KtExpedited(override val value: Boolean) : BooleanValueType<KtExpedited>

@JvmInline
value class KtDueDate(override val value: LocalDate) : LocalDateValueType<KtDueDate>

// ---------------------------------------------------------------------------------------------------------------
// Non-inline Kotlin semantic types. No unboxing, so these DO survive into the JVM signature and are what
// KotlinValueTypeConverter exists for.
// ---------------------------------------------------------------------------------------------------------------

data class KtShipmentId(override val value: String) : StringValueType<KtShipmentId>

data class KtWeight(override val value: BigDecimal) : BigDecimalValueType<KtWeight>

data class KtShippedAt(override val value: ZonedDateTime) : ZonedDateTimeValueType<KtShippedAt>
