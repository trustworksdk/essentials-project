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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * What `EssentialTypesJacksonModule` does and does not cover for **Kotlin** types, on the Jackson 3 flavour.
 *
 * This boundary was undocumented, and an agent working from the docs concluded the module covered
 * `dk.trustworks.essentials.kotlin.types.StringValueType` because nothing said otherwise. It does not: the module
 * registers serializers for the *Java* hierarchy - `CharSequenceType`, `NumberType`, `Money`,
 * `JSR310SingleValueType` - and Kotlin semantic types are not part of it. `jackson-module-kotlin` is what handles
 * them, and it is the consumer's job to register it.
 *
 * @see KotlinJacksonBodyJackson2Test the same assertions on the Jackson 2 flavour
 */
@EnabledIfSystemProperty(named = "essentials.jackson.flavor", matches = "jackson3")
class KotlinJacksonBodyJackson3Test {

    private fun essentialsOnlyMapper(): JsonMapper =
        JsonMapper.builder().addModule(EssentialTypesJacksonModule()).build()

    private fun essentialsPlusKotlinMapper(): JsonMapper =
        JsonMapper.builder()
            .addModule(EssentialTypesJacksonModule())
            .addModule(KotlinModule.Builder().build())
            .build()

    @Test
    fun `without jackson-module-kotlin a value class writes the WRONG wire shape`() {
        val mapper = essentialsOnlyMapper()

        // This is the damaging half, and it fails silently: Jackson sees an ordinary bean with a `value` property
        // and writes an object, where the semantic type's wire contract is the bare scalar. Nothing throws. A
        // service that persists or publishes this has changed its wire format without any error to notice.
        assertThat(mapper.writeValueAsString(KtOrderId("order-4711")))
            .isEqualTo("""{"value":"order-4711"}""")
    }

    @Test
    fun `without jackson-module-kotlin a value class cannot be read back from its correct wire shape`() {
        val mapper = essentialsOnlyMapper()

        assertThatThrownBy { mapper.readValue(""""order-4711"""", KtOrderId::class.java) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `with jackson-module-kotlin a value class writes the scalar and round-trips`() {
        val mapper = essentialsPlusKotlinMapper()
        val orderId = KtOrderId("order-4711")

        val json = mapper.writeValueAsString(orderId)

        assertThat(json).isEqualTo(""""order-4711"""")
        assertThat(mapper.readValue(json, KtOrderId::class.java)).isEqualTo(orderId)
    }

    @Test
    fun `a non-inline semantic type round-trips once jackson-module-kotlin is registered`() {
        val mapper = essentialsPlusKotlinMapper()
        val shipmentId = KtShipmentId("shipment-99")

        val json = mapper.writeValueAsString(shipmentId)
        assertThat(mapper.readValue(json, KtShipmentId::class.java)).isEqualTo(shipmentId)
    }
}
