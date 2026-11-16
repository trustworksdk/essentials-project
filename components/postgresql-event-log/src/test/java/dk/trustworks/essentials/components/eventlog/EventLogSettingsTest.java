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

package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EventLogSettingsTest {
    private static final long TWO_MS = Duration.ofMillis(2).toNanos();

    private final EventLogSettings ruleOnly = EventLogSettings.defaults()
                                                              .withBackstopInterval(Duration.ofMillis(1))
                                                              .withProbeBackoff(Duration.ofMillis(1), Duration.ofSeconds(10));

    @Test
    void the_probe_wait_is_never_below_the_backstop_floor() {
        assertThat(EventLogSettings.defaults().probeWait(TWO_MS, 10)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void up_to_one_reader_s_worth_of_backends_the_wait_is_a_hundred_times_the_probe_cost() {
        assertThat(ruleOnly.probeWait(TWO_MS, 1)).isEqualTo(Duration.ofMillis(200));
        assertThat(ruleOnly.probeWait(TWO_MS, 19)).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void the_wait_grows_with_the_readers_the_backend_count_implies() {
        assertThat(ruleOnly.probeWait(TWO_MS, 100)).isEqualTo(Duration.ofSeconds(2));
        assertThat(EventLogSettings.defaults().probeWait(TWO_MS, 400)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void the_wait_is_capped_at_probe_backoff_max() {
        assertThat(ruleOnly.probeWait(TWO_MS, 2_000)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void after_a_wake_the_probe_wait_follows_the_cost_rule_without_the_backstop_floor() {
        var defaults = EventLogSettings.defaults();
        assertThat(defaults.wokenProbeMin()).isEqualTo(Duration.ofMillis(10));
        assertThat(defaults.wokenProbeWait(0, 1)).isEqualTo(Duration.ofMillis(10));
        assertThat(defaults.wokenProbeWait(TWO_MS, 1)).isEqualTo(Duration.ofMillis(200));
        assertThat(defaults.wokenProbeWait(TWO_MS, 100)).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.wokenProbeWait(Duration.ofSeconds(1).toNanos(), 1)).isEqualTo(defaults.probeBackoffMax());
    }

    @Test
    void the_coalescing_intervals_may_be_zero_but_not_negative() {
        var defaults = EventLogSettings.defaults();
        assertThat(defaults.withWokenReadMin(Duration.ZERO).wokenReadMin()).isZero();
        assertThat(defaults.withNotifySendMin(Duration.ZERO).notifySendMin()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> defaults.withWokenReadMin(Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> defaults.withNotifySendMin(Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- phase 7 §6: lease timings ----

    @Test
    void the_lease_defaults_are_15_s_4_s_7_s_and_3_s() {
        var settings = EventLogSettings.defaults();
        assertThat(settings.leaseDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(settings.leaseRenewalInterval()).isEqualTo(Duration.ofSeconds(4));
        assertThat(settings.leaseLocalStop()).isEqualTo(Duration.ofSeconds(7));
        assertThat(settings.leaseStatementTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.nodeName()).isEmpty();
    }

    @Test
    void the_test_timings_are_valid() {
        var settings = EventLogSettings.defaults().withLease(Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(1))
                                       .withLeaseStatementTimeout(Duration.ofSeconds(1));
        assertThat(settings.leaseLocalStop()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void the_lease_must_outlast_the_local_deadline_plus_one_renewal() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EventLogSettings.defaults().withLease(Duration.ofSeconds(11), Duration.ofSeconds(4), Duration.ofSeconds(7)))
                                       .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaseDuration");
    }

    @Test
    void the_local_deadline_is_at_least_one_renewal_and_below_the_lease() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EventLogSettings.defaults().withLease(Duration.ofSeconds(15), Duration.ofSeconds(4), Duration.ofSeconds(3)))
                                       .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaseLocalStop");
    }

    @Test
    void the_statement_timeout_is_whole_seconds_and_at_most_the_local_deadline() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EventLogSettings.defaults().withLeaseStatementTimeout(Duration.ofMillis(1500)))
                                       .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole seconds");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EventLogSettings.defaults().withLeaseStatementTimeout(Duration.ofSeconds(8)))
                                       .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaseStatementTimeout");
    }

    @Test
    void the_node_name_is_at_most_64_characters() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EventLogSettings.defaults().withNodeName("x".repeat(65)))
                                       .isInstanceOf(IllegalArgumentException.class);
        assertThat(EventLogSettings.defaults().withNodeName("x".repeat(64)).nodeName()).hasSize(64);
    }
}
