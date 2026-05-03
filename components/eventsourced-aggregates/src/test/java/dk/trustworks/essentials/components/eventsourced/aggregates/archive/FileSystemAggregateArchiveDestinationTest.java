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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.GenerationState;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.LogicalAggregateId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemAggregateArchiveDestinationTest {

    @Test
    void writes_streamed_content_inside_root_directory(@TempDir Path tempDir) throws IOException {
        var destination = new FileSystemAggregateArchiveDestination(tempDir);
        var request = writeRequest("Orders", "order-1", "jsonl");

        var result = destination.write(request, out -> {
            out.write("line\n".getBytes(StandardCharsets.UTF_8));
            return 1L;
        });

        var written = Path.of(java.net.URI.create(result.locationUri()));
        assertThat(written.startsWith(tempDir.toAbsolutePath().normalize())).isTrue();
        assertThat(Files.exists(written)).isTrue();
        assertThat(written.getFileName().toString()).isEqualTo("generation-1.jsonl");
        assertThat(Files.readAllBytes(written)).isEqualTo("line\n".getBytes(StandardCharsets.UTF_8));
        assertThat(result.bytesWritten()).isEqualTo(5L);
        assertThat(result.recordsWritten()).isEqualTo(1L);
        assertThat(result.checksum()).startsWith("sha256:");
    }

    @Test
    void rejects_parent_directory_traversal_in_aggregate_type(@TempDir Path tempDir) {
        var destination = new FileSystemAggregateArchiveDestination(tempDir);
        var request = writeRequest("..", "order-1", "jsonl");

        assertThatThrownBy(() -> destination.write(request, noopWriter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid path segment");
    }

    @Test
    void rejects_parent_directory_traversal_in_logical_aggregate_id(@TempDir Path tempDir) {
        var destination = new FileSystemAggregateArchiveDestination(tempDir);
        var request = writeRequest("Orders", "..", "jsonl");

        assertThatThrownBy(() -> destination.write(request, noopWriter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid path segment");
    }

    @Test
    void rejects_current_directory_segment(@TempDir Path tempDir) {
        var destination = new FileSystemAggregateArchiveDestination(tempDir);
        var request = writeRequest("Orders", ".", "jsonl");

        assertThatThrownBy(() -> destination.write(request, noopWriter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid path segment");
    }

    @Test
    void rejects_parent_directory_traversal_in_file_extension(@TempDir Path tempDir) {
        var destination = new FileSystemAggregateArchiveDestination(tempDir);
        var request = writeRequest("Orders", "order-1", "..");

        assertThatThrownBy(() -> destination.write(request, noopWriter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid path segment");
    }

    @Test
    void slash_characters_are_neutralized(@TempDir Path tempDir) throws IOException {
        var destination = new FileSystemAggregateArchiveDestination(tempDir);
        var request = writeRequest("../escape", "../order", "jsonl");

        var result = destination.write(request, noopWriter());

        var written = Path.of(java.net.URI.create(result.locationUri()));
        assertThat(written.startsWith(tempDir.toAbsolutePath().normalize())).isTrue();
    }

    @Test
    void deletes_partial_file_when_writer_throws(@TempDir Path tempDir) {
        var destination = new FileSystemAggregateArchiveDestination(tempDir);
        var request = writeRequest("Orders", "order-1", "jsonl");

        assertThatThrownBy(() -> destination.write(request, out -> {
            out.write("partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("boom");
        })).isInstanceOf(IOException.class);

        var expectedPath = tempDir.toAbsolutePath().normalize()
                                  .resolve("Orders").resolve("order-1").resolve("generation-1.jsonl");
        assertThat(Files.exists(expectedPath)).isFalse();
    }

    private static ArchiveContentWriter noopWriter() {
        return out -> 0L;
    }

    private static AggregateArchiveWriteRequest writeRequest(String aggregateType,
                                                             String logicalAggregateId,
                                                             String fileExtension) {
        var generation = new AggregateGeneration<>(AggregateType.of(aggregateType),
                                                   new LogicalAggregateId<>(logicalAggregateId),
                                                   1L,
                                                   logicalAggregateId + "#1",
                                                   GenerationState.CLOSED,
                                                   OffsetDateTime.parse("2026-04-01T00:00:00Z"),
                                                   Optional.of(OffsetDateTime.parse("2026-04-10T00:00:00Z")));
        return new AggregateArchiveWriteRequest(AggregateType.of(aggregateType),
                                                logicalAggregateId,
                                                generation,
                                                AggregateArchiveFormat.JSONL,
                                                fileExtension);
    }
}
