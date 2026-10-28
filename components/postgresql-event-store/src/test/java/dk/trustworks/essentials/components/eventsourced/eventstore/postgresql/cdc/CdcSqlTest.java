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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CdcSqlTest {
    @Test
    void the_inbox_status_index_keeps_its_name() {
        var cdcSql = new CdcSql();

        assertThat(cdcSql.getCdcIndexName()).isEqualTo("idx_eventstore_cdc_inbox_inbox_status");
        assertThat(cdcSql.getCreateCdcIndexSql())
                .isEqualTo("CREATE INDEX IF NOT EXISTS idx_eventstore_cdc_inbox_inbox_status ON eventstore_cdc_inbox (slot_name, status, inbox_id)");
    }

    @Test
    void the_inbox_status_index_is_looked_up_under_the_name_its_create_index_statement_gives_it() {
        // Long enough that the index name crosses PostgreSQL's 63-character identifier limit
        var longTableName = "a_long_cdc_inbox_table_name_used_by_one_service_of_this_system";
        assertThat(longTableName).hasSize(PostgresqlUtil.MAX_IDENTIFIER_LENGTH - 1);

        for (var tableName : List.of(CdcSql.DEFAULT_CDC_TABLE_NAME, longTableName)) {
            @SuppressWarnings("unchecked")
            var repository = new CdcInboxRepository(mock(HandleAwareUnitOfWorkFactory.class), Optional.empty(), tableName, SchemaOwnership.HARNESS);

            var indexChange = repository.contribute(mock(SchemaContext.class))
                                        .stream()
                                        .filter(change -> "cdc-inbox-status-index".equals(change.changeId()))
                                        .findFirst()
                                        .orElseThrow();

            var statements = String.join("\n", indexChange.statements());
            assertThat(firstGroup("c\\.relname = left\\(lower\\('(\\w+)'\\), 63\\)", statements))
                    .isEqualTo(firstGroup("CREATE INDEX IF NOT EXISTS (\\w+) ON ", statements))
                    .isEqualTo("idx_" + tableName + "_inbox_status");
        }
    }

    private static String firstGroup(String regex, String statements) {
        var matcher = Pattern.compile(regex).matcher(statements);
        assertThat(matcher.find()).as("'%s' in %s", regex, statements).isTrue();
        return matcher.group(1);
    }
}
