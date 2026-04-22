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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcHealthCheckProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the stuck-delivery + dispatcher-dead heuristics of {@link CdcEffectivenessMonitor}
 * via direct {@code evaluate()} calls against mocked counters. Unit-level only — the real
 * end-to-end flow is covered by the perf-lab's BackpressureScenario with the monitor enabled.
 */
class CdcEffectivenessMonitorTest {

    private static final String SLOT = "test_slot";

    @Test
    void healthy_stream_does_not_trip_either_heuristic() {
        var fixture = newFixture(defaultConfig());
        fixture.availability.active(SLOT);

        // Baseline tick.
        fixture.monitor.evaluate();

        // Window passes — tailer got 2000 messages, dispatcher published 1500, ticking fine.
        sleep(50);
        fixture.tailerMessagesReceived.set(2000);
        fixture.dispatcherPublished.set(1500);
        fixture.dispatcherTicks.set(3000);
        fixture.monitor.evaluate();

        assertThat(fixture.availability.isActive()).isTrue();
        assertThat(fixture.monitor.hasFiredAtLeastOnce()).isFalse();
    }

    @Test
    void stuck_delivery_trips_the_monitor_and_flips_availability_to_FAILED() {
        var fixture = newFixture(defaultConfig());
        fixture.availability.active(SLOT);

        fixture.monitor.evaluate();

        sleep(50);
        fixture.tailerMessagesReceived.set(5000);
        fixture.dispatcherPublished.set(0);
        fixture.dispatcherTicks.set(3000);
        fixture.monitor.evaluate();

        assertThat(fixture.availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
        assertThat(fixture.availability.snapshot().reason()).contains("stuck").contains("5000");
        assertThat(fixture.monitor.hasFiredAtLeastOnce()).isTrue();
    }

    @Test
    void below_threshold_does_not_trip_stuck_heuristic_even_with_zero_published() {
        var config = defaultConfig();
        config.setMessagesReceivedThreshold(10_000);
        var fixture = newFixture(config);
        fixture.availability.active(SLOT);

        fixture.monitor.evaluate();

        sleep(50);
        fixture.tailerMessagesReceived.set(500);
        fixture.dispatcherPublished.set(0);
        fixture.dispatcherTicks.set(3000);
        fixture.monitor.evaluate();

        assertThat(fixture.availability.isActive()).isTrue();
        assertThat(fixture.monitor.hasFiredAtLeastOnce()).isFalse();
    }

    @Test
    void dispatcher_dead_heuristic_fires_after_grace_period() {
        var config = defaultConfig();
        config.setInterval(Duration.ofMillis(50));
        config.setDispatcherIdleGracePeriod(Duration.ofMillis(50));
        var fixture = newFixture(config);
        fixture.availability.active(SLOT);

        fixture.monitor.evaluate();

        // Elapse past interval + grace, keep dispatcher.ticks at 0.
        sleep(150);
        fixture.monitor.evaluate();

        assertThat(fixture.availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
        assertThat(fixture.availability.snapshot().reason()).contains("dispatcher appears dead");
    }

    @Test
    void dispatcher_not_flagged_dead_before_grace_period_elapses() {
        var config = defaultConfig();
        config.setInterval(Duration.ofMillis(50));
        config.setDispatcherIdleGracePeriod(Duration.ofSeconds(60));
        var fixture = newFixture(config);
        fixture.availability.active(SLOT);

        fixture.monitor.evaluate();

        // Less than the 60s grace — must NOT fire.
        sleep(100);
        fixture.monitor.evaluate();

        assertThat(fixture.availability.isActive()).isTrue();
    }

    @Test
    void non_active_availability_resets_baseline_and_does_not_fire() {
        var fixture = newFixture(defaultConfig());
        // Default availability is INACTIVE — monitor must never evaluate heuristics until ACTIVE.

        fixture.monitor.evaluate();
        sleep(50);
        fixture.tailerMessagesReceived.set(5000);
        fixture.dispatcherPublished.set(0);
        fixture.monitor.evaluate();

        assertThat(fixture.availability.isActive()).isFalse();
        assertThat(fixture.monitor.hasFiredAtLeastOnce()).isFalse();
    }

    @Test
    void auto_recover_false_fires_once_then_stays_quiet_even_if_availability_returns_active() {
        var config = defaultConfig();
        config.setAutoRecover(false);
        var fixture = newFixture(config);
        fixture.availability.active(SLOT);
        fixture.monitor.evaluate();

        // First stuck window — fires.
        sleep(50);
        fixture.tailerMessagesReceived.set(5000);
        fixture.dispatcherPublished.set(0);
        fixture.dispatcherTicks.set(3000);
        fixture.monitor.evaluate();
        assertThat(fixture.availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
        assertThat(fixture.monitor.hasFiredAtLeastOnce()).isTrue();

        // Tailer reconnects and flips ACTIVE.
        fixture.availability.active(SLOT);

        // Next stuck window must NOT re-fire — autoRecover is off and the monitor has already fired.
        sleep(50);
        fixture.tailerMessagesReceived.set(15000);
        fixture.dispatcherPublished.set(0);
        fixture.monitor.evaluate();

        assertThat(fixture.availability.isActive()).isTrue();
    }

    /**
     * Regression: previously, a monitor that started before the tailer flipped availability to
     * ACTIVE wasted a full interval on "tick 1 sets baseline, tick 2 evaluates" — pushing
     * detection out to 2×interval. The listener-based baseline capture (subscribed to
     * {@link CdcAvailability#stateChanges()}) eagerly snapshots counters the moment availability
     * transitions ACTIVE, so the first scheduled tick at t=interval runs a real evaluation.
     * <p>
     * This test drives the real {@code start()} path — the other tests exercise {@code evaluate()}
     * directly, which doesn't install the listener.
     */
    @Test
    void active_transition_triggers_eager_baseline_capture_so_detection_lands_within_one_interval() throws InterruptedException {
        var config = defaultConfig();
        // Minimum enforced interval in start() is 1000ms. Using the floor here keeps the test
        // fast; anything smaller would be silently clamped and mislead the assertions.
        config.setInterval(Duration.ofMillis(1000));
        var fixture = newFixture(config);

        fixture.tailerMessagesReceived.set(0);
        fixture.dispatcherPublished.set(0);
        fixture.dispatcherTicks.set(0);

        fixture.monitor.start();
        try {
            // Let the monitor tick once with INACTIVE — no signal, no firing.
            Thread.sleep(1200);
            assertThat(fixture.monitor.hasFiredAtLeastOnce()).isFalse();

            // Availability flips ACTIVE — listener fires immediately, baseline is captured now
            // (tailerMessagesReceived=0, dispatcherPublished=0, dispatcherTicks=0).
            fixture.availability.active(SLOT);

            // Short delay for the listener's executor.execute(evaluateSafely) to finish capturing
            // baseline before we mutate the counters.
            Thread.sleep(100);

            // Simulate stuck delivery: tailer receives a batch, dispatcher publishes nothing.
            fixture.tailerMessagesReceived.set(5000);
            fixture.dispatcherPublished.set(0);
            fixture.dispatcherTicks.set(500);

            // Wait one full interval plus slack — the NEXT scheduled tick should evaluate and
            // fire. If the baseline hadn't been captured eagerly, we'd still be in "first tick
            // sets baseline" territory and would NOT fire until a second interval elapsed.
            Thread.sleep(config.getInterval().toMillis() + 500);

            assertThat(fixture.monitor.hasFiredAtLeastOnce())
                    .as("monitor should fire within one interval of the ACTIVE transition thanks to eager baseline capture")
                    .isTrue();
        } finally {
            fixture.monitor.stop();
        }
    }

    @Test
    void auto_recover_true_re_fires_on_next_stuck_window_after_tailer_reconnects() {
        var fixture = newFixture(defaultConfig()); // autoRecover=true by default
        fixture.availability.active(SLOT);
        fixture.monitor.evaluate();

        // First stuck window — fires.
        sleep(50);
        fixture.tailerMessagesReceived.set(5000);
        fixture.dispatcherPublished.set(0);
        fixture.dispatcherTicks.set(3000);
        fixture.monitor.evaluate();
        assertThat(fixture.availability.getState()).isEqualTo(CdcAvailability.State.FAILED);

        // Tailer reconnects.
        fixture.availability.active(SLOT);

        // First tick after re-ACTIVE establishes a new baseline — must not fire yet.
        sleep(20);
        fixture.monitor.evaluate();
        assertThat(fixture.availability.isActive()).isTrue();

        // Next stuck window — fires again.
        sleep(50);
        fixture.tailerMessagesReceived.set(25000);
        fixture.dispatcherPublished.set(0);
        fixture.monitor.evaluate();
        assertThat(fixture.availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
    }

    @Test
    void auto_recreate_slot_on_stuck_fires_after_threshold_consecutive_fires() {
        var config = defaultConfig();
        config.setAutoRecreateSlotOnStuck(true);
        config.setRecreateSlotAfterConsecutiveFires(3);
        var fixture = newFixture(config);
        fixture.availability.active(SLOT);
        fixture.monitor.evaluate(); // baseline

        // Real-world cycle: fire, then tailer reconnect re-flips ACTIVE, then monitor's next
        // tick enters the recovery-reset branch (resets baseline + clears
        // monitorMarkedFailedAtNanos, counter stays), then the next window fires again.
        // Threshold=3 means we need THREE fire-recovery cycles to trigger auto-recreate.
        for (int cycle = 1; cycle <= 3; cycle++) {
            // Stuck window — fires.
            sleep(50);
            fixture.tailerMessagesReceived.addAndGet(5000);
            fixture.dispatcherPublished.set(0);
            fixture.dispatcherTicks.addAndGet(1000);
            fixture.monitor.evaluate();
            // Tailer reconnect flaps availability back to ACTIVE — monitor's next tick absorbs
            // this as recovery-reset without clearing the counter.
            fixture.availability.active(SLOT);
            fixture.monitor.evaluate();
        }

        verify(fixture.tailer, times(1)).requestSlotRecreation();
    }

    @Test
    void auto_recreate_does_not_fire_when_flag_disabled() {
        var config = defaultConfig();
        config.setAutoRecreateSlotOnStuck(false);
        config.setRecreateSlotAfterConsecutiveFires(1);
        var fixture = newFixture(config);
        fixture.availability.active(SLOT);
        fixture.monitor.evaluate();

        sleep(50);
        fixture.tailerMessagesReceived.set(5000);
        fixture.dispatcherPublished.set(0);
        fixture.dispatcherTicks.set(3000);
        fixture.monitor.evaluate();

        verify(fixture.tailer, never()).requestSlotRecreation();
    }

    @Test
    void auto_recreate_counter_resets_after_healthy_window() {
        var config = defaultConfig();
        config.setAutoRecreateSlotOnStuck(true);
        config.setRecreateSlotAfterConsecutiveFires(3);
        var fixture = newFixture(config);
        fixture.availability.active(SLOT);
        fixture.monitor.evaluate();

        // Fire once.
        sleep(50);
        fixture.tailerMessagesReceived.set(5000);
        fixture.dispatcherPublished.set(0);
        fixture.monitor.evaluate();

        // Tailer recovers genuinely: availability back to ACTIVE and the next evaluate observes
        // actual delivery (publishedEvents advances), which is the canonical "healthy window"
        // signal that clears the consecutive-fire counter.
        fixture.availability.active(SLOT);
        sleep(20);
        fixture.monitor.evaluate(); // resets baseline after the prior fire; monitorMarkedFailedAtNanos cleared
        sleep(50);
        fixture.tailerMessagesReceived.addAndGet(500);
        fixture.dispatcherPublished.addAndGet(500);
        fixture.dispatcherTicks.addAndGet(1000);
        fixture.monitor.evaluate(); // healthy window — resets consecutiveFireCount to 0

        // Now fire twice more — should NOT have reached threshold=3 because the healthy window
        // reset the counter.
        for (int i = 0; i < 2; i++) {
            sleep(50);
            fixture.tailerMessagesReceived.addAndGet(5000);
            long beforePublished = fixture.dispatcherPublished.get();
            fixture.dispatcherPublished.set(beforePublished); // no new publications
            fixture.monitor.evaluate();
            fixture.availability.active(SLOT);
        }

        verify(fixture.tailer, never()).requestSlotRecreation();
    }

    // -------- fixture plumbing --------

    /**
     * Default config for stuck-delivery tests. The stuck heuristic doesn't look at elapsed time
     * (only counter deltas + threshold), so short intervals are fine. Grace period stays long
     * enough that the dispatcher-dead heuristic never accidentally fires in these tests.
     */
    private static CdcHealthCheckProperties defaultConfig() {
        var c = new CdcHealthCheckProperties();
        c.setEnabled(true);
        c.setInterval(Duration.ofMillis(50));
        c.setMessagesReceivedThreshold(1000);
        c.setDispatcherIdleGracePeriod(Duration.ofSeconds(60));
        c.setAutoRecover(true);
        return c;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Fixture newFixture(CdcHealthCheckProperties config) {
        var tailer         = mock(WalReplicationTailer.class);
        var dispatcher     = mock(CdcDispatcher.class);
        var availability   = new CdcAvailability();

        var msgs = new AtomicLong(0);
        var publ = new AtomicLong(0);
        var ticks = new AtomicLong(0);

        // Wire up getStatus() to read the mutable counters so tests can advance them.
        when(tailer.getStatus()).thenAnswer(inv -> new WalReplicationTailer.WalReplicationTailerStatus(
                SLOT, true, true, "0/0", "0/0", 0L,
                msgs.get(), 0L, 0L, 0L, 0L));
        when(dispatcher.getStatus()).thenAnswer(inv -> new CdcDispatcher.CdcDispatcherStatus(
                SLOT, true, false,
                ticks.get(), 0L, 0L, 0L, 0L,
                publ.get(),
                0L, // inboxRowsWithEmptyDecode
                0L, 0L,
                LogicalDecodingPlugin.DiagnosticSummary.EMPTY));

        var monitor = new CdcEffectivenessMonitor(tailer, dispatcher, availability,
                                                  CdcDeliveryMode.INBOX, config, SLOT);
        return new Fixture(tailer, dispatcher, availability, monitor, msgs, publ, ticks);
    }

    private record Fixture(WalReplicationTailer tailer,
                           CdcDispatcher dispatcher,
                           CdcAvailability availability,
                           CdcEffectivenessMonitor monitor,
                           AtomicLong tailerMessagesReceived,
                           AtomicLong dispatcherPublished,
                           AtomicLong dispatcherTicks) {
    }
}
