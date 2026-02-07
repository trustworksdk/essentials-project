/*
 *  Copyright 2021-2025 the original author or authors.
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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

public class CdcSql {
    private final String cdcTableName;

    public static final String DEFAULT_CDC_TABLE_NAME = "eventstore_cdc_inbox";

    public CdcSql(String cdcTableName) {
        PostgresqlUtil.checkIsValidTableOrColumnName(cdcTableName);
        this.cdcTableName = cdcTableName;
    }

    public CdcSql() {
        String cdcTableName = DEFAULT_CDC_TABLE_NAME;
        PostgresqlUtil.checkIsValidTableOrColumnName(cdcTableName);
        this.cdcTableName = cdcTableName;
    }

    public String getCdcTableName() {
        return cdcTableName;
    }

    public String buildCreateCdcTableSql() {
        return bind("""
                    CREATE TABLE IF NOT EXISTS {:tableName} (
                      inbox_id      BIGSERIAL primary key,
                      slot_name     TEXT NOT NULL,
                      lsn           TEXT NOT NULL,
                      received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                      payload_json  TEXT NOT NULL,
                      status        TEXT NOT NULL, -- RECEIVED | POISON | DISPATCHED
                      error         TEXT NULL,
                    
                      unique(slot_name, lsn)
                    );
                    """, arg("tableName", cdcTableName));
    }

    public String getCreateCdcIndexSql() {
        return bind("CREATE INDEX IF NOT EXISTS idx_{:tableName}_inbox_status ON {:tableName} (slot_name, status, inbox_id)",
                    arg("tableName", cdcTableName));
    }
}
