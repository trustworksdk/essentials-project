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

package dk.trustworks.essentials.components.eventsourced.aggregates.schema;

import dk.trustworks.essentials.components.eventsourced.aggregates.archive.PostgresqlAggregateArchiveRegistry;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.PostgresqlClosingBooksGenerationRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.PostgresqlAggregateSnapshotJobRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreManagedUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.test.schema.SchemaReapplyLockProbe;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every instance re-applies these components' schema when it starts, while the instances already running write to the
 * same tables. {@code CREATE INDEX IF NOT EXISTS} takes a {@code SHARE} lock on its table even when the index exists, and
 * {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} takes {@code ACCESS EXCLUSIVE} even when the column exists, so a
 * re-apply waited for every write in flight and blocked the next ones until it committed.
 */
@Testcontainers
class AggregatesSchemaReapplyConcurrencyIT {
    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("aggregates-schema-reapply-db");

    private Jdbi                               jdbi;
    private EventStoreManagedUnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());
        // The container is shared by the class, so start every test from an empty database
        jdbi.useHandle(handle -> handle.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public"));
        unitOfWorkFactory = new EventStoreManagedUnitOfWorkFactory(jdbi);
    }

    @Test
    void reapplying_the_snapshot_job_schema_does_not_wait_for_jobs_being_written() {
        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(jdbi, new PostgresqlAggregateSnapshotJobRepository(unitOfWorkFactory));
    }

    @Test
    void reapplying_the_archive_registry_schema_does_not_wait_for_claims_being_written() {
        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(jdbi, new PostgresqlAggregateArchiveRegistry(unitOfWorkFactory));
    }

    @Test
    void reapplying_the_closing_books_generation_schema_does_not_wait_for_generations_being_written() {
        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(jdbi, new PostgresqlClosingBooksGenerationRepository(unitOfWorkFactory));
    }
}
