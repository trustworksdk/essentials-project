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
 * Streaming write callback handed to an {@link AggregateArchiveDestination}. Implementations write
 * archive content to the supplied {@link OutputStream} and return the number of records written
 * (typically the persisted-event count). The destination owns the stream lifecycle and will wrap
 * it with checksum/byte-counting decorators before invocation.
 */
@FunctionalInterface
public interface ArchiveContentWriter {

    /**
     * Writes archive content to the provided OutputStream and returns the number of records written.
     * <p>
     * Implementations are responsible for writing the archive data to the OutputStream.
     * The number of records written typically corresponds to the count of persisted events.
     * The OutputStream is owned by the caller, and lifecycle management such as opening
     * and closing is handled externally.
     *
     * @param out the OutputStream to write the archive content to
     * @return the number of records written to the OutputStream
     * @throws IOException if an I/O error occurs during the write operation
     */
    long write(OutputStream out) throws IOException;
}
