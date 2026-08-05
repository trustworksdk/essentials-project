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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Serializes and deserializes generation-specific stream ids for closing-books-aware repositories.
 */
public interface ClosingBooksStreamIdSerializer<STREAM_ID> {
    String serialize(STREAM_ID streamId);

    STREAM_ID deserialize(String persistedStreamId);

    static ClosingBooksStreamIdSerializer<String> stringBased() {
        return new ClosingBooksStreamIdSerializer<>() {
            @Override
            public String serialize(String streamId) {
                return requireNonNull(streamId, "No streamId provided");
            }

            @Override
            public String deserialize(String persistedStreamId) {
                return requireNonNull(persistedStreamId, "No persistedStreamId provided");
            }
        };
    }
}
