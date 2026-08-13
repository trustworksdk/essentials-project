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

import dk.trustworks.essentials.types.spring.web.EssentialsWebFluxConfigurer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * The reactive half of [ValidatingValueClassPathVariableTest]: an `init { require(...) }` guard on a Kotlin semantic
 * type used as a `@PathVariable` runs on WebFlux too - including on a `suspend` handler - and produces the same
 * 500-vs-400 split between the inline and non-inline shapes.
 *
 * This matters more here than on WebMvc, because the reactive stack is what a Kotlin/WebFlux service actually runs on.
 */
@SpringBootTest(classes = [ValidatingValueClassPathVariableWebFluxTest.ReactiveApplication::class],
                webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = ["spring.main.web-application-type=reactive"])
class ValidatingValueClassPathVariableWebFluxTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(EssentialsWebFluxConfigurer::class, ValidatingValueTypeController::class, SuspendingValidatingController::class)
    class ReactiveApplication

    // WebTestClient is not auto-configured here: spring-webmvc is also on this module's test classpath, so Boot's test
    // support sees a servlet application. Same reason and same workaround as EssentialsWebFluxConfigurerJackson3Test.
    @Value("\${local.server.port}")
    private var port: Int = 0

    private val client: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @Test
    fun `a valid value binds through a value class init guard`() {
        client.get().uri("/validated/orders/order-4711").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("order-4711")
    }

    @Test
    fun `an invalid value class path variable answers 500`() {
        client.get().uri("/validated/orders/BAD-4711").exchange()
            .expectStatus().is5xxServerError
    }

    @Test
    fun `an invalid value class path variable answers 500 on a suspend handler too`() {
        client.get().uri("/validated/suspend/orders/BAD-4711").exchange()
            .expectStatus().is5xxServerError
    }

    @Test
    fun `an invalid non-inline path variable answers 400`() {
        client.get().uri("/validated/shipments/BAD-99").exchange()
            .expectStatus().isBadRequest
    }
}
