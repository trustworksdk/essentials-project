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

import dk.trustworks.essentials.types.Amount
import dk.trustworks.essentials.types.EmailAddress
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat

// ---------------------------------------------------------------------------------------------------------------
// The DTOs the SingleValueTypeModelConverter tests publish. Every property of a value-class type has a mangled JVM
// getter (`getOrderId-<hash>`), which is what springdoc names the property after unless the converter renames it.
// ---------------------------------------------------------------------------------------------------------------

/** A value class that implements no Essentials interface: jackson-module-kotlin still writes it as the bare scalar. */
@JvmInline
value class PlainId(val value: String)

data class KtOrderLine(val orderId: KtOrderId, val quantity: KtQuantity)

data class KtOrderDocument(
    val orderId: KtOrderId,
    val quantity: KtQuantity,
    val price: KtPrice,
    val dueDate: KtDueDate,
    val plainId: PlainId,
    val optionalQuantity: KtQuantity?,
    val expedited: KtExpedited,
    val related: List<KtOrderId>,
    val quantities: Map<String, KtQuantity>,
    val shipmentId: KtShipmentId,
    val contact: EmailAddress,
    val amount: Amount,
    val firstLine: KtOrderLine,
    val lines: List<KtOrderLine>
) {
    companion object {
        fun sample(): KtOrderDocument {
            val line = KtOrderLine(KtOrderId("order-1"), KtQuantity(2))
            return KtOrderDocument(
                orderId = KtOrderId("order-1"),
                quantity = KtQuantity(7),
                price = KtPrice(BigDecimal("12.50")),
                dueDate = KtDueDate(LocalDate.of(2026, 9, 29)),
                plainId = PlainId("plain-1"),
                optionalQuantity = KtQuantity(3),
                expedited = KtExpedited(true),
                related = listOf(KtOrderId("order-2")),
                quantities = mapOf("order-2" to KtQuantity(1)),
                shipmentId = KtShipmentId("shipment-1"),
                contact = EmailAddress.of("orders@example.com"),
                amount = Amount.of("99.95"),
                firstLine = line,
                lines = listOf(line)
            )
        }
    }
}

/** `type`, or `type/format`. OpenAPI 3.1 may write `type` as an array. */
fun schemaOf(schema: JsonNode?): String {
    assertThat(schema).`as`("schema").isNotNull
    val typeNode = schema!!.get("type")
    assertThat(typeNode).`as`("type of %s", schema).isNotNull
    val type = if (typeNode.isArray) typeNode.get(0).asString() else typeNode.asString()
    val format = schema.get("format")
    return if (format == null) type else type + "/" + format.asString()
}

/**
 * Walks the JSON actually written next to the published schema: property names must match exactly and each value's
 * JSON kind must be the one its schema declares.
 */
fun assertAgrees(schemas: JsonNode, schemaOrRef: JsonNode?, wire: JsonNode, path: String) {
    assertThat(schemaOrRef).`as`("%s: schema", path).isNotNull
    var schema: JsonNode = schemaOrRef!!
    schema.get("\$ref")?.let { ref ->
        val name = ref.asString().substringAfterLast('/')
        schema = schemas.get(name) ?: throw AssertionError("$path: no component $name")
    }
    when {
        wire.isObject -> {
            val properties = schema.get("properties")
            if (properties == null) {
                val values = schema.get("additionalProperties")
                assertThat(values).`as`("%s: map schema", path).isNotNull
                wire.properties().forEach { (key, value) -> assertAgrees(schemas, values, value, "$path.$key") }
            } else {
                assertThat(properties.propertyNames()).`as`("%s: property names", path)
                    .containsExactlyInAnyOrderElementsOf(wire.propertyNames())
                wire.properties().forEach { (key, value) -> assertAgrees(schemas, properties.get(key), value, "$path.$key") }
            }
        }
        wire.isArray -> {
            assertThat(schemaOf(schema)).`as`(path).isEqualTo("array")
            wire.values().forEach { assertAgrees(schemas, schema.get("items"), it, "$path[]") }
        }
        wire.isString -> assertThat(schemaOf(schema).substringBefore('/')).`as`("%s is written as %s", path, wire).isEqualTo("string")
        wire.isIntegralNumber -> assertThat(schemaOf(schema).substringBefore('/')).`as`("%s is written as %s", path, wire).isIn("integer", "number")
        wire.isNumber -> assertThat(schemaOf(schema).substringBefore('/')).`as`("%s is written as %s", path, wire).isEqualTo("number")
        wire.isBoolean -> assertThat(schemaOf(schema).substringBefore('/')).`as`("%s is written as %s", path, wire).isEqualTo("boolean")
    }
}
