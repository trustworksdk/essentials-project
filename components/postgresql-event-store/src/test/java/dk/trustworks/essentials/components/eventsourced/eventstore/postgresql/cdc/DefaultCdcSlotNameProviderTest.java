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

import dk.trustworks.essentials.components.foundation.postgresql.InvalidTableOrColumnNameException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultCdcSlotNameProviderTest {

    @Test
    void slot_name_normalizes_hyphens_and_lowercases() {
        var provider = new DefaultCdcSlotNameProvider("tenant-db");

        var slotName = provider.slotName(CdcConsumerGroup.of("Orders-Dispatcher"));

        assertThat(slotName).isEqualTo("essentials_orders_dispatcher_tenant_db");
    }

    @Test
    void constructor_rejects_invalid_postgres_identifier() {
        assertThatThrownBy(() -> new DefaultCdcSlotNameProvider("tenant db"))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("Invalid table or column name");
    }

    @Test
    void slot_name_rejects_values_exceeding_postgresql_identifier_limit() {
        var provider = new DefaultCdcSlotNameProvider("db");
        var veryLongGroupName = "g".repeat(70);

        assertThatThrownBy(() -> provider.slotName(CdcConsumerGroup.of(veryLongGroupName)))
                .isInstanceOf(InvalidTableOrColumnNameException.class)
                .hasMessageContaining("CDC replication slot name");
    }
}
