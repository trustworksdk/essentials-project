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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AggregateIdSerializerTest {

    @Test
    void serializerFor_a_String_id() {
        var serializer = AggregateIdSerializer.serializerFor(String.class);

        assertThat(serializer).isInstanceOf(AggregateIdSerializer.StringIdSerializer.class);
        assertThat(serializer.deserialize(serializer.serialize("order-1"))).isEqualTo("order-1");
    }

    @Test
    void serializerFor_a_UUID_id() {
        var serializer = AggregateIdSerializer.serializerFor(UUID.class);
        var id         = UUID.randomUUID();

        assertThat(serializer).isInstanceOf(AggregateIdSerializer.UUIDIdSerializer.class);
        assertThat(serializer.deserialize(serializer.serialize(id))).isEqualTo(id);
    }

    @Test
    void serializerFor_a_CharSequenceType_id() {
        var serializer = AggregateIdSerializer.serializerFor(OrderId.class);
        var id         = OrderId.of("order-1");

        assertThat(serializer).isInstanceOf(AggregateIdSerializer.CharSequenceTypeIdSerializer.class);
        assertThat(serializer.aggregateIdType()).isEqualTo(OrderId.class);
        assertThat(serializer.deserialize(serializer.serialize(id))).isEqualTo(id);
    }

    /**
     * The Kotlin ids live in src/test/kotlin, which is compiled after the Java tests, so they are looked up by name
     */
    @Test
    void serializerFor_a_Kotlin_StringValueType_id() throws ClassNotFoundException {
        var kotlinCustomerId = Class.forName(getClass().getPackageName() + ".KotlinCustomerId");
        var serializer       = AggregateIdSerializer.serializerFor(kotlinCustomerId);

        assertThat(serializer).isInstanceOf(StringValueTypeAggregateIdSerializer.class);
        assertThat(serializer.aggregateIdType()).isEqualTo(kotlinCustomerId);
        var id = serializer.deserialize("customer-1");
        assertThat(id).isInstanceOf(kotlinCustomerId);
        assertThat(serializer.serialize(id)).isEqualTo("customer-1");
        assertThat(serializer.deserialize(serializer.serialize(id))).isEqualTo(id);
    }

    @Test
    void serializerFor_a_Kotlin_value_class_id() throws ClassNotFoundException {
        var kotlinOrderId = Class.forName(getClass().getPackageName() + ".KotlinOrderId");
        var serializer    = AggregateIdSerializer.serializerFor(kotlinOrderId);

        var id = serializer.deserialize("order-1");

        assertThat(serializer).isInstanceOf(StringValueTypeAggregateIdSerializer.class);
        assertThat(id).isInstanceOf(kotlinOrderId);
        assertThat(serializer.serialize(id)).isEqualTo("order-1");
    }

    @Test
    void serializerFor_an_unsupported_id_names_the_supported_types_and_the_Kotlin_serializer() {
        assertThatThrownBy(() -> AggregateIdSerializer.serializerFor(UnsupportedId.class))
                .isInstanceOf(EventStoreException.class)
                .hasMessageContaining(UnsupportedId.class.getName())
                .hasMessageContaining("dk.trustworks.essentials.kotlin.types.StringValueType")
                .hasMessageContaining(StringValueTypeAggregateIdSerializer.class.getName());
    }

    /**
     * kotlin-stdlib and kotlin-reflect are {@code provided}: a Java application has neither, and the Java id types
     * must resolve exactly as before without touching them
     */
    @Test
    void serializerFor_resolves_the_Java_id_types_on_a_classpath_without_Kotlin() throws Exception {
        try (var javaOnly = javaOnlyClassLoader()) {
            assertThatThrownBy(() -> javaOnly.loadClass("kotlin.jvm.internal.Intrinsics"))
                    .isInstanceOf(ClassNotFoundException.class);
            var serializerFor = javaOnly.loadClass(AggregateIdSerializer.class.getName())
                                        .getMethod("serializerFor", Class.class);

            assertThat(serializerFor.invoke(null, String.class).getClass().getName())
                    .isEqualTo(AggregateIdSerializer.StringIdSerializer.class.getName());
            assertThat(serializerFor.invoke(null, UUID.class).getClass().getName())
                    .isEqualTo(AggregateIdSerializer.UUIDIdSerializer.class.getName());
            assertThat(serializerFor.invoke(null, javaOnly.loadClass(OrderId.class.getName())).getClass().getName())
                    .isEqualTo(AggregateIdSerializer.CharSequenceTypeIdSerializer.class.getName());
            assertThatThrownBy(() -> serializerFor.invoke(null, javaOnly.loadClass(UnsupportedId.class.getName())))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause()
                    .satisfies(cause -> assertThat(cause.getClass().getName()).isEqualTo(EventStoreException.class.getName()))
                    .hasMessageContaining(UnsupportedId.class.getName());
        }
    }

    private static URLClassLoader javaOnlyClassLoader() {
        var classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var urls = Arrays.stream(classPath.split(File.pathSeparator))
                         .filter(entry -> !new File(entry).getName().startsWith("kotlin-"))
                         .map(entry -> {
                             try {
                                 return new File(entry).toURI().toURL();
                             } catch (MalformedURLException e) {
                                 throw new IllegalStateException(e);
                             }
                         })
                         .toArray(URL[]::new);
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    static class UnsupportedId {
    }
}
