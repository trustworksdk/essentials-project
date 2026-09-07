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

package dk.trustworks.essentials.components.queue.shardowned;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every durable moment in time is the database's, never a node's.
 * <p>
 * Message visibility, retry backoff, lease expiry and instance liveness are all decided by comparing
 * timestamps that nodes write and other nodes read. If any of them came from a client clock, two
 * nodes disagreeing by a few seconds would disagree about who owns a shard and about which messages
 * are due — and the failure would be intermittent, environment-dependent, and close to impossible to
 * reproduce deliberately. Sourcing all of them from {@code now()} removes the class of bug outright
 * rather than bounding it.
 * <p>
 * A structural test rather than a behavioural one, because the property cannot be observed from a
 * single machine: with one clock there is no skew to detect. What can be checked is that no client
 * timestamp is ever sent, which is the thing that would make skew matter. This exists so that a
 * later change adding {@code setTimestamp} for a delay fails here instead of in production on the
 * one cluster whose NTP is unhappy.
 */
class ServerSideTimeTest {

    private static final Path STORAGE = Path.of("src/main/java/dk/trustworks/essentials/components/queue/shardowned/NextGenStorage.java");
    private static final Path SCHEMA  = Path.of("src/main/java/dk/trustworks/essentials/components/queue/shardowned/NextGenSchema.java");

    @Test
    void no_client_supplied_timestamp_is_ever_written_to_the_database() throws Exception {
        var storage = Files.readString(STORAGE);
        assertThat(storage)
                .as("a client clock must never reach a durable column — use now() in the statement")
                .doesNotContain("setTimestamp")
                .doesNotContain("Instant.now()")
                .doesNotContain("System.currentTimeMillis()");
    }

    @Test
    void visibility_backoff_lease_and_liveness_are_all_computed_by_the_server() throws Exception {
        var storage = Files.readString(STORAGE);

        // A retry's new visibility is server-now plus the backoff, not the node's idea of the future.
        assertThat(storage)
                .as("retry backoff must be computed from the server clock")
                .contains("visible_at = now() + make_interval");

        // Delivery eligibility, lease validity and instance liveness are likewise server-evaluated.
        assertThat(storage).contains("visible_at <= now()");
        assertThat(storage).contains("lease_until > now()");
        assertThat(storage).contains("lease_until = now() + make_interval");
        assertThat(storage).contains("last_seen > now() - make_interval");
    }

    @Test
    void temporal_columns_default_to_the_server_clock() throws Exception {
        var schema = Files.readString(SCHEMA);
        for (var column : new String[]{"enqueued_at", "visible_at", "lease_until", "last_seen", "dead_lettered_at"}) {
            assertThat(schema)
                    .as("%s must default to the server clock", column)
                    .containsPattern(column + "\\s+timestamptz\\s+NOT NULL DEFAULT now\\(\\)");
        }
    }
}
