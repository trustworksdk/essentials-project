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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

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
    public AggregateArchiveArtifact export(AggregateArchiveExportRequest request) {
        requireNonNull(request, "No request provided");
        var builder = new StringBuilder();
        request.persistedEvents()
               .forEach(event -> builder.append(jsonSerializer.serialize(ArchivedPersistedEventLine.from(request, event)))
                                        .append('\n'));

        var content = builder.toString().getBytes(StandardCharsets.UTF_8);
        return new AggregateArchiveArtifact(format(),
                                            content,
                                            request.persistedEvents().size(),
                                            sha256(content),
                                            "jsonl");
    }

    private String sha256(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            rethrowIfCriticalError(e);
            throw new IllegalStateException(msg("Failed to calculate SHA-256 checksum for {}", getClass().getSimpleName()), e);
        }
    }
}
