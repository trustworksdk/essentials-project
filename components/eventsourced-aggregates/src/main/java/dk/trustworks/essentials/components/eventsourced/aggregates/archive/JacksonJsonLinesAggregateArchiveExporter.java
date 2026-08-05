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

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An implementation of the {@link AggregateArchiveExporter} interface that exports aggregate events
 * in newline-delimited JSON (JSONL) format using the {@link JSONSerializer}.
 * <p>
 * This implementation is responsible for streaming persisted events from a provided
 * {@link AggregateArchiveExportRequest} into an {@link OutputStream}. Each event is serialized
 * into a JSON object using the supplied {@link JSONSerializer} and written as a single line in the
 * output stream. The resulting format conforms to JSON Lines (JSONL), where each line of the output
 * represents one JSON object.
 * <p>
 * The lifecycle of the {@link OutputStream} is managed externally, and this implementation should
 * not close it.
 * <p>
 * Usage of this exporter is validated to ensure that required dependencies, such as the
 * {@link JSONSerializer}, are provided during instantiation and that invalid or null parameters
 * are not allowed during export. An {@link IllegalArgumentException} is thrown for any missing
 * required parameters.
 */
public class JacksonJsonLinesAggregateArchiveExporter implements AggregateArchiveExporter {
    private final JSONSerializer jsonSerializer;

    public JacksonJsonLinesAggregateArchiveExporter(JSONSerializer jsonSerializer) {
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
    }

    @Override
    public AggregateArchiveFormat format() {
        return AggregateArchiveFormat.JSONL;
    }

    @Override
    public String fileExtension() {
        return "jsonl";
    }

    @Override
    public long export(AggregateArchiveExportRequest request, OutputStream out) throws IOException {
        requireNonNull(request, "No request provided");
        requireNonNull(out, "No out provided");
        // Wrap, but don't close the supplied OutputStream — the destination owns its lifecycle.
        var writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        long count = 0L;
        try (var events = request.persistedEvents()) {
            for (var iterator = events.iterator(); iterator.hasNext(); ) {
                var event = iterator.next();
                writer.write(jsonSerializer.serialize(ArchivedPersistedEventLine.from(request, event)));
                writer.write('\n');
                count++;
            }
        }
        writer.flush();
        return count;
    }
}
