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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.ConnectionPoolMetadata;
import org.springframework.boot.jdbc.metadata.*;

import javax.sql.DataSource;
import java.util.OptionalInt;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@link ConnectionPoolMetadata} read through Spring Boot's own {@link DataSourcePoolMetadataProvider}.
 * <p>
 * Spring Boot registers a provider for each pool it supports — HikariCP, Commons DBCP2, Tomcat JDBC,
 * Oracle UCP — and each sees through {@code DataSource} proxies to the pool. Reusing them keeps the
 * engine and this starter free of any dependency on a particular pool, and covers every pool Spring
 * Boot does. A pool none of them recognises yields {@link ConnectionPoolMetadata#unknown()}'s answers,
 * and the runtime then logs its requirement rather than checking it.
 * <p>
 * The metadata object is resolved once; the values are read from it on every call, and Spring's
 * implementations read them from the live pool, which is what the runtime needs when a pump fails to
 * acquire a connection long after start-up.
 */
final class SpringConnectionPoolMetadata implements ConnectionPoolMetadata {
    private final DataSourcePoolMetadata metadata;

    private SpringConnectionPoolMetadata(DataSourcePoolMetadata metadata) {
        this.metadata = metadata;
    }

    /**
     * Metadata for {@code dataSource}, or {@link ConnectionPoolMetadata#unknown()} if no provider
     * recognises its pool.
     */
    static ConnectionPoolMetadata of(DataSource dataSource, DataSourcePoolMetadataProvider provider) {
        requireNonNull(dataSource, "No dataSource provided");
        requireNonNull(provider, "No provider provided");
        var metadata = provider.getDataSourcePoolMetadata(dataSource);
        return metadata == null ? ConnectionPoolMetadata.unknown() : new SpringConnectionPoolMetadata(metadata);
    }

    @Override
    public OptionalInt maximumSize() {
        // Null when not known; DBCP2 reports an unbounded pool as a negative number, which the
        // runtime treats as not known too.
        return optional(metadata.getMax());
    }

    @Override
    public OptionalInt activeConnections() {
        // Null before the pool has started, among other cases.
        return optional(metadata.getActive());
    }

    private static OptionalInt optional(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    @Override
    public String toString() {
        return "SpringConnectionPoolMetadata[" + metadata.getClass().getSimpleName() + "]";
    }
}
