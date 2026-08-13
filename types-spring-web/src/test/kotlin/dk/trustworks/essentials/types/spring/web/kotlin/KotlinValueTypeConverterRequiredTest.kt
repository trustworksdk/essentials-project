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

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The negative half of [KotlinValueTypeBindingTest]: the same controller, with `EssentialsWebMvcConfigurer`
 * deliberately **not** imported. It pins down how narrow the converter's actual job is, because two of the three
 * shapes below need nothing from Essentials at all:
 *
 * | Shape | Binds without the configurer? | Why |
 * |---|---|---|
 * | `@JvmInline value class` over anything | **yes** | Kotlin unboxes it in the JVM signature; Spring only ever sees `String`/`Long`/… |
 * | non-inline class wrapping a `String` | **yes** | Spring's own `ObjectToObjectConverter` finds the single `String`-arg constructor |
 * | non-inline class wrapping anything else | **no** | no `String`-arg constructor to find - this is the gap `KotlinValueTypeConverter` fills |
 *
 * Keeping all three asserted is what stops the module's documentation over-claiming, which is exactly how the
 * Kotlin support got mis-described in the first place.
 */
@SpringBootTest(classes = [KotlinValueTypeConverterRequiredTest.UnconfiguredApplication::class])
@AutoConfigureMockMvc
class KotlinValueTypeConverterRequiredTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(KotlinValueTypeController::class)
    class UnconfiguredApplication

    @Autowired
    private lateinit var mockMvc: MockMvc

    // The failure is a 500, not a 400: with no converter registered at all Spring raises
    // ConversionNotSupportedException, which it classes as a server misconfiguration rather than bad client input.
    // Worth knowing when diagnosing this in the wild - a forgotten @Import shows up as an internal server error on
    // a perfectly well-formed request.

    @Test
    fun `a data class over BigDecimal does not bind without the configurer`() {
        mockMvc.perform(get("/kt/shipments/by-weight/{weight}", "12.750"))
            .andExpect(status().isInternalServerError)
    }

    @Test
    fun `a data class over ZonedDateTime does not bind without the configurer`() {
        val encoded = URLEncoder.encode("2026-08-10T10:30:00Z[UTC]", StandardCharsets.UTF_8)
        mockMvc.perform(get("/kt/shipments/by-shipped-at/{shippedAt}", encoded))
            .andExpect(status().isInternalServerError)
    }

    @Test
    fun `a data class over String binds anyway, via Spring's own ObjectToObjectConverter`() {
        // Not something Essentials provides: Spring finds the single String-arg constructor by itself. Asserted so
        // the converter is not credited with work it does not do.
        mockMvc.perform(get("/kt/shipments/{shipmentId}", "shipment-99"))
            .andExpect(status().isOk)
    }

    @Test
    fun `a value class semantic type binds anyway, because Kotlin erased it`() {
        mockMvc.perform(get("/kt/orders/{orderId}", "order-4711"))
            .andExpect(status().isOk)
    }
}
