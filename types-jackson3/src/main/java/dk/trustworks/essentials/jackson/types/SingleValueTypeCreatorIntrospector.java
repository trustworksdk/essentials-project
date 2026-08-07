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

package dk.trustworks.essentials.jackson.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import dk.trustworks.essentials.types.*;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.*;

/**
 * Declares the single-argument constructor of every {@link SingleValueType} to be a <em>delegating</em> creator, so a
 * value type is always built from the JSON scalar it was written as.
 * <p>
 * Without this the mode is left to Jackson's heuristics, which read the surrounding mapper configuration: enabling
 * {@code ALLOW_FINAL_FIELDS_AS_MUTATORS} makes the wrapped {@code value} field settable, at which point a value type
 * looks like an ordinary bean and Jackson tries to read {@code {"value":"..."}} instead of {@code "..."}; choosing
 * {@code USE_PROPERTIES_BASED} breaks it the same way. Since a value type is a wrapper around one scalar, the mode is
 * not really a matter of configuration, and saying so explicitly makes value types independent of it.
 *
 * @see EssentialTypesJacksonModule
 */
final class SingleValueTypeCreatorIntrospector extends NopAnnotationIntrospector {

    @Override
    public JsonCreator.Mode findCreatorAnnotation(MapperConfig<?> config, Annotated annotated) {
        if (annotated instanceof AnnotatedConstructor constructor
            && constructor.getParameterCount() == 1
            && isSingleValueType(constructor.getDeclaringClass())) {
            return JsonCreator.Mode.DELEGATING;
        }
        // null means "no creator annotation found", leaving every other type to the standard introspection.
        return null;
    }

    private static boolean isSingleValueType(Class<?> type) {
        return SingleValueType.class.isAssignableFrom(type)
               || JSR310SingleValueType.class.isAssignableFrom(type);
    }
}
