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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public class FileSystemAggregateArchiveDestination implements AggregateArchiveDestination {
    private final Path rootDirectory;

    public FileSystemAggregateArchiveDestination(Path rootDirectory) {
        this.rootDirectory = requireNonNull(rootDirectory, "No rootDirectory provided");
    }

    @Override
    public String write(AggregateArchiveWriteRequest request) {
        requireNonNull(request, "No request provided");
        try {
            var targetFile = rootDirectory
                    .resolve(sanitizePathSegment(request.aggregateType().toString()))
                    .resolve(sanitizePathSegment(request.logicalAggregateId()))
                    .resolve("generation-" + request.generation().generation() + "." + request.artifact().fileExtension());
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile,
                        request.artifact().content(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            return targetFile.toUri().toString();
        } catch (IOException e) {
            rethrowIfCriticalError(e);
            throw new IllegalStateException(msg("Failed to write archive artifact for {} generation {}",
                                                request.logicalAggregateId(),
                                                request.generation().generation()), e);
        }
    }

    private String sanitizePathSegment(String rawValue) {
        return rawValue.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
