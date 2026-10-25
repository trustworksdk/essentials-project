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


package dk.trustworks.essentials.components.foundation.json;

import dk.trustworks.essentials.shared.reflection.LoadingClassFailedException;
import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link Jackson3JSONSerializer} caches the classes it resolves by name. The cache must follow
 * {@link Jackson3JSONSerializer#getClassLoader()}: a class resolved through one loader must not be handed out after the
 * loader changes, which is what Spring Boot DevTools does on every restart.
 */
class Jackson3JSONSerializerClassLookupTest {

    private static final String NAME = Payload.class.getName();
    private static final String JSON = "{\"id\":\"p-1\"}";

    /**
     * Sees only the JDK's own classes - not this test's
     */
    private final ClassLoader jdkOnly = new URLClassLoader(new URL[0], null);

    @Test
    void every_by_name_read_resolves_the_class() {
        var serializer = (Jackson3JSONSerializer) EssentialsObjectMappers.createJSONSerializer();

        for (var i = 0; i < 3; i++) {
            assertThat((Object) serializer.deserialize(JSON, NAME)).isEqualTo(new Payload("p-1"));
            assertThat((Object) serializer.deserialize(JSON.getBytes(StandardCharsets.UTF_8), NAME)).isEqualTo(new Payload("p-1"));
        }
        var read = new AtomicReference<Object>();
        serializer.readProperties(("{\"p\":" + JSON + "}").getBytes(StandardCharsets.UTF_8), (name, value) -> read.set(value.as(NAME)));
        assertThat(read.get()).isEqualTo(new Payload("p-1"));
    }

    @Test
    void a_new_class_loader_is_asked_again_rather_than_answered_from_the_cache() {
        var serializer = (Jackson3JSONSerializer) EssentialsObjectMappers.createJSONSerializer();
        var original   = serializer.getClassLoader();
        assertThat((Object) serializer.deserialize(JSON, NAME)).isEqualTo(new Payload("p-1"));

        serializer.setClassLoader(jdkOnly);
        assertThatThrownBy(() -> serializer.deserialize(JSON, NAME)).isInstanceOf(LoadingClassFailedException.class);
        assertThat((Object) serializer.deserialize("\"text\"", String.class.getName())).isEqualTo("text");

        serializer.setClassLoader(original);
        assertThat((Object) serializer.deserialize(JSON, NAME)).isEqualTo(new Payload("p-1"));
    }

    @Test
    void a_subclass_answering_another_class_loader_is_followed_too() {
        var loader = new AtomicReference<ClassLoader>(getClass().getClassLoader());
        var serializer = new Jackson3JSONSerializer(EssentialsObjectMappers.createJackson3ObjectMapper()) {
            @Override
            public ClassLoader getClassLoader() {
                return loader.get();
            }
        };
        assertThat((Object) serializer.deserialize(JSON, NAME)).isEqualTo(new Payload("p-1"));

        loader.set(jdkOnly);

        assertThatThrownBy(() -> serializer.deserialize(JSON, NAME)).isInstanceOf(LoadingClassFailedException.class);
    }

    @Test
    void a_class_that_does_not_load_is_not_remembered() {
        var serializer = (Jackson3JSONSerializer) EssentialsObjectMappers.createJSONSerializer();

        for (var i = 0; i < 2; i++) {
            assertThatThrownBy(() -> serializer.deserialize(JSON, "com.example.DoesNotExist")).isInstanceOf(LoadingClassFailedException.class);
        }
    }

    private record Payload(String id) {
    }
}
