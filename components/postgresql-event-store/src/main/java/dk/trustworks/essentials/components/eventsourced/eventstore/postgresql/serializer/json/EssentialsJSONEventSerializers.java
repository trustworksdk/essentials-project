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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;

/**
 * Builds the {@link JSONEventSerializer} with the canonical Essentials mapper configuration from
 * {@link EssentialsObjectMappers}.
 * <p>
 * Use this instead of constructing {@link Jackson3JSONEventSerializer} around a hand-built mapper: persisted event
 * payloads and metadata have to stay readable across library versions, and only the mapper configuration in
 * {@link EssentialsObjectMappers} guarantees the established format.
 */
public final class EssentialsJSONEventSerializers {

    private EssentialsJSONEventSerializers() {
    }

    /**
     * @return a {@link JSONEventSerializer} using the canonical Essentials mapper configuration
     */
    public static JSONEventSerializer create() {
        return new Jackson3JSONEventSerializer(EssentialsObjectMappers.createJackson3ObjectMapper());
    }
}
