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

import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule
import dk.trustworks.essentials.types.spring.web.EssentialsWebMvcConfigurer
import dk.trustworks.essentials.types.spring.web.SingleValueTypeModelConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The registration story end to end: a Boot application running springdoc registers [SingleValueTypeModelConverter]
 * as a bean, and `/v3/api-docs` then describes the response the same application actually writes. Boot's own web
 * `JsonMapper` writes the response, so the wire side is the real one. There is no Jackson 2 Kotlin module on this
 * classpath, as in a Boot 4 application, which is what makes springdoc mangle the property names.
 */
@SpringBootTest(classes = [SingleValueTypeModelConverterSpringDocTest.TestApplication::class])
@AutoConfigureMockMvc
class SingleValueTypeModelConverterSpringDocTest {

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(EssentialsWebMvcConfigurer::class, OrderDocumentController::class)
    class TestApplication {
        @Bean
        fun essentialTypesJacksonModule() = EssentialTypesJacksonModule()

        @Bean
        fun singleValueTypeModelConverter() = SingleValueTypeModelConverter()
    }

    @RestController
    class OrderDocumentController {
        @GetMapping("/springdoc/orders/{orderId}")
        fun order(@PathVariable orderId: KtOrderId): KtOrderDocument = KtOrderDocument.sample().copy(orderId = orderId)
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val reader: JsonMapper = JsonMapper.builder().build()

    private fun apiDocs(): JsonNode =
        reader.readTree(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn().response.contentAsString)

    @Test
    fun `the document describes the ids and value classes as their values under their Kotlin names`() {
        val schemas = apiDocs().get("components").get("schemas")
        val properties = schemas.get("KtOrderDocument").get("properties")

        assertThat(properties.propertyNames()).noneMatch { it.contains('-') }
        assertThat(schemaOf(properties.get("orderId"))).isEqualTo("string")
        assertThat(schemaOf(properties.get("quantity"))).isEqualTo("integer/int64")
        assertThat(schemaOf(properties.get("contact"))).isEqualTo("string")
        assertThat(schemaOf(properties.get("amount"))).isEqualTo("number")
        assertThat(schemas.propertyNames()).doesNotContain("EmailAddress", "Amount", "KtOrderId", "KtQuantity")
    }

    @Test
    fun `the document agrees with the response the application writes`() {
        val docs = apiDocs()
        val wire = reader.readTree(
            mockMvc.perform(get("/springdoc/orders/{orderId}", "order-4711")).andExpect(status().isOk).andReturn().response.contentAsString
        )

        val schemas = docs.get("components").get("schemas")
        val operation = docs.get("paths").get("/springdoc/orders/{orderId}").get("get")
        val response = operation.get("responses").get("200").get("content").values().first().get("schema")
        assertAgrees(schemas, response, wire, "response")
        assertThat(schemaOf(operation.get("parameters").get(0).get("schema"))).isEqualTo("string")
        assertThat(wire.get("orderId").asString()).isEqualTo("order-4711")
    }
}
