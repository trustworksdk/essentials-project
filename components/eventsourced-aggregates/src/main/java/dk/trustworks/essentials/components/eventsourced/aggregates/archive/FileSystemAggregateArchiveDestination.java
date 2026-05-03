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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Implementation of {@link AggregateArchiveDestination} that writes archive artifacts to the file system.
 * Each artifact is written to a directory under a specified root directory, with its structure determined by
 * the aggregate type, logical aggregate ID, and generation information provided in the request.
 * <p>
 * Enforces sanitization of path segments to prevent invalid or potentially unsafe paths.
 * Handles byte counting and checksum computation during the write operation.
 * Ensures directories are created as needed and removes partial files in case of write errors.
 */
public class FileSystemAggregateArchiveDestination implements AggregateArchiveDestination {
    private static final Set<String> FORBIDDEN_PATH_SEGMENTS = Set.of(".", "..");

    private final Path rootDirectory;

    public FileSystemAggregateArchiveDestination(Path rootDirectory) {
        this.rootDirectory = requireNonNull(rootDirectory, "No rootDirectory provided")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public AggregateArchiveWriteResult write(AggregateArchiveWriteRequest request, ArchiveContentWriter writer) throws IOException {
        requireNonNull(request, "No request provided");
        requireNonNull(writer, "No writer provided");

        var fileName = "generation-" + request.generation().generation()
                + "." + sanitizePathSegment(request.fileExtension());
        var targetFile = rootDirectory
                .resolve(sanitizePathSegment(request.aggregateType().toString()))
                .resolve(sanitizePathSegment(request.logicalAggregateId()))
                .resolve(fileName)
                .normalize();
        if (!targetFile.startsWith(rootDirectory)) {
            throw new IllegalArgumentException(msg(
                    "Resolved archive path '{}' escapes root directory '{}'",
                    targetFile,
                    rootDirectory));
        }
        Files.createDirectories(targetFile.getParent());

        var digest = newSha256();
        long records;
        var counting = new ByteCountingOutputStream();
        try (var fileStream = Files.newOutputStream(targetFile,
                                                    StandardOpenOption.CREATE,
                                                    StandardOpenOption.TRUNCATE_EXISTING,
                                                    StandardOpenOption.WRITE);
             var digestStream = new DigestOutputStream(fileStream, digest);
             var bufferedStream = new BufferedOutputStream(digestStream)) {
            counting.delegate = bufferedStream;
            records = writer.write(counting);
        } catch (IOException e) {
            // Best-effort: remove a partial file so a retry can recreate it cleanly.
            try {
                Files.deleteIfExists(targetFile);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            rethrowIfCriticalError(e);
            throw e;
        }

        return new AggregateArchiveWriteResult(targetFile.toUri().toString(),
                                                counting.bytesWritten,
                                                records,
                                                "sha256:" + HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            rethrowIfCriticalError(e);
            throw new IllegalStateException(msg("Failed to initialise SHA-256 digest"), e);
        }
    }

    private String sanitizePathSegment(String rawValue) {
        requireNonNull(rawValue, "No path segment provided");
        var sanitized = rawValue.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isEmpty() || FORBIDDEN_PATH_SEGMENTS.contains(sanitized)) {
            throw new IllegalArgumentException(msg("Invalid path segment: '{}'", rawValue));
        }
        return sanitized;
    }

    /** Counts bytes written by an exporter. The {@code delegate} is set after construction
     *  because we need a stable reference to hand to the writer while still being able to
     *  configure the wrapping order in the try-with-resources block. */
    private static final class ByteCountingOutputStream extends OutputStream {
        private OutputStream delegate;
        private long bytesWritten;

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            bytesWritten += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }
}
