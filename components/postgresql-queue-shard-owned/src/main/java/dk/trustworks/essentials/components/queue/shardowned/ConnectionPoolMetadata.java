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

import java.util.OptionalInt;

/**
 * What a {@link ShardRuntime} can learn about the connection pool behind its {@code DataSource}.
 * <p>
 * <b>Why this exists.</b> The runtime holds {@code pumpThreads + 1} connections for as long as it
 * runs, and a pool too small for that does not fail — pumps retry forever while their shards stop
 * delivering. Checking needs the pool's maximum size, and {@code DataSource} has no standard way to
 * report one: every pool names it differently. So the engine asks through this interface and knows
 * no pool at all; whoever builds the runtime and does know the pool supplies it. The Spring Boot
 * starter adapts Spring's own {@code DataSourcePoolMetadataProvider}, which covers HikariCP, Commons
 * DBCP2, Tomcat JDBC and Oracle UCP and sees through {@code DataSource} proxies.
 * <p>
 * Both methods are read at the moment they are needed — size at start-up, active connections when a
 * pump fails to acquire one — so an implementation must answer from the pool's live state and must
 * not open a connection to do it. Either may be empty; the runtime then skips what it would have
 * checked, and never refuses to start for want of an answer.
 *
 * @see ShardRuntime#ShardRuntime(javax.sql.DataSource, ShardOwnerSettings, ShardOwnerMetrics, ConnectionPoolMetadata)
 */
public interface ConnectionPoolMetadata {

    /**
     * The most connections the pool will hand out at once. Empty if it is unknown or unbounded.
     */
    OptionalInt maximumSize();

    /**
     * How many connections are checked out right now. Empty if it is unknown — including a pool that
     * has not started yet.
     */
    OptionalInt activeConnections();

    /**
     * Metadata that knows nothing, for a pool that cannot be read. The runtime then logs what it needs
     * rather than checking it.
     */
    static ConnectionPoolMetadata unknown() {
        return UnknownConnectionPoolMetadata.INSTANCE;
    }

    /**
     * Fixed answers, for a caller who knows the pool's size but has no live view of it, and for tests.
     *
     * @param maximumSize the most connections the pool will hand out at once; must be positive
     */
    static ConnectionPoolMetadata ofMaximumSize(int maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive, was " + maximumSize);
        }
        return new ConnectionPoolMetadata() {
            @Override
            public OptionalInt maximumSize() {
                return OptionalInt.of(maximumSize);
            }

            @Override
            public OptionalInt activeConnections() {
                return OptionalInt.empty();
            }

            @Override
            public String toString() {
                return "ConnectionPoolMetadata[maximumSize=" + maximumSize + "]";
            }
        };
    }
}

/**
 * {@link ConnectionPoolMetadata#unknown()}. Top level and package-private, because a type nested in an
 * interface is implicitly public and this one is not API.
 */
enum UnknownConnectionPoolMetadata implements ConnectionPoolMetadata {
    INSTANCE;

    @Override
    public OptionalInt maximumSize() {
        return OptionalInt.empty();
    }

    @Override
    public OptionalInt activeConnections() {
        return OptionalInt.empty();
    }
}
