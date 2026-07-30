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

package dk.trustworks.essentials.components.document_db.postgresql

import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object TestObjectMappers {
    fun createKotlinObjectMapper() = JsonMapper.builder()
        .addModule(Jdk8Module())
        .addModule(JavaTimeModule())
        .addModules(*optionalEssentialsModules())
        .build()
        .registerKotlinModule()

    private fun optionalEssentialsModules(): Array<Module> {
        val modules = mutableListOf<Module>()
        addIfFasterxmlModule(modules, "dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule")
        addIfFasterxmlModule(modules, "dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule")
        return modules.toTypedArray()
    }

    private fun addIfFasterxmlModule(modules: MutableList<Module>, className: String) {
        try {
            val instance = Class.forName(className).getDeclaredConstructor().newInstance()
            if (instance is Module) {
                modules.add(instance)
            }
        } catch (_: ReflectiveOperationException) {
            // Optional test support module for the active Jackson flavor.
        }
    }
}
