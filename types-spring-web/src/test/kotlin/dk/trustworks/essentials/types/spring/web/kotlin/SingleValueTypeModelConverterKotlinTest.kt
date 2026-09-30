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
import dk.trustworks.essentials.types.spring.web.SingleValueTypeModelConverter
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.util.Json
import io.swagger.v3.core.util.Json31
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * [SingleValueTypeModelConverter] on Kotlin DTOs, through swagger-core's `ModelConverters` alone - no springdoc, so
 * none of springdoc's own Kotlin converters, and no Jackson 2 Kotlin module: the situation of a Boot 4 application.
 */
class SingleValueTypeModelConverterKotlinTest {

    private val webMapper: JsonMapper = JsonMapper.builder()
        .addModule(EssentialTypesJacksonModule())
        .addModule(KotlinModule.Builder().build())
        .build()

    private fun resolve(openapi31: Boolean, withConverter: Boolean = true): JsonNode {
        val converters = ModelConverters(openapi31)
        if (withConverter) {
            converters.addConverter(SingleValueTypeModelConverter())
        }
        val schemas = converters.readAll(AnnotatedType(KtOrderDocument::class.java))
        val json = if (openapi31) Json31.mapper().writeValueAsString(schemas) else Json.mapper().writeValueAsString(schemas)
        return webMapper.readTree(json)
    }

    @Test
    fun `without the converter a value-class property is published under its mangled getter name`() {
        val properties = resolve(openapi31 = false, withConverter = false).get("KtOrderDocument").get("properties")

        assertThat(properties.propertyNames()).anyMatch { it.startsWith("orderId-") }
        assertThat(properties.propertyNames()).doesNotContain("orderId")
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = [false, true])
    fun `value-class properties are published under their Kotlin names as the schema of their value`(openapi31: Boolean) {
        val schemas = resolve(openapi31)
        val properties = schemas.get("KtOrderDocument").get("properties")

        assertThat(properties.propertyNames()).noneMatch { it.contains('-') }
        assertThat(schemaOf(properties.get("orderId"))).isEqualTo("string")
        assertThat(schemaOf(properties.get("quantity"))).isEqualTo("integer/int64")
        assertThat(schemaOf(properties.get("price"))).isEqualTo("number")
        assertThat(schemaOf(properties.get("dueDate"))).isEqualTo("string/date")
        assertThat(schemaOf(properties.get("plainId"))).isEqualTo("string")
        assertThat(schemaOf(properties.get("optionalQuantity"))).isEqualTo("integer/int64")
        assertThat(schemaOf(properties.get("expedited"))).isEqualTo("boolean")
        assertThat(schemaOf(properties.get("related").get("items"))).isEqualTo("string")
        assertThat(schemaOf(properties.get("quantities").get("additionalProperties"))).isEqualTo("integer/int64")
        assertThat(schemaOf(properties.get("contact"))).isEqualTo("string")
        assertThat(schemaOf(properties.get("amount"))).isEqualTo("number")
        val line = schemas.get("KtOrderLine").get("properties")
        assertThat(line.propertyNames()).containsExactlyInAnyOrder("orderId", "quantity")
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = [false, true])
    fun `required entries follow the renamed properties`(openapi31: Boolean) {
        val required = resolve(openapi31).get("KtOrderDocument").get("required")

        // swagger-core marks nothing required for a plain data class; if that changes, the names must still be the real ones
        required?.values()?.forEach { assertThat(it.asString()).doesNotContain("-") }
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = [false, true])
    fun `the published document agrees with the json written`(openapi31: Boolean) {
        val schemas = resolve(openapi31)
        val wire = webMapper.readTree(webMapper.writeValueAsString(KtOrderDocument.sample()))

        assertAgrees(schemas, schemas.get("KtOrderDocument"), wire, "KtOrderDocument")
    }
}
