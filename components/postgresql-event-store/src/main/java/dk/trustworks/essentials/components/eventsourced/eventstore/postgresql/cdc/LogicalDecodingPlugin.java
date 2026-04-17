/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import org.jdbi.v3.core.Handle;

import java.util.Map;
import java.util.Optional;

/**
 * Describes a logical decoding plugin used by the CDC tailer.
 */
public interface LogicalDecodingPlugin {
    String pluginName();

    Optional<String> unusableReason(Handle handle);

    default boolean isUsable(Handle handle) {
        return unusableReason(handle).isEmpty();
    }

    Map<String, Object> slotOptions();

    default boolean supportsCurrentPayloadPipeline() {
        return false;
    }

    default String unsupportedReason() {
        return "CDC plugin '" + pluginName() + "' is configured, but payload decoding is not implemented for the current pipeline";
    }
}
