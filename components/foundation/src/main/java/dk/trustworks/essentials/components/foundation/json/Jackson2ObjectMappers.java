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

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The Jackson 2 half of {@link EssentialsObjectMappers}, kept in a class of its own.
 * <p>
 * The JVM verifies all methods of a class when it links the class, and verifying Jackson 2 code needs the Jackson 2
 * classes. Were this code in {@link EssentialsObjectMappers}, that class - and with it the Jackson 3 factory and
 * {@link EssentialsObjectMappers#createJSONSerializer()} - would fail with {@code NoClassDefFoundError} on a classpath
 * that has only Jackson 3. Here it is linked only when a Jackson 2 mapper is actually requested.
 */
final class Jackson2ObjectMappers {
    private Jackson2ObjectMappers() {
    }

    /**
     * @see EssentialsObjectMappers#createJackson2ObjectMapper(com.fasterxml.jackson.databind.Module...)
     */
    static ObjectMapper create(com.fasterxml.jackson.databind.Module... additionalModules) {
        requireNonNull(additionalModules, "No additionalModules provided");
        var builder = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                                                   .disable(MapperFeature.AUTO_DETECT_GETTERS)
                                                                   .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                                                                   .disable(MapperFeature.AUTO_DETECT_SETTERS)
                                                                   .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                                                                   .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                                                   .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                                                   .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                                                   .enable(MapperFeature.AUTO_DETECT_CREATORS)
                                                                   .enable(MapperFeature.AUTO_DETECT_FIELDS)
                                                                   .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                                                                   // Untyped binding (deserialize to Map/Object, as the
                                                                   // CDC WAL path does) would otherwise map JSON floats
                                                                   // to Double, so 1.10 re-serializes as 1.1 and large
                                                                   // decimals lose precision. On the CDC path the
                                                                   // re-serialized string IS the persisted event
                                                                   // payload, so fidelity has to be exact.
                                                                   .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                                                   .addModule(new Jdk8Module())
                                                                   .addModule(new JavaTimeModule());
        EssentialsJacksonModules.jackson2Modules().forEach(builder::addModule);
        for (com.fasterxml.jackson.databind.Module additionalModule : additionalModules) {
            builder.addModule(additionalModule);
        }

        var objectMapper = builder.build();
        objectMapper.setVisibility(objectMapper.getSerializationConfig().getDefaultVisibilityChecker()
                                               .withGetterVisibility(Visibility.NONE)
                                               .withSetterVisibility(Visibility.NONE)
                                               .withFieldVisibility(Visibility.ANY)
                                               .withCreatorVisibility(Visibility.ANY));
        return objectMapper;
    }
}
