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

package dk.trustworks.essentials.components.distributed.fencedlock.postgresql;

import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.test.schema.SchemaReapplyLockProbe;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every instance re-applies the fenced-lock schema when it starts, while the instances already running confirm their
 * locks every few seconds. {@code CREATE INDEX IF NOT EXISTS} takes a {@code SHARE} lock on the table even when the
 * index exists, so the re-apply waited for every confirmation in flight and blocked the next ones until it committed.
 */
@Testcontainers
class PostgresqlFencedLockSchemaReapplyIT {
    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("lock-schema-reapply-db");

    @Test
    void reapplying_the_fenced_lock_schema_does_not_wait_for_lock_confirmations_in_flight() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
        jdbi.installPlugin(new PostgresPlugin());

        SchemaReapplyLockProbe.assertReapplyDoesNotWaitForWritersOnItsTables(jdbi, new PostgresqlFencedLockStorage(jdbi));
    }
}
