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

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.queue.shardowned.ConnectionPoolMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.metadata.*;
import org.springframework.jdbc.datasource.*;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapter from Spring Boot's pool metadata to the engine's.
 * <p>
 * Uses an unstarted Hikari pool — the one on this test classpath — through Spring Boot's own metadata
 * implementation, the same one its auto-configuration registers. No connection is opened: the
 * adapter must answer from configuration and live counters alone.
 */
class SpringConnectionPoolMetadataTest {

    private static final DataSourcePoolMetadataProvider HIKARI = dataSource ->
            dataSource instanceof HikariDataSource hikari ? new HikariDataSourcePoolMetadata(hikari) : null;

    @Test
    void the_maximum_size_is_read_from_the_pool() {
        try (var pool = hikari(7)) {
            var metadata = SpringConnectionPoolMetadata.of(pool, new CompositeDataSourcePoolMetadataProvider(List.of(HIKARI)));
            assertThat(metadata.maximumSize()).hasValue(7);
            // Not started, so there is no live count — empty, not zero.
            assertThat(metadata.activeConnections()).isEmpty();
        }
    }

    @Test
    void a_proxied_pool_is_seen_through_by_springs_own_unwrapping() {
        try (var pool = hikari(7)) {
            DataSource proxied = new TransactionAwareDataSourceProxy(pool);
            // Spring's providers unwrap themselves; this one mirrors that with DataSourceUnwrapper so
            // the test shows the adapter gets the pool, not the proxy.
            DataSourcePoolMetadataProvider unwrapping = dataSource -> {
                var hikari = org.springframework.boot.jdbc.DataSourceUnwrapper.unwrap(dataSource, HikariDataSource.class);
                return hikari == null ? null : new HikariDataSourcePoolMetadata(hikari);
            };
            assertThat(SpringConnectionPoolMetadata.of(proxied, unwrapping).maximumSize()).hasValue(7);
        }
    }

    @Test
    void a_pool_no_provider_recognises_is_unknown() {
        var unpooled = new DriverManagerDataSource("jdbc:postgresql://localhost:1/never-connected");
        assertThat(SpringConnectionPoolMetadata.of(unpooled, new CompositeDataSourcePoolMetadataProvider(List.of(HIKARI))))
                .isSameAs(ConnectionPoolMetadata.unknown());
    }

    private static HikariDataSource hikari(int maximumPoolSize) {
        // The no-argument constructor configures without starting; nothing connects until asked.
        var pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:postgresql://localhost:1/never-connected");
        pool.setMaximumPoolSize(maximumPoolSize);
        return pool;
    }
}
