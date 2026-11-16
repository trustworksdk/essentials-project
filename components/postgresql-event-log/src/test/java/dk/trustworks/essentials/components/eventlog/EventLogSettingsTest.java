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
}
