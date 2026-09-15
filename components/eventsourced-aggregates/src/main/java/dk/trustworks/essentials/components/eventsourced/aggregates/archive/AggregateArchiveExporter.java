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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Streams persisted events into an {@link OutputStream} owned by an
 * {@link AggregateArchiveDestination}. Implementations must consume the
 * {@link AggregateArchiveExportRequest#persistedEvents()} stream exactly once and write the
 * encoded bytes directly to the supplied {@link OutputStream} without buffering the entire
 * payload in memory.
 */
public interface AggregateArchiveExporter {
    AggregateArchiveFormat format();

    String fileExtension();

    /**
     * Streams the events in {@code request} as the configured archive format.
     *
     * @return the number of events written
     */
    long export(AggregateArchiveExportRequest request, OutputStream out) throws IOException;
}
