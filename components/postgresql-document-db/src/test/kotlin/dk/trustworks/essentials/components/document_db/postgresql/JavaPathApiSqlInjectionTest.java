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

package dk.trustworks.essentials.components.document_db.postgresql;

import dk.trustworks.essentials.components.document_db.*;
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import kotlin.jvm.JvmClassMappingKt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The Java-friendly path API is the only surface where an identifier position in the generated SQL can be reached
 * with a runtime {@link String} - the Kotlin API uses {@code KProperty1} references, which are fixed at compile time.
 * Every one of those entry points must reject anything that isn't a plain JSON path before it reaches SQL assembly.
 */
class JavaPathApiSqlInjectionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "name'); DROP TABLE java_products; --",
            "name' OR '1'='1",
            "city'||(SELECT version())||'",
            "address.city'; DELETE FROM java_products; --",
            "name\\'",
            "na me",
            "name-with-dash",
            "data->>'name'",
            "*"
    })
    void jsonPathPropertyRejectsInjection(String payload) {
        assertThatThrownBy(() -> new JsonPathProperty<>(payload)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name'); DROP TABLE java_products; --",
            "name' OR '1'='1",
            "city'||(SELECT version())||'",
            "data->>'name'",
            "*"
    })
    void pathPropertyHelperRejectsInjection(String payload) {
        assertThatThrownBy(() -> QueryKt.pathProperty(payload)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name'); DROP TABLE java_products; --",
            "name' OR '1'='1",
            "city'||(SELECT version())||'",
            "data->>'name'",
            "*"
    })
    void indexFromPathsRejectsInjectedPath(String payload) {
        assertThatThrownBy(() -> Index.fromPaths("idx_ok", payload)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "idx'); DROP TABLE java_products; --",
            "idx ok",
            "idx-ok",
            "*"
    })
    void indexFromPathsRejectsInjectedIndexName(String payload) {
        assertThatThrownBy(() -> Index.fromPaths(payload, "name")).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name'); DROP TABLE java_products; --",
            "name' OR '1'='1",
            "city'||(SELECT version())||'",
            "data->>'name'"
    })
    void conditionPathOverloadsRejectInjection(String payload) {
        var serializer = mock(JSONSerializer.class);

        assertThatThrownBy(() -> new Condition<>(serializer).eq(payload, "v")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Condition<>(serializer).eq(payload, "v", DbType.TEXT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Condition<>(serializer).lt(payload, 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Condition<>(serializer).lte(payload, 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Condition<>(serializer).gt(payload, 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Condition<>(serializer).gte(payload, 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Condition<>(serializer).like(payload, "v")).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name'); DROP TABLE java_products; --",
            "name' OR '1'='1",
            "city'||(SELECT version())||'",
            "data->>'name'"
    })
    @SuppressWarnings("unchecked")
    void queryBuilderPathOrderByRejectsInjection(String payload) {
        var configuration = EntityConfiguration.Companion.<JavaInteropApiTest.JavaProduct, String>configureEntity(
                JvmClassMappingKt.getKotlinClass(JavaInteropApiTest.JavaProduct.class));
        var repository = (DocumentDbRepository<JavaInteropApiTest.JavaProduct, String>) mock(DocumentDbRepository.class);

        assertThatThrownBy(() -> new QueryBuilder<>(configuration, repository).orderBy(payload, QueryBuilder.Order.ASC))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new QueryBuilder<>(configuration, repository).orderBy(payload, DbType.TEXT, QueryBuilder.Order.ASC))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name'); DROP TABLE java_products; --",
            "name' OR '1'='1",
            "data->>'name'"
    })
    @SuppressWarnings("unchecked")
    void addIndexByPathsRejectsInjection(String payload) {
        var repository = (DocumentDbRepository<JavaInteropApiTest.JavaProduct, String>) mock(DocumentDbRepository.class,
                                                                                            org.mockito.Mockito.CALLS_REAL_METHODS);

        assertThatThrownBy(() -> repository.addIndexByPaths("idx_ok", payload)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> repository.addIndexByPaths(payload, "name")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aLegitimatePathIsStillAccepted() {
        var property = new JsonPathProperty<>("contact.address.city");

        assertThat(property.toJSONValueArrowPath()).isEqualTo("data->'contact'->'address'->>'city'");
        assertThat(property.name()).isEqualTo("contact_address_city");
    }
}
