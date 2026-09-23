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

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JacksonJSONSerializer} sets the mapper's class loader, which it did by replacing the mapper's
 * {@code TypeFactory} with a fresh {@code TypeFactory.defaultInstance()}. That discarded the type modifiers registered
 * modules had installed - {@code Jdk8Module}'s among them, which is what makes {@link Optional} a reference type - so a
 * payload with an {@code Optional} field failed with "Java 8 optional type ... not supported by default".
 */
class JacksonJSONSerializerTypeModifierTest {

    @Test
    void optional_fields_serialize_through_the_serializer() {
        var serializer = new JacksonJSONSerializer(JsonMapper.builder().addModule(new Jdk8Module()).build());

        var json = serializer.serialize(new WithOptional(Optional.of("present")));

        assertThat(json).isEqualTo("{\"value\":\"present\"}");
        assertThat(serializer.deserialize(json, WithOptional.class).value).contains("present");
    }

    @Test
    void changing_the_class_loader_later_keeps_the_type_modifiers() {
        var serializer = new JacksonJSONSerializer(JsonMapper.builder().addModule(new Jdk8Module()).build());

        serializer.setClassLoader(new ClassLoader(getClass().getClassLoader()) {
        });

        assertThat(serializer.serialize(new WithOptional(Optional.empty()))).isEqualTo("{\"value\":null}");
        assertThat(serializer.getClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    static final class WithOptional {
        public Optional<String> value;

        @SuppressWarnings("unused") // Jackson creator
        WithOptional() {
        }

        WithOptional(Optional<String> value) {
            this.value = value;
        }
    }
}
