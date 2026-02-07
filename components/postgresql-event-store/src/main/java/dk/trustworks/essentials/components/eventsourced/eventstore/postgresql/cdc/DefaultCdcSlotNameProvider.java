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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

/**
 * Default implementation of the {@link CdcSlotNameProvider} interface that provides
 * a slot name for PostgreSQL Change Data Capture (CDC) replication slots.
 * <p>
 * The slot name is derived from a combination of the consumer group name and the specified
 * database name or alias. This ensures that the slot name is unique and adheres to PostgreSQL
 * naming conventions.
 * <p>
 * This class validates the provided database name or alias and the consumer group name
 * against PostgreSQL naming rules to prevent invalid slot names.
 */
public class DefaultCdcSlotNameProvider implements CdcSlotNameProvider {

    private final String databaseNameOrAlias;

    public DefaultCdcSlotNameProvider(String databaseNameOrAlias) {
        this.databaseNameOrAlias = databaseNameOrAlias == null ? "db" : databaseNameOrAlias;
        PostgresqlUtil.checkIsValidTableOrColumnName(databaseNameOrAlias);
    }

    @Override
    public String slotName(CdcConsumerGroup group) {
        PostgresqlUtil.checkIsValidTableOrColumnName(group.name());
        return ("essentials_" + group.name() + "_" + databaseNameOrAlias).toLowerCase();
    }

}
