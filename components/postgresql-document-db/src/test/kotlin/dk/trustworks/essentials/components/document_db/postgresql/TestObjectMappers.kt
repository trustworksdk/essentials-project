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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers
import dk.trustworks.essentials.components.foundation.json.JSONSerializer
import dk.trustworks.essentials.components.foundation.json.Jackson3JSONSerializer
import tools.jackson.module.kotlin.KotlinModule

/**
 * Builds the [JSONSerializer] the repository ITs persist documents with.
 *
 * The mapper configuration comes from [EssentialsObjectMappers] because that configuration *is* the persisted-JSON
 * contract — a locally assembled mapper drifts and silently changes the stored format. Documents here are Kotlin data
 * classes, so the Kotlin module is registered on top, or their immutable constructors cannot be bound.
 */
object TestObjectMappers {

    fun createJSONSerializer(): JSONSerializer =
        Jackson3JSONSerializer(EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule.Builder().build()))
}
