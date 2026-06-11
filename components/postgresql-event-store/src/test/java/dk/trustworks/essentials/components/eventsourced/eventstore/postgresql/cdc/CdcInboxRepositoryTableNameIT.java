/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the CDC inbox table-name configuration: {@code CdcInboxRepository} must read AND write
 * the table name it was configured with (the same name the {@code @TTLJob} cleans), not a hardcoded
 * {@code eventstore_cdc_inbox}. Previously every query hardcoded the default, so a custom
 * {@code essentials.eventstore.cdc.inbox-table-name} produced a TTL job cleaning one table while the
 * repository grew another unbounded.
 */
@Testcontainers
public class CdcInboxRepositoryTableNameIT extends AbstractLogicalReplicationPostgresIT {

    @Test
    void repository_reads_and_writes_the_configured_inbox_table_name() {
        var customTable = "custom_cdc_inbox_" + System.nanoTime();

        // Constructing the repository creates the table+indexes under the configured name.
        var repo = new CdcInboxRepository(unitOfWorkFactory, customTable);

        // The custom table exists and the default one was NOT created by this repository.
        assertThat(tableExists(customTable)).as("custom inbox table is created").isTrue();
        assertThat(tableExists(CdcSql.DEFAULT_CDC_TABLE_NAME))
                .as("default inbox table must NOT be created when a custom name is configured")
                .isFalse();

        // Writes and reads must all target the custom table.
        var slot = "slot_a";
        assertThat(repo.insertIfAbsent(slot, "0/1", "{\"e\":1}")).isTrue();
        assertThat(repo.insertIfAbsent(slot, "0/1", "{\"e\":1}")).as("idempotent on duplicate").isFalse();
        assertThat(repo.countByStatus(slot, CdcInboxRepository.InboxStatus.RECEIVED.name())).isEqualTo(1L);

        var batch = repo.fetchNextBatch(slot, 10);
        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).lsn()).isEqualTo("0/1");

        // And the row physically lives in the custom table.
        assertThat(rowCount(customTable)).isEqualTo(1L);
    }

    private boolean tableExists(String table) {
        return unitOfWorkFactory.withUnitOfWork(uow ->
                uow.handle().createQuery("select to_regclass(:t) is not null")
                   .bind("t", table)
                   .mapTo(boolean.class)
                   .one());
    }

    private long rowCount(String table) {
        // table name is a test-controlled validated identifier — safe to interpolate
        return unitOfWorkFactory.withUnitOfWork(uow ->
                uow.handle().createQuery("select count(*) from " + table)
                   .mapTo(long.class)
                   .one());
    }
}
