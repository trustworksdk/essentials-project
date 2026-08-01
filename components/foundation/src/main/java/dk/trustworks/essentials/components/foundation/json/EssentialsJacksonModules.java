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
 * Resolves the Essentials Jackson modules for whichever Jackson major is in use.
 * <p>
 * {@code types-jackson}/{@code immutable-jackson} (Jackson 2) and {@code types-jackson3}/{@code immutable-jackson3}
 * (Jackson 3) publish the <em>same</em> class names in the same packages, compiled against different Jackson majors. A
 * build therefore resolves exactly one flavor, selected by the {@code essentials.types-jackson.artifactId} /
 * {@code essentials.immutable-jackson.artifactId} properties, and the module classes cannot be named against a
 * specific major from code that has to work with both. Hence reflection.
 * <p>
 * <b>Why this fails loudly.</b> These modules are what encode the Essentials value types as JSON primitives. Building
 * a mapper without them does not error — it silently persists {@code {"value":"orders"}} where every other version of
 * the application writes {@code "orders"}, corrupting data that is expected to outlive the library version that wrote
 * it. So a flavor mismatch throws here rather than degrading quietly.
 *
 * @see JacksonJSONSerializer
 * @see Jackson3JSONSerializer
 */
public final class EssentialsJacksonModules {

    /** Present when {@code types-jackson} or {@code types-jackson3} is on the classpath. */
    public static final String TYPES_MODULE_CLASS_NAME = "dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule";

    /** Present when {@code immutable-jackson} or {@code immutable-jackson3} is on the classpath. */
    public static final String IMMUTABLE_MODULE_CLASS_NAME = "dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule";

    private static final String JACKSON_2_MODULE_TYPE = "com.fasterxml.jackson.databind.Module";
    private static final String JACKSON_3_MODULE_TYPE = "tools.jackson.databind.JacksonModule";

    private EssentialsJacksonModules() {
    }

    /**
     * @return the Essentials modules that are on the classpath and compatible with Jackson 2, as
     *         {@code com.fasterxml.jackson.databind.Module} instances
     * @throws IllegalStateException if a module is present but built for a different Jackson major
     */
    public static List<com.fasterxml.jackson.databind.Module> jackson2Modules() {
        var modules = new ArrayList<com.fasterxml.jackson.databind.Module>();
        forEachPresentModule(JACKSON_2_MODULE_TYPE, "Jackson 2", module ->
                modules.add((com.fasterxml.jackson.databind.Module) module));
        return modules;
    }

    /**
     * @return the Essentials modules that are on the classpath and compatible with Jackson 3, as
     *         {@code tools.jackson.databind.JacksonModule} instances
     * @throws IllegalStateException if a module is present but built for a different Jackson major
     */
    public static List<tools.jackson.databind.JacksonModule> jackson3Modules() {
        var modules = new ArrayList<tools.jackson.databind.JacksonModule>();
        forEachPresentModule(JACKSON_3_MODULE_TYPE, "Jackson 3", module ->
                modules.add((tools.jackson.databind.JacksonModule) module));
        return modules;
    }

    /** @return {@code true} if the Essentials Jackson modules on the classpath are the Jackson 3 flavor */
    public static boolean isJackson3Flavor() {
        return moduleClass(TYPES_MODULE_CLASS_NAME)
                .filter(moduleClass -> isAssignableTo(moduleClass, JACKSON_3_MODULE_TYPE))
                .isPresent();
    }

    private static void forEachPresentModule(String requiredModuleType,
                                             String requiredFlavor,
                                             java.util.function.Consumer<Object> consumer) {
        for (String moduleClassName : List.of(TYPES_MODULE_CLASS_NAME, IMMUTABLE_MODULE_CLASS_NAME)) {
            moduleClass(moduleClassName).ifPresent(moduleClass -> {
                if (!isAssignableTo(moduleClass, requiredModuleType)) {
                    throw new IllegalStateException(
                            moduleClassName + " on the classpath is not a " + requiredFlavor + " module. The Essentials "
                                    + "Jackson flavor and the mapper being built disagree: select the artifacts for the "
                                    + "Jackson major you are using (essentials.types-jackson.artifactId / "
                                    + "essentials.immutable-jackson.artifactId), or build the mapper for the other major. "
                                    + "Continuing would serialize Essentials value types as nested objects instead of "
                                    + "JSON primitives, which existing persisted data cannot be read back as.");
                }
                consumer.accept(instantiate(moduleClass));
            });
        }
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
