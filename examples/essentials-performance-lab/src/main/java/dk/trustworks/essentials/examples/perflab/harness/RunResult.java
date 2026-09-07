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

package dk.trustworks.essentials.examples.perflab.harness;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The outcome of one measured run of one arm.
 * <p>
 * Written as JSON so a later commit's numbers can be diffed against the phase that produced them.
 * A benchmark whose results live only in a terminal scrollback cannot answer "did this regress",
 * which is the only question the numbers are collected to answer.
 * <p>
 * Every run carries its own {@code environment} because the settings that dominate a PostgreSQL
 * queue benchmark — {@code synchronous_commit}, {@code fsync}, shared buffers — are exactly the
 * ones most likely to differ silently between two machines, and a comparison across differing
 * environments is worse than no comparison at all.
 *
 * @param extra scenario-specific values; where a scenario reports a correctness invariant it
 *              belongs here, so that a run which was fast but wrong cannot be mistaken for a pass
 */
public record RunResult(String scenario,
                        String arm,
                        int repetition,
                        Instant startedAt,
                        long durationMillis,
                        long opsCompleted,
                        double throughputPerSecond,
                        Map<String, Object> config,
                        List<LatencyRecorder.Summary> latencies,
                        Map<String, Long> dbDelta,
                        Map<String, Long> jvmDelta,
                        Map<String, String> environment,
                        Map<String, Object> extra) {

    private static final Logger       log          = LoggerFactory.getLogger(RunResult.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                                                                       .enable(SerializationFeature.INDENT_OUTPUT)
                                                                       .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public RunResult {
        requireNonNull(scenario, "No scenario provided");
        requireNonNull(arm, "No arm provided");
        requireNonNull(startedAt, "No startedAt provided");
        config = config == null ? Map.of() : Map.copyOf(config);
        latencies = latencies == null ? List.of() : List.copyOf(latencies);
        dbDelta = dbDelta == null ? Map.of() : Map.copyOf(dbDelta);
        jvmDelta = jvmDelta == null ? Map.of() : Map.copyOf(jvmDelta);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /**
     * WAL bytes divided by operations — the write-amplification figure that decides whether a
     * design is actually cheaper or merely faster on an unloaded machine.
     * <p>
     * Annotated because Jackson serializes a record's components, not its derived accessors, and a
     * headline metric silently missing from the result file is worse than one that was never
     * computed. (The project-wide ban on serialization annotations covers the Essentials types whose
     * JSON is a persisted wire-format contract across two Jackson majors — this is a local harness
     * result record, read only by the tooling that wrote it.)
     */
    @JsonProperty
    public double walBytesPerOperation() {
        var walBytes = dbDelta.getOrDefault("wal.bytesWritten", 0L);
        return opsCompleted == 0 ? 0.0d : (double) walBytes / opsCompleted;
    }

    /**
     * Write one or more results to {@code path}, creating parent directories as needed. A failure to
     * write is logged rather than thrown: losing the file is bad, losing a 30 minute run because the
     * directory was read-only is worse.
     */
    public static void writeAll(String path, Object results) {
        if (path == null || path.isBlank()) {
            log.debug("No metrics output file configured, skipping JSON write");
            return;
        }
        try {
            var target = Path.of(path);
            var parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, objectMapper.writeValueAsString(results));
            log.info("Wrote run results to {}", target.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write run results to {}", path, e);
        }
    }
}
