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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.postgresql.SqlExecutionTimeLogger;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresqlAggregateArchiveRegistryIT {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    @Container
    private final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:18.4").withDatabaseName("event-store")
                                                                                                           .withUsername("test-user")
                                                                                                           .withPassword("secret-password");

    private EventStoreManagedUnitOfWorkFactory unitOfWorkFactory;
    private PostgresqlAggregateArchiveRegistry registry;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        jdbi.setSqlLogger(new SqlExecutionTimeLogger());

        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
        registry = new PostgresqlAggregateArchiveRegistry(unitOfWorkFactory);
    }

    @AfterEach
    void cleanup() {
        unitOfWorkFactory.getCurrentUnitOfWork().ifPresent(UnitOfWork::rollback);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void try_claim_inserts_in_progress_marker_and_returns_true() {
        var claimed = registry.tryClaim(ORDERS, "order-1", 1L, "order-1#1", OffsetDateTime.now());

        assertThat(claimed).isTrue();
        var entry = registry.findArchivedGeneration(ORDERS, "order-1", 1L);
        assertThat(entry).isPresent();
        assertThat(entry.get().status()).isEqualTo(AggregateArchiveStatus.IN_PROGRESS);
        assertThat(entry.get().streamAggregateId()).isEqualTo("order-1#1");
        assertThat(entry.get().archiveLocation()).isNull();
        assertThat(entry.get().format()).isNull();
    }

    @Test
    void try_claim_returns_false_when_another_node_already_claimed() {
        var winner = registry.tryClaim(ORDERS, "order-1", 1L, "order-1#1", OffsetDateTime.now());
        var loser = registry.tryClaim(ORDERS, "order-1", 1L, "order-1#1", OffsetDateTime.now());

        assertThat(winner).isTrue();
        assertThat(loser).isFalse();
    }

    @Test
    void try_claim_returns_false_when_generation_is_already_archived() {
        registry.save(new AggregateArchiveEntry(ORDERS,
                                                "order-1",
                                                1L,
                                                "order-1#1",
                                                AggregateArchiveStatus.ARCHIVED,
                                                AggregateArchiveFormat.JSONL,
                                                "file:///tmp/orders/order-1/generation-1.jsonl",
                                                10L,
                                                "sha256:abc",
                                                OffsetDateTime.now(),
                                                OffsetDateTime.now(),
                                                null));

        assertThat(registry.tryClaim(ORDERS, "order-1", 1L, "order-1#1", OffsetDateTime.now())).isFalse();
    }

    @Test
    void save_after_try_claim_promotes_in_progress_to_archived() {
        registry.tryClaim(ORDERS, "order-1", 1L, "order-1#1", OffsetDateTime.now());

        registry.save(new AggregateArchiveEntry(ORDERS,
                                                "order-1",
                                                1L,
                                                "order-1#1",
                                                AggregateArchiveStatus.ARCHIVED,
                                                AggregateArchiveFormat.JSONL,
                                                "file:///tmp/orders/order-1/generation-1.jsonl",
                                                7L,
                                                "sha256:abc",
                                                OffsetDateTime.now(),
                                                OffsetDateTime.now(),
                                                null));

        var entry = registry.findArchivedGeneration(ORDERS, "order-1", 1L).orElseThrow();
        assertThat(entry.status()).isEqualTo(AggregateArchiveStatus.ARCHIVED);
        assertThat(entry.archiveLocation()).isEqualTo("file:///tmp/orders/order-1/generation-1.jsonl");
        assertThat(entry.checksum()).isEqualTo("sha256:abc");
        assertThat(entry.eventCount()).isEqualTo(7L);
    }
}
