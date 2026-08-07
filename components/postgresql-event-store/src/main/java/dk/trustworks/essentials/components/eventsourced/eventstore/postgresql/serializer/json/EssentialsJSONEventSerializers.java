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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json;

import dk.trustworks.essentials.components.foundation.json.*;

/**
 * Builds the {@link JSONEventSerializer} for whichever Jackson major the application uses, with the canonical
 * Essentials mapper configuration from {@link EssentialsObjectMappers}.
 * <p>
 * Use this instead of picking {@link JacksonJSONEventSerializer} or {@link Jackson3JSONEventSerializer} by hand: the two
 * write identical JSON only when their mappers are configured identically, which is what going through
 * {@link EssentialsObjectMappers} guarantees. Persisted event payloads and metadata have to remain readable across a
 * Jackson upgrade, so that guarantee is the whole point.
 */
public final class EssentialsJSONEventSerializers {

    private EssentialsJSONEventSerializers() {
    }

    /**
     * @return a {@link JSONEventSerializer} for the Jackson flavor on the classpath — Jackson 3 when the Essentials
     *         Jackson 3 modules are present, otherwise Jackson 2
     */
    public static JSONEventSerializer createForActiveJacksonFlavor() {
        return EssentialsJacksonModules.isJackson3Flavor()
               ? new Jackson3JSONEventSerializer(EssentialsObjectMappers.createJackson3ObjectMapper())
               : new JacksonJSONEventSerializer(EssentialsObjectMappers.createJackson2ObjectMapper());
    }
}
