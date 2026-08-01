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

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.*;
import tools.jackson.databind.module.SimpleModule;

import java.util.*;

/**
 * Keeps a type that <em>is</em> a collection — {@code implements Map} or {@code Collection}, wrapping one behind a
 * single {@code final} field — reading as its contents rather than as a bean with one property.
 * <p>
 * Jackson decides that from the surrounding mapper configuration, and
 * {@link tools.jackson.databind.MapperFeature#ALLOW_FINAL_FIELDS_AS_MUTATORS} — which
 * {@link EssentialsObjectMappers#createJackson3ObjectMapper} enables, because immutable payloads have no other way to
 * be populated under Jackson 3 — tips the balance the wrong way: the wrapped field becomes a mutator, the type starts
 * looking like a bean, and deserialization calls the constructor with {@code null}. The break is read-only and
 * asymmetric, since serialization keeps writing the contents, so it surfaces far from its cause. It cost 87
 * {@code postgresql-queue} integration tests on {@code MessageMetaData}, then reappeared on {@code EventMetaData}.
 * <p>
 * Matched by <em>shape</em> rather than by a list of classes, so a new wrapper type is covered on arrival and no module
 * has to know about types declared above it. Deliberately not annotations on the types themselves: an Essentials type
 * carries no serialization framework, which is what lets one type serve both Jackson majors and the non-Jackson
 * serializers.
 * <p>
 * Jackson 2 needs none of this — final fields are mutators there by default, and it still resolves these types as
 * collections.
 */
final class Jackson3CollectionWrapperModule extends SimpleModule {

    Jackson3CollectionWrapperModule() {
        super("Essentials-Jackson3-CollectionWrappers");
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.insertAnnotationIntrospector(new CollectionWrapperCreatorIntrospector());
    }

    private static final class CollectionWrapperCreatorIntrospector extends NopAnnotationIntrospector {

        @Override
        public JsonCreator.Mode findCreatorAnnotation(MapperConfig<?> config, Annotated annotated) {
            if (annotated instanceof AnnotatedConstructor constructor
                && constructor.getParameterCount() == 1
                && isCollectionLike(constructor.getDeclaringClass())
                && isCollectionLike(constructor.getRawParameterType(0))) {
                return JsonCreator.Mode.DELEGATING;
            }
            // null means "no creator annotation found", leaving every other type to the standard introspection.
            return null;
        }

        private static boolean isCollectionLike(Class<?> type) {
            return Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
        }
    }
}
