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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CdcAvailabilityTest {

    @Test
    void failed_increments_generic_and_reason_tagged_failure_counters() {
        var meterRegistry = new SimpleMeterRegistry();
        var availability = new CdcAvailability(Optional.of(meterRegistry));

        availability.failed("slot_a", "wal2json missing");

        assertThat(availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
        assertThat(meterRegistry.counter("essentials.cdc.start_failures_total").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("essentials.cdc.start_failures_total", "reason", "wal2json_missing").count())
                .isEqualTo(1.0);
    }

    @Test
    void active_and_inactive_update_snapshot_and_active_gauge() {
        var meterRegistry = new SimpleMeterRegistry();
        var availability = new CdcAvailability(Optional.of(meterRegistry));

        availability.active("slot_a");
        var activeSnapshot = availability.snapshot();
        assertThat(activeSnapshot.state()).isEqualTo(CdcAvailability.State.ACTIVE);
        assertThat(activeSnapshot.slotName()).isEqualTo("slot_a");
        assertThat(activeSnapshot.reason()).isNull();
        assertThat(activeSnapshot.lastChangedEpochMs()).isPositive();
        assertThat(meterRegistry.get("essentials.cdc.active").gauge().value()).isEqualTo(1.0);

        availability.inactive("slot_a", "tailer stopped");
        var inactiveSnapshot = availability.snapshot();
        assertThat(inactiveSnapshot.state()).isEqualTo(CdcAvailability.State.INACTIVE);
        assertThat(inactiveSnapshot.reason()).isEqualTo("tailer stopped");
        assertThat(meterRegistry.get("essentials.cdc.active").gauge().value()).isEqualTo(0.0);
    }
}
