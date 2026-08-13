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

package dk.trustworks.essentials.types.spring.web

import dk.trustworks.essentials.kotlin.types.*
import org.springframework.core.convert.TypeDescriptor
import org.springframework.core.convert.converter.GenericConverter
import org.springframework.core.convert.converter.GenericConverter.ConvertiblePair
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import kotlin.reflect.full.primaryConstructor

/**
 * [GenericConverter] that converts a `String` path variable or request parameter into a **Kotlin** semantic type -
 * a class implementing one of the `dk.trustworks.essentials.kotlin.types` interfaces:
 *
 * ```kotlin
 * data class Weight(override val value: BigDecimal) : BigDecimalValueType<Weight>
 *
 * @GetMapping("/shipments/by-weight/{weight}")
 * fun byWeight(@PathVariable weight: Weight): Shipment = ...
 * ```
 *
 * This is the Kotlin counterpart to [SingleValueTypeConverter], which covers the *Java*
 * [dk.trustworks.essentials.types.SingleValueType] hierarchy only - a Kotlin semantic type is not a
 * `CharSequenceType`. [EssentialsWebMvcConfigurer]/[EssentialsWebFluxConfigurer] register both.
 *
 * ### What actually needs this converter - and what does not
 * Narrower than it looks, and worth checking against `KotlinValueTypeConverterRequiredTest` before assuming:
 *
 * | Shape | Needs this converter? |
 * |---|---|
 * | `@JvmInline value class` over anything | **No.** Kotlin *unboxes* a value class in every JVM signature, nullable included: `fun byOrderId(orderId: OrderId)` compiles to `byOrderId-GEJpfBY(String)`. Spring only ever sees the underlying type and binds it natively. This converter cannot be reached, and does not need to be |
 * | non-inline class wrapping a `String` | **No.** Spring's own `ObjectToObjectConverter` finds the single `String`-arg constructor |
 * | non-inline class wrapping anything else | **Yes.** There is no `String`-arg constructor for Spring to find. Without this converter the request fails with `ConversionNotSupportedException` - an HTTP **500**, not a 400 |
 *
 * ### Why this class is Kotlin
 * Instantiating goes through `kotlin-reflect`'s [primaryConstructor], the same approach `types-jdbi`'s
 * `StringValueTypeColumnMapper` and the event store's `StringValueTypeAggregateIdSerializer` already use. It also
 * keeps working if a target type ever is a value class, whose only JVM constructor is private plus a synthetic
 * `box-impl`.
 *
 * ### Why registration is conditional
 * `kotlin-reflect` is an **optional** dependency, so a Java-only application will not have it on the classpath and
 * loading this class would fail. Registration goes through [KotlinValueTypeConverterRegistrar], which checks first.
 *
 * ### Scope
 * `@PathVariable` and `@RequestParam` only. Request and response *bodies* are Jackson's job: register
 * `jackson-module-kotlin`'s `KotlinModule` on the web `ObjectMapper` - Essentials' `EssentialTypesJacksonModule`
 * covers the Java hierarchy only and does **not** handle Kotlin types.
 */
class KotlinValueTypeConverter : GenericConverter {

    override fun getConvertibleTypes(): Set<ConvertiblePair> = CONVERTIBLE_TYPES

    override fun convert(source: Any?, sourceType: TypeDescriptor, targetType: TypeDescriptor): Any? {
        if (source == null) return null
        val target = targetType.type
        val text = source.toString()

        val wrappedValue: Any = when {
            StringValueType::class.java.isAssignableFrom(target)         -> text
            LongValueType::class.java.isAssignableFrom(target)           -> text.toLong()
            IntValueType::class.java.isAssignableFrom(target)            -> text.toInt()
            ShortValueType::class.java.isAssignableFrom(target)          -> text.toShort()
            ByteValueType::class.java.isAssignableFrom(target)           -> text.toByte()
            DoubleValueType::class.java.isAssignableFrom(target)         -> text.toDouble()
            FloatValueType::class.java.isAssignableFrom(target)          -> text.toFloat()
            BigDecimalValueType::class.java.isAssignableFrom(target)     -> BigDecimal(text)
            BigIntegerValueType::class.java.isAssignableFrom(target)     -> BigInteger(text)
            BooleanValueType::class.java.isAssignableFrom(target)        -> text.toBooleanStrict()
            InstantValueType::class.java.isAssignableFrom(target)        -> Instant.parse(text)
            LocalDateValueType::class.java.isAssignableFrom(target)      -> LocalDate.parse(text)
            LocalDateTimeValueType::class.java.isAssignableFrom(target)  -> LocalDateTime.parse(text)
            LocalTimeValueType::class.java.isAssignableFrom(target)      -> LocalTime.parse(text)
            OffsetDateTimeValueType::class.java.isAssignableFrom(target) -> OffsetDateTime.parse(text)
            // Matches SingleValueTypeConverter: a ZonedDateTime's "[Europe/Paris]" zone suffix has to survive the URL,
            // so the client encodes it and we decode here. No other temporal type needs this.
            ZonedDateTimeValueType::class.java.isAssignableFrom(target)  ->
                ZonedDateTime.parse(URLDecoder.decode(text, StandardCharsets.UTF_8))

            else                                                        ->
                throw IllegalArgumentException("Cannot convert '$text' to unsupported Kotlin value type '${target.name}'")
        }

        val primaryConstructor = target.kotlin.primaryConstructor
            ?: throw IllegalArgumentException(
                "Kotlin value type '${target.name}' has no primary constructor to convert '$text' with"
            )
        return primaryConstructor.call(wrappedValue)
    }

    companion object {
        /**
         * One pair per interface: the Kotlin value type interfaces share no common supertype - each extends only
         * `Serializable` and `Comparable<SELF>` - so there is nothing narrower to declare.
         */
        private val CONVERTIBLE_TYPES: Set<ConvertiblePair> = setOf(
            ConvertiblePair(String::class.java, StringValueType::class.java),
            ConvertiblePair(String::class.java, LongValueType::class.java),
            ConvertiblePair(String::class.java, IntValueType::class.java),
            ConvertiblePair(String::class.java, ShortValueType::class.java),
            ConvertiblePair(String::class.java, ByteValueType::class.java),
            ConvertiblePair(String::class.java, DoubleValueType::class.java),
            ConvertiblePair(String::class.java, FloatValueType::class.java),
            ConvertiblePair(String::class.java, BigDecimalValueType::class.java),
            ConvertiblePair(String::class.java, BigIntegerValueType::class.java),
            ConvertiblePair(String::class.java, BooleanValueType::class.java),
            ConvertiblePair(String::class.java, InstantValueType::class.java),
            ConvertiblePair(String::class.java, LocalDateValueType::class.java),
            ConvertiblePair(String::class.java, LocalDateTimeValueType::class.java),
            ConvertiblePair(String::class.java, LocalTimeValueType::class.java),
            ConvertiblePair(String::class.java, OffsetDateTimeValueType::class.java),
            ConvertiblePair(String::class.java, ZonedDateTimeValueType::class.java),
        )
    }
}
