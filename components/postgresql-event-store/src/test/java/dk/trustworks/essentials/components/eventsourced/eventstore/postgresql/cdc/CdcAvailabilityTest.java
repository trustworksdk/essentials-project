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
    void failed_increments_reason_tagged_failure_counter() {
        var meterRegistry = new SimpleMeterRegistry();
        var availability = new CdcAvailability(meterRegistry);

        availability.failed("slot_a", "wal2json missing");

        assertThat(availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
        // The start-failure counter is always reason-tagged (a "none" baseline series is registered
        // at startup) so a single Prometheus-compatible meter name carries every failure reason.
        assertThat(meterRegistry.counter("essentials.cdc.start_failures_total", "reason", "wal2json_missing").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("essentials.cdc.start_failures_total", "reason", "none").count())
                .isEqualTo(0.0);
    }

    @Test
    void active_and_inactive_update_snapshot_and_active_gauge() {
        var meterRegistry = new SimpleMeterRegistry();
        var availability = new CdcAvailability(meterRegistry);

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

    @Test
    void polling_before_cdc_has_ever_been_active_counts_as_warm_up_not_fallback() {
        // The startup case: the lifecycle starts subscriptions before the WAL tailer has connected, so each
        // subscription legitimately begins on polling. Reporting that as a fallback made a healthy boot claim
        // "CDC has fallen back to polling N times" with no reason and no error.
        var meterRegistry = new SimpleMeterRegistry();
        var availability = new CdcAvailability(meterRegistry);

        availability.fallbackUsed();
        availability.fallbackUsed();
        availability.fallbackUsed();

        var snapshot = availability.snapshot();
        assertThat(snapshot.fallbackCount()).isZero();
        assertThat(snapshot.warmupPollCount()).isEqualTo(3);
        assertThat(snapshot.everActive()).isFalse();
        assertThat(meterRegistry.counter("essentials.cdc.fallback_total").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("essentials.cdc.warmup_poll_total").count()).isEqualTo(3.0);
    }

    @Test
    void polling_after_cdc_has_been_active_counts_as_a_real_fallback() {
        var meterRegistry = new SimpleMeterRegistry();
        var availability = new CdcAvailability(meterRegistry);

        availability.fallbackUsed();              // warm-up, before CDC ever came up
        availability.active("slot_a");
        availability.inactive("slot_a", "tailer stopped");
        availability.fallbackUsed();              // genuine regression

        var snapshot = availability.snapshot();
        assertThat(snapshot.fallbackCount()).isEqualTo(1);
        assertThat(snapshot.warmupPollCount()).isEqualTo(1);
        // everActive stays true once set: a later INACTIVE is exactly what makes the second poll a fallback.
        assertThat(snapshot.everActive()).isTrue();
        assertThat(meterRegistry.counter("essentials.cdc.fallback_total").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("essentials.cdc.warmup_poll_total").count()).isEqualTo(1.0);
    }

    /**
     * A dropped replication connection flips ACTIVE to FAILED and back within a second. Reason is cleared on
     * recovery, so the interruption record is the only trace it leaves.
     */
    @Test
    void an_interruption_is_recorded_and_kept_after_recovery() {
        var meterRegistry = new SimpleMeterRegistry();
        var availability  = new CdcAvailability(meterRegistry);
        availability.active("slot");

        availability.failed("slot", "Database connection failed when writing to copy");
        var during = availability.interruptions();
        assertThat(during.count()).isEqualTo(1);
        assertThat(during.ongoing()).isTrue();
        assertThat(during.lastReason()).isEqualTo("Database connection failed when writing to copy");
        assertThat(during.lastInterruptedAtEpochMs()).isPositive();
        assertThat(during.lastRecoveredAtEpochMs()).isZero();

        availability.active("slot");
        var after = availability.interruptions();
        assertThat(after.count()).isEqualTo(1);
        assertThat(after.ongoing()).isFalse();
        assertThat(after.lastReason()).isEqualTo("Database connection failed when writing to copy");
        assertThat(after.lastRecoveredAtEpochMs()).isGreaterThanOrEqualTo(after.lastInterruptedAtEpochMs());
        assertThat(availability.snapshot().reason()).isNull();
        assertThat(meterRegistry.counter("essentials.cdc.interruptions_total").count()).isEqualTo(1.0);
    }

    @Test
    void only_leaving_active_is_an_interruption() {
        var availability = new CdcAvailability();

        // Startup: INACTIVE -> FAILED -> INACTIVE never reached ACTIVE, so nothing was interrupted
        availability.inactive("slot", "starting");
        availability.failed("slot", "logical decoding not enabled");
        availability.inactive("slot", "starting");
        assertThat(availability.interruptions().count()).isZero();

        // Losing the slot to another instance is one: this instance stopped delivering over CDC
        availability.active("slot");
        availability.inactive("slot", "another instance holds the slot");
        assertThat(availability.interruptions().count()).isEqualTo(1);

        // Repeating a non-active state is not a second one
        availability.failed("slot", "still down");
        assertThat(availability.interruptions().count()).isEqualTo(1);
    }

    @Test
    void a_requested_stop_is_not_an_interruption() {
        var availability = new CdcAvailability();
        availability.active("slot");

        availability.inactive("slot", CdcAvailability.STOPPED_REASON);

        assertThat(availability.interruptions().count()).isZero();
        assertThat(availability.interruptions().ongoing()).isFalse();
    }
}
