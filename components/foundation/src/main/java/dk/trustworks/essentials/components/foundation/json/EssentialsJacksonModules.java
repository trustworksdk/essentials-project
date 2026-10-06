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

import java.util.*;

/**
 * Resolves the Essentials Jackson modules ({@code types-jackson3} / {@code immutable-jackson3}) that are on the
 * classpath. Both are optional dependencies of this module, so they are found by class name rather than referenced.
 * <p>
 * <b>Why this fails loudly.</b> These modules are what encode the Essentials value types as JSON primitives. Building
 * a mapper without them does not error — it silently persists {@code {"value":"orders"}} where every other version of
 * the application writes {@code "orders"}, corrupting data that is expected to outlive the library version that wrote
 * it. Up to 0.50 a Jackson 2 flavor ({@code types-jackson} / {@code immutable-jackson}) published the same class names;
 * finding one of those on the classpath therefore throws, rather than being skipped.
 *
 * @see Jackson3JSONSerializer
 */
public final class EssentialsJacksonModules {

    /** Present when {@code types-jackson3} is on the classpath. */
    public static final String TYPES_MODULE_CLASS_NAME = "dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule";

    /** Present when {@code immutable-jackson3} is on the classpath. */
    public static final String IMMUTABLE_MODULE_CLASS_NAME = "dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule";

    private static final String JACKSON_MODULE_TYPE = "tools.jackson.databind.JacksonModule";

    private EssentialsJacksonModules() {
    }

    /**
     * @return the Essentials Jackson modules that are on the classpath
     * @throws IllegalStateException if a module is present but was built for Jackson 2 (an Essentials 0.50 or earlier
     *                               {@code types-jackson} / {@code immutable-jackson} artifact)
     */
    public static List<tools.jackson.databind.JacksonModule> modules() {
        var modules = new ArrayList<tools.jackson.databind.JacksonModule>();
        for (String moduleClassName : List.of(TYPES_MODULE_CLASS_NAME, IMMUTABLE_MODULE_CLASS_NAME)) {
            moduleClass(moduleClassName).ifPresent(moduleClass -> {
                if (!isAssignableTo(moduleClass, JACKSON_MODULE_TYPE)) {
                    throw new IllegalStateException(
                            moduleClassName + " on the classpath is not a Jackson 3 module. It most likely comes from "
                                    + "the Essentials 0.50-or-earlier types-jackson or immutable-jackson artifact, which "
                                    + "were built for Jackson 2 and are no longer published: depend on types-jackson3 and "
                                    + "immutable-jackson3 instead. Continuing would serialize Essentials value types as "
                                    + "nested objects instead of JSON primitives, which existing persisted data cannot be "
                                    + "read back as.");
                }
                modules.add((tools.jackson.databind.JacksonModule) instantiate(moduleClass));
            });
        }
        return modules;
    }

    private static Optional<Class<?>> moduleClass(String moduleClassName) {
        try {
            return Optional.of(Class.forName(moduleClassName));
        } catch (ClassNotFoundException e) {
            // The flavor pair is optional: an application that does not use Essentials types need not supply it.
            return Optional.empty();
        }
    }

    private static boolean isAssignableTo(Class<?> moduleClass, String moduleTypeName) {
        try {
            return Class.forName(moduleTypeName).isAssignableFrom(moduleClass);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Object instantiate(Class<?> moduleClass) {
        try {
            return moduleClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JSONSerializationException("Failed to instantiate the Essentials Jackson module "
                                                         + moduleClass.getName(), e);
        }
    }
}
