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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import com.fasterxml.jackson.databind.Module;

import java.util.ArrayList;
import java.util.List;

public final class TestFasterxmlModules {
    private TestFasterxmlModules() {
    }

    public static Module[] optionalEssentialsModules() {
        List<Module> modules = new ArrayList<>(2);
        addIfFasterxmlModule(modules, "dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule");
        addIfFasterxmlModule(modules, "dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule");
        return modules.toArray(new Module[0]);
    }

    private static void addIfFasterxmlModule(List<Module> modules, String moduleClassName) {
        try {
            Object instance = Class.forName(moduleClassName).getDeclaredConstructor().newInstance();
            if (instance instanceof Module module) {
                modules.add(module);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Optional test support module for the active Jackson flavor.
        }
    }
}
