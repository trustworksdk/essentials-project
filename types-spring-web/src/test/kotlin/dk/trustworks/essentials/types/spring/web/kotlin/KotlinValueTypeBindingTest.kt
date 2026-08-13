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

import dk.trustworks.essentials.types.spring.web.EssentialsWebMvcConfigurer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * How Kotlin semantic types bind as `@PathVariable`/`@RequestParam`, and where
 * `dk.trustworks.essentials.types.spring.web.KotlinValueTypeConverter` is - and is not - what makes it work.
 *
 * The distinction is not obvious and is easy to get backwards, so it is asserted here rather than assumed:
 *
 * - A **`@JvmInline value class`** is *unboxed* by the Kotlin compiler in every JVM method signature, including a
 *   nullable one. `fun byOrderId(orderId: KtOrderId)` compiles to `byOrderId-GEJpfBY(String)` (check with
 *   `javap -p`). Spring therefore sees a `String` parameter, binds it with its own built-in converter, and Kotlin
 *   re-wraps it at the call boundary. No Essentials converter is involved, and none *can* be.
 * - A **non-inline** semantic type - a `data class` implementing the same interface - survives into the signature
 *   as itself. That is the case the converter exists for, and without it those endpoints return 400.
 *
 * @see KotlinValueTypeConverterRequiredTest for the negative half of that second claim
 */
@SpringBootTest(classes = [KotlinValueTypeBindingTest.TestApplication::class])
@AutoConfigureMockMvc
class KotlinValueTypeBindingTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(EssentialsWebMvcConfigurer::class, KotlinValueTypeController::class)
    class TestApplication

    @Autowired
    private lateinit var mockMvc: MockMvc

    // --- @JvmInline value classes -------------------------------------------------------------------------------

    @Test
    fun `a value class over String binds`() {
        val orderId = KtOrderId("order-4711")
        mockMvc.perform(get("/kt/orders/{orderId}", orderId.value))
            .andExpect(status().isOk)
            .andExpect(content().string("order-4711"))
    }

    @Test
    fun `a value class over Long binds`() {
        mockMvc.perform(get("/kt/orders/by-quantity/{quantity}", "42"))
            .andExpect(status().isOk)
            .andExpect(content().string("42"))
    }

    @Test
    fun `a value class over BigDecimal binds`() {
        mockMvc.perform(get("/kt/orders/by-price/{price}", BigDecimal("123.456").toPlainString()))
            .andExpect(status().isOk)
            .andExpect(content().string("123.456"))
    }

    @Test
    fun `a value class over LocalDate binds`() {
        mockMvc.perform(get("/kt/orders/by-due-date/{dueDate}", LocalDate.of(2026, 8, 10).toString()))
            .andExpect(status().isOk)
            .andExpect(content().string("2026-08-10"))
    }

    @Test
    fun `a value class over Boolean binds as a request param`() {
        mockMvc.perform(get("/kt/orders").param("expedited", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string("true"))
    }

    // --- Non-inline semantic types, via KotlinValueTypeConverter ------------------------------------------------

    @Test
    fun `a data class over String binds through the converter`() {
        mockMvc.perform(get("/kt/shipments/{shipmentId}", "shipment-99"))
            .andExpect(status().isOk)
            .andExpect(content().string("shipment-99"))
    }

    @Test
    fun `a data class over BigDecimal binds through the converter`() {
        mockMvc.perform(get("/kt/shipments/by-weight/{weight}", "12.750"))
            .andExpect(status().isOk)
            .andExpect(content().string("12.750"))
    }

    @Test
    fun `a data class over ZonedDateTime binds through the converter when URL-encoded`() {
        // Same contract as the Java ZonedDateTimeType: the "[UTC]" zone suffix has characters that cannot travel a
        // URL path raw, so the client encodes and the converter decodes.
        //
        // A region zone id such as "Europe/Paris" does not work even so, and that is not the converter's doing:
        // its encoded slash is rejected by the servlet container's path handling before conversion is reached.
        // Region ids have to travel as a request param instead.
        val shippedAt = ZonedDateTime.parse("2026-08-10T10:30:00Z[UTC]")
        val encoded = URLEncoder.encode(shippedAt.toString(), StandardCharsets.UTF_8)
        mockMvc.perform(get("/kt/shipments/by-shipped-at/{shippedAt}", encoded))
            .andExpect(status().isOk)
            .andExpect(content().string(shippedAt.toString()))
    }
}
