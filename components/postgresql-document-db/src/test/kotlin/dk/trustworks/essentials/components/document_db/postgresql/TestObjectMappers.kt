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

import dk.trustworks.essentials.components.foundation.json.EssentialsJacksonModules
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers
import dk.trustworks.essentials.components.foundation.json.JSONSerializer
import dk.trustworks.essentials.components.foundation.json.Jackson3JSONSerializer
import dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer

/**
 * Builds the [JSONSerializer] the repository ITs persist documents with, for whichever Jackson flavour the build
 * selected.
 *
 * Two things have to line up. The mapper configuration comes from [EssentialsObjectMappers] because that configuration
 * *is* the persisted-JSON contract — a locally assembled mapper drifts and silently changes the stored format. On top
 * of that, documents here are Kotlin data classes, so the flavour's Kotlin module has to be registered or their
 * immutable constructors cannot be bound.
 *
 * Both Kotlin modules can be named side by side: Jackson 3 moved to the `tools.jackson.module` group, so the two have
 * different fully-qualified names. That is unlike the Essentials `types-jackson`/`types-jackson3` pair, which share
 * class names and therefore have to be resolved reflectively through [EssentialsJacksonModules].
 */
object TestObjectMappers {

    fun createJSONSerializer(): JSONSerializer =
        if (EssentialsJacksonModules.isJackson3Flavor()) {
            Jackson3JSONSerializer(
                EssentialsObjectMappers.createJackson3ObjectMapper(
                    tools.jackson.module.kotlin.KotlinModule.Builder().build()
                )
            )
        } else {
            JacksonJSONSerializer(
                EssentialsObjectMappers.createJackson2ObjectMapper(
                    com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build()
                )
            )
        }
}
