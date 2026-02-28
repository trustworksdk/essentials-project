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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter;

import java.nio.charset.StandardCharsets;

/**
 * Functional interface for filtering Write-Ahead Log (WAL) messages to decide whether they
 * should be persisted. This interface allows implementing custom filtering logic for WAL
 * messages in both string and byte array formats.
 * <p>
 * An optional default implementation is provided for
 * byte array inputs via {@code shouldPersist(byte[] walJsonBytes)}, which converts the input
 * bytes to UTF-8 encoded strings before delegating the decision to the primary method.
 */
@FunctionalInterface
public interface WalMessageFilter {
    boolean shouldPersist(String walJson);

    default boolean shouldPersist(byte[] walJsonBytes) {
        if (walJsonBytes == null || walJsonBytes.length == 0) return false;
        return shouldPersist(new String(walJsonBytes, StandardCharsets.UTF_8));
    }
}
