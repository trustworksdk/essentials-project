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

package dk.trustworks.essentials.types.spring.web;

import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.types.*;
import dk.trustworks.essentials.types.spring.web.model.*;
import io.swagger.v3.core.converter.*;
import io.swagger.v3.core.util.*;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.*;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link SingleValueTypeModelConverter} makes swagger-core (and so springdoc) publish, in both the OpenAPI 3.0
 * and 3.1 renderings.
 * <p>
 * Two kinds of assertion: the exact schema per type, and an oracle that serialises a populated DTO with the Jackson 3
 * mapper a web application uses ({@link EssentialTypesJacksonModule}) and checks every published property against the
 * JSON actually written, so the document cannot drift from the wire without a test failing.
 */
class SingleValueTypeModelConverterTest {

    private static final JsonMapper WEB_MAPPER = JsonMapper.builder().addModule(new EssentialTypesJacksonModule()).build();

    // Scalar semantic types the shared test model does not have
    public static class Priority extends ShortType<Priority> {
        public Priority(Short value) {
            super(value);
        }
    }

    public static class Level extends ByteType<Level> {
        public Level(Byte value) {
            super(value);
        }
    }

    public static class Serial extends BigIntegerType<Serial> {
        public Serial(BigInteger value) {
            super(value);
        }
    }

    public static class Ratio extends DoubleType<Ratio> {
        public Ratio(Double value) {
            super(value);
        }
    }

    public static class Weight extends FloatType<Weight> {
        public Weight(Float value) {
            super(value);
        }
    }

    /**
     * No scalar serializer exists for a {@link BooleanType}: it is written as <code>{"value":true}</code>, so it must stay an object
     */
    public static class Expedited extends BooleanType<Expedited> {
        public Expedited(Boolean value) {
            super(value);
        }
    }

    public record OrderLine(ProductId productId, Quantity quantity, Amount unitPrice) {
    }

    public record OrderDocument(OrderId orderId,
                                CustomerId customerId,
                                AccountId accountId,
                                Quantity totalQuantity,
                                Priority priority,
                                Level level,
                                Serial serial,
                                Ratio ratio,
                                Weight weight,
                                Amount amount,
                                Percentage discount,
                                EmailAddress contact,
                                CountryCode country,
                                CurrencyCode currency,
                                Money total,
                                DueDate dueDate,
                                LastUpdated lastUpdated,
                                Created created,
                                TimeOfDay deliveryTime,
                                TransactionTime transactionTime,
                                TransferTime transferTime,
                                Expedited expedited,
                                List<OrderId> relatedOrders,
                                Set<ProductId> productIds,
                                Map<ProductId, Quantity> quantities,
                                List<OrderLine> lines,
                                OrderLine firstLine) {
    }

    public record AnnotatedDocument(@Schema(description = "The order this document is about") OrderId orderId) {
    }

    private static OrderDocument sampleDocument() {
        var line = new OrderLine(ProductId.of("product-1"), Quantity.of(2), Amount.of("12.50"));
        return new OrderDocument(OrderId.of(4711),
                                 new CustomerId("customer-1"),
                                 AccountId.of(42),
                                 Quantity.of(7),
                                 new Priority((short) 3),
                                 new Level((byte) 1),
                                 new Serial(new BigInteger("123456789012345678901234567890")),
                                 new Ratio(0.25),
                                 new Weight(1.5f),
                                 Amount.of("99.95"),
                                 Percentage.from("12.5"),
                                 EmailAddress.of("orders@example.com"),
                                 CountryCode.of("DK"),
                                 CurrencyCode.of("DKK"),
                                 Money.of("99.95", "DKK"),
                                 DueDate.of(LocalDate.of(2026, 9, 29)),
                                 LastUpdated.of(Instant.parse("2026-09-29T10:15:30Z")),
                                 Created.of(LocalDateTime.of(2026, 9, 29, 10, 15, 30)),
                                 TimeOfDay.of(LocalTime.of(10, 15, 30)),
                                 TransactionTime.of(ZonedDateTime.parse("2026-09-29T10:15:30+02:00[Europe/Copenhagen]")),
                                 TransferTime.of(OffsetDateTime.parse("2026-09-29T10:15:30+02:00")),
                                 new Expedited(true),
                                 List.of(OrderId.of(1), OrderId.of(2)),
                                 Set.of(ProductId.of("product-1")),
                                 Map.of(ProductId.of("product-1"), Quantity.of(2)),
                                 List.of(line),
                                 line);
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = {false, true})
    void each_scalar_semantic_type_is_published_as_the_schema_of_its_value(boolean openapi31) throws Exception {
        var properties = resolve(OrderDocument.class, openapi31, true).get("OrderDocument").get("properties");

        assertThat(schemaOf(properties.get("orderId"))).isEqualTo("integer/int64");
        assertThat(schemaOf(properties.get("customerId"))).isEqualTo("string");
        assertThat(schemaOf(properties.get("accountId"))).isEqualTo("integer/int64");
        assertThat(schemaOf(properties.get("totalQuantity"))).isEqualTo("integer/int32");
        assertThat(schemaOf(properties.get("priority"))).isEqualTo("integer/int32");
        assertThat(schemaOf(properties.get("level"))).isEqualTo("integer/int32");
        assertThat(schemaOf(properties.get("serial"))).isEqualTo("integer");
        assertThat(schemaOf(properties.get("ratio"))).isEqualTo("number/double");
        assertThat(schemaOf(properties.get("weight"))).isEqualTo("number/float");
        assertThat(schemaOf(properties.get("amount"))).isEqualTo("number");
        assertThat(schemaOf(properties.get("discount"))).isEqualTo("number");
        assertThat(schemaOf(properties.get("contact"))).isEqualTo("string");
        assertThat(schemaOf(properties.get("country"))).isEqualTo("string");
        assertThat(schemaOf(properties.get("currency"))).isEqualTo("string");
        assertThat(schemaOf(properties.get("dueDate"))).isEqualTo("string/date");
        assertThat(schemaOf(properties.get("lastUpdated"))).isEqualTo("string/date-time");
        assertThat(schemaOf(properties.get("created"))).isEqualTo("string/date-time");
        assertThat(schemaOf(properties.get("transactionTime"))).isEqualTo("string/date-time");
        assertThat(schemaOf(properties.get("transferTime"))).isEqualTo("string/date-time");
        // Not what swagger-core does for a plain LocalTime (an object with hour, minute, ...), but what is written
        assertThat(schemaOf(properties.get("deliveryTime"))).isEqualTo("string/partial-time");
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = {false, true})
    void lists_sets_and_maps_of_semantic_types_hold_the_value_schema(boolean openapi31) throws Exception {
        var properties = resolve(OrderDocument.class, openapi31, true).get("OrderDocument").get("properties");

        assertThat(schemaOf(properties.get("relatedOrders"))).isEqualTo("array");
        assertThat(schemaOf(properties.get("relatedOrders").get("items"))).isEqualTo("integer/int64");
        assertThat(schemaOf(properties.get("productIds"))).isEqualTo("array");
        assertThat(properties.get("productIds").get("uniqueItems").asBoolean()).isTrue();
        assertThat(schemaOf(properties.get("productIds").get("items"))).isEqualTo("string");
        // OpenAPI cannot describe a map key; the value schema is what a client gets
        assertThat(schemaOf(properties.get("quantities"))).isEqualTo("object");
        assertThat(schemaOf(properties.get("quantities").get("additionalProperties"))).isEqualTo("integer/int32");
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = {false, true})
    void a_nested_record_stays_a_component_with_collapsed_properties(boolean openapi31) throws Exception {
        var schemas = resolve(OrderDocument.class, openapi31, true);
        var properties = schemas.get("OrderDocument").get("properties");

        assertThat(properties.get("firstLine").get("$ref").asString()).isEqualTo("#/components/schemas/OrderLine");
        assertThat(properties.get("lines").get("items").get("$ref").asString()).isEqualTo("#/components/schemas/OrderLine");
        var line = schemas.get("OrderLine").get("properties");
        assertThat(schemaOf(line.get("productId"))).isEqualTo("string");
        assertThat(schemaOf(line.get("quantity"))).isEqualTo("integer/int32");
        assertThat(schemaOf(line.get("unitPrice"))).isEqualTo("number");
        var money = schemas.get("Money").get("properties");
        assertThat(schemaOf(money.get("amount"))).isEqualTo("number");
        assertThat(schemaOf(money.get("currency"))).isEqualTo("string");
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = {false, true})
    void no_component_is_published_for_a_scalar_semantic_type(boolean openapi31) throws Exception {
        var schemas = resolve(OrderDocument.class, openapi31, true);

        // Expedited is a BooleanType, which really is written as an object
        assertThat(schemas.propertyNames()).containsExactlyInAnyOrder("OrderDocument", "OrderLine", "Money", "Expedited");
        assertThat(schemas.get("Expedited").get("properties").propertyNames()).containsExactly("value");
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = {false, true})
    void the_published_document_agrees_with_the_json_written(boolean openapi31) throws Exception {
        var schemas = resolve(OrderDocument.class, openapi31, true);
        var wire = WEB_MAPPER.readTree(WEB_MAPPER.writeValueAsString(sampleDocument()));

        assertAgrees(schemas, schemas.get("OrderDocument"), wire, "OrderDocument");
    }

    @Test
    void without_the_converter_ids_are_published_as_objects() throws Exception {
        // The defect this converter exists for; if swagger-core ever fixes it on its own this test says so
        var schemas = resolve(OrderDocument.class, false, false);

        assertThat(schemas.get("OrderDocument").get("properties").get("customerId").get("$ref").asString())
                .isEqualTo("#/components/schemas/CustomerId");
        assertThat(schemas.get("CustomerId").get("properties").propertyNames()).contains("value", "empty");
    }

    @ParameterizedTest(name = "openapi31={0}")
    @ValueSource(booleans = {false, true})
    void a_schema_annotation_on_the_property_still_applies(boolean openapi31) throws Exception {
        var orderId = resolve(AnnotatedDocument.class, openapi31, true).get("AnnotatedDocument").get("properties").get("orderId");

        assertThat(schemaOf(orderId)).isEqualTo("integer/int64");
        assertThat(orderId.get("description").asString()).isEqualTo("The order this document is about");
    }

    // ------------------------------------------------------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private static JsonNode resolve(Class<?> type, boolean openapi31, boolean withConverter) throws Exception {
        var converters = new ModelConverters(openapi31);
        if (withConverter) {
            converters.addConverter(new SingleValueTypeModelConverter());
        }
        Map<String, io.swagger.v3.oas.models.media.Schema> schemas = converters.readAll(new AnnotatedType(type));
        var json = openapi31 ? Json31.mapper().writeValueAsString(schemas) : Json.mapper().writeValueAsString(schemas);
        return WEB_MAPPER.readTree(json);
    }

    /**
     * <code>type</code> or <code>type/format</code>. OpenAPI 3.1 may write <code>type</code> as an array.
     */
    private static String schemaOf(JsonNode schema) {
        assertThat(schema).as("schema").isNotNull();
        var typeNode = schema.get("type");
        assertThat(typeNode).as("type of %s", schema).isNotNull();
        var type = typeNode.isArray() ? typeNode.get(0).asString() : typeNode.asString();
        var format = schema.get("format");
        return format == null ? type : type + "/" + format.asString();
    }

    private static void assertAgrees(JsonNode schemas, JsonNode schema, JsonNode wire, String path) {
        if (schema.get("$ref") != null) {
            var ref = schema.get("$ref").asString();
            schema = schemas.get(ref.substring(ref.lastIndexOf('/') + 1));
            assertThat(schema).as("%s: component %s", path, ref).isNotNull();
        }
        if (wire.isObject()) {
            var properties = schema.get("properties");
            if (properties == null) {
                // a map
                assertThat(schema.get("additionalProperties")).as("%s: object schema", path).isNotNull();
                for (var entry : wire.properties()) {
                    assertAgrees(schemas, schema.get("additionalProperties"), entry.getValue(), path + "." + entry.getKey());
                }
                return;
            }
            assertThat(properties.propertyNames()).as("%s: property names", path).containsExactlyInAnyOrderElementsOf(wire.propertyNames());
            for (var entry : wire.properties()) {
                assertAgrees(schemas, properties.get(entry.getKey()), entry.getValue(), path + "." + entry.getKey());
            }
            return;
        }
        var type = schemaOf(schema).split("/")[0];
        if (wire.isArray()) {
            assertThat(type).as("%s", path).isEqualTo("array");
            for (var element : wire.values()) {
                assertAgrees(schemas, schema.get("items"), element, path + "[]");
            }
        } else if (wire.isString()) {
            assertThat(type).as("%s is written as %s", path, wire).isEqualTo("string");
        } else if (wire.isIntegralNumber()) {
            assertThat(type).as("%s is written as %s", path, wire).isIn("integer", "number");
        } else if (wire.isNumber()) {
            assertThat(type).as("%s is written as %s", path, wire).isEqualTo("number");
        } else if (wire.isBoolean()) {
            assertThat(type).as("%s is written as %s", path, wire).isEqualTo("boolean");
        }
    }
}
