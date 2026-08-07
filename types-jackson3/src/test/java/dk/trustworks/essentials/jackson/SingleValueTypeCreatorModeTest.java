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

package dk.trustworks.essentials.jackson;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import dk.trustworks.essentials.jackson.model.*;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.ConstructorDetector;
import tools.jackson.databind.introspect.VisibilityChecker;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A value type must read back from the bare scalar it was written as, whatever the surrounding mapper is configured
 * like. Two Jackson 3 settings would otherwise change how its single-argument constructor is interpreted and make it
 * expect {@code {"value":"..."}} instead:
 * <ul>
 *     <li>{@code ALLOW_FINAL_FIELDS_AS_MUTATORS} — makes the wrapped {@code value} field settable, so the value type
 *         looks like an ordinary bean. Essentials enables exactly this for persisted JSON, to keep Jackson 2's
 *         behaviour of populating final fields.</li>
 *     <li>{@code USE_PROPERTIES_BASED} — reinterprets the single-argument constructor as taking one named property.</li>
 * </ul>
 * Both would silently change the persisted format of every id in the system, so they are pinned here rather than left
 * to whichever mapper an application happens to build.
 */
class SingleValueTypeCreatorModeTest {

    @Test
    void a_value_type_reads_a_bare_scalar_when_final_fields_are_mutators() {
        var objectMapper = mapperWith(builder -> builder.enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS));

        assertRoundTripsAsScalar(objectMapper);
    }

    @Test
    void a_value_type_reads_a_bare_scalar_when_constructors_are_properties_based() {
        var objectMapper = mapperWith(builder -> builder.constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED));

        assertRoundTripsAsScalar(objectMapper);
    }

    private static void assertRoundTripsAsScalar(tools.jackson.databind.ObjectMapper objectMapper) {
        var productId = ProductId.of("product-1");

        var json = objectMapper.writeValueAsString(productId);

        assertThat(json).isEqualTo("\"product-1\"");
        // Cast to Object: a CharSequenceType is both a CharSequence and Comparable, which AssertJ's overloads of
        // assertThat cannot disambiguate.
        assertThat((Object) objectMapper.readValue(json, ProductId.class)).isEqualTo(productId);
    }

    private static tools.jackson.databind.ObjectMapper mapperWith(java.util.function.Consumer<JsonMapper.Builder> tweak) {
        var builder = JsonMapper.builder()
                                .addModule(new EssentialTypesJacksonModule())
                                .changeDefaultVisibility(visibility -> VisibilityChecker.defaultInstance()
                                                                                        .withGetterVisibility(Visibility.NONE)
                                                                                        .withSetterVisibility(Visibility.NONE)
                                                                                        .withFieldVisibility(Visibility.ANY)
                                                                                        .withCreatorVisibility(Visibility.ANY));
        tweak.accept(builder);
        return builder.build();
    }
}
