/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Outcome returned by {@link AggregateArchiveDestination#write} after the exporter has streamed
 * its content. Captures the resolved location URI, the total bytes written, the on-the-fly
 * checksum and the number of records reported by the {@link ArchiveContentWriter}.
 */
public record AggregateArchiveWriteResult(
        String locationUri,
        long bytesWritten,
        long recordsWritten,
        String checksum
) {
    public AggregateArchiveWriteResult {
        requireNonBlank(locationUri, "No locationUri provided");
        if (bytesWritten < 0) throw new IllegalArgumentException("bytesWritten must be >= 0");
        if (recordsWritten < 0) throw new IllegalArgumentException("recordsWritten must be >= 0");
        requireNonNull(checksum, "No checksum provided");
    }
}
