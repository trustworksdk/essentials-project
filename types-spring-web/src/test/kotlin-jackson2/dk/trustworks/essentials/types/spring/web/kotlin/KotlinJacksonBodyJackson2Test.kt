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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * The Jackson 2 twin of [KotlinJacksonBodyJackson3Test]: `EssentialTypesJacksonModule` covers the *Java*
 * `SingleValueType` hierarchy, and Kotlin semantic types need `jackson-module-kotlin` on both flavours.
 *
 * Running the same assertions on both majors is what makes the boundary a property of Essentials rather than an
 * accident of one Jackson version.
 */
@EnabledIfSystemProperty(named = "essentials.jackson.flavor", matches = "jackson2")
class KotlinJacksonBodyJackson2Test {

    private fun essentialsOnlyMapper(): ObjectMapper =
        ObjectMapper().registerModule(EssentialTypesJacksonModule())

    private fun essentialsPlusKotlinMapper(): ObjectMapper =
        ObjectMapper()
            .registerModule(EssentialTypesJacksonModule())
            .registerModule(KotlinModule.Builder().build())

    @Test
    fun `without jackson-module-kotlin a value class writes the WRONG wire shape`() {
        val mapper = essentialsOnlyMapper()

        // Silent, as on Jackson 3: an object instead of the bare scalar the semantic type's wire contract calls for.
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
