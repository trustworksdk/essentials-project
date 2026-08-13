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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

/**
 * What happens to an `init { require(...) }` guard on a Kotlin semantic type used as a `@PathVariable`, and what HTTP
 * status an invalid value comes back as. WebMvc; [ValidatingValueClassPathVariableWebFluxTest] mirrors it reactively.
 *
 * [KotlinValueTypeBindingTest] establishes that a `@JvmInline value class` is unboxed in the JVM signature, so Spring
 * binds a plain `String` and no Essentials converter is involved. The natural inference from that is that the value
 * class is never constructed either, and that its `init` guard is therefore silently skipped - an invalid id reaching
 * the application unvalidated. **That inference is wrong**, and the difference matters enough to pin down:
 *
 * - Spring re-boxes the bound `String` into the value class before invoking the handler
 *   (`InvocableHandlerMethod$KotlinDelegate.box`, via `kotlin-reflect`), which calls `constructor-impl` and therefore
 *   runs `init`. Validation happens on every request.
 * - It happens *after* argument resolution, in handler invocation, so the `IllegalArgumentException` is not a binding
 *   failure. Unhandled it becomes **HTTP 500**, not 400.
 * - A non-inline semantic type validates too, but inside [dk.trustworks.essentials.types.spring.web.KotlinValueTypeConverter]
 *   during conversion, so it surfaces as `MethodArgumentTypeMismatchException` and **HTTP 400**.
 *
 * The 500-vs-400 asymmetry between the two shapes is the practical takeaway: a validating value class needs an
 * `@ExceptionHandler(IllegalArgumentException::class)` to answer a malformed id with 400.
 */
@SpringBootTest(classes = [ValidatingValueClassPathVariableTest.TestApplication::class])
@AutoConfigureMockMvc
class ValidatingValueClassPathVariableTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(EssentialsWebMvcConfigurer::class, ValidatingValueTypeController::class)
    class TestApplication

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `a valid value binds through a value class init guard`() {
        mockMvc.perform(get("/validated/orders/{orderId}", "order-4711"))
            .andExpect(status().isOk)
            .andExpect(content().string("order-4711"))
    }

    @Test
    fun `a value class init guard does run on an invalid path variable`() {
        // Not status().isBadRequest: the guard fires during handler invocation rather than argument resolution, so
        // MockMvc rethrows it rather than mapping it. The assertion is that the guard ran at all.
        assertThatThrownBy { mockMvc.perform(get("/validated/orders/{orderId}", "BAD-4711")) }
            .rootCause()
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("KtValidatedOrderId must start with 'order-'")
    }

    @Test
    fun `a non-inline init guard fails during conversion and answers 400`() {
        val result = mockMvc.perform(get("/validated/shipments/{shipmentId}", "BAD-99"))
            .andExpect(status().isBadRequest)
            .andReturn()
        assertThat(result.resolvedException).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }
}
