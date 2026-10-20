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

package dk.trustworks.essentials.components.queue.shardowned;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * {@code stuckHandlerTimeout}: on by default at 15 minutes, settable per queue, and kept by the constructor 0.60.0
 * shipped, which predates it.
 */
class ShardOwnerSettingsTest {

    @Test
    void the_stuck_handler_timeout_is_on_by_default_at_fifteen_minutes() {
        assertThat(ShardOwnerSettings.defaults().stuckHandlerTimeout()).isEqualTo(Duration.ofMinutes(15))
                                                                        .isEqualTo(ShardOwnerSettings.DEFAULT_STUCK_HANDLER_TIMEOUT);
        assertThat(ShardOwnerSettings.builder().build()).isEqualTo(ShardOwnerSettings.defaults());
    }

    @Test
    @SuppressWarnings("removal")
    void the_constructor_from_before_the_setting_gives_it_its_default() {
        var defaults = ShardOwnerSettings.defaults();
        var settings = new ShardOwnerSettings(defaults.readBatchSize(), defaults.ackBatchSize(), defaults.ackFlushInterval(),
                                              defaults.chaseDelay(), defaults.holeExpiry(), defaults.sweepInterval(),
                                              defaults.maxHolesPerChase(), defaults.keyConcurrency(), defaults.pollBackstop(),
                                              defaults.maxSweepInterval(), defaults.pumpThreads(), defaults.shedGrace(),
                                              defaults.leaseTtl(), defaults.watermarkCap());

        assertThat(settings).isEqualTo(defaults);
    }

    @Test
    void one_queue_is_given_its_own_bound_with_every_other_setting_kept() {
        var shared = ShardOwnerSettings.builder().setLeaseTtl(Duration.ofSeconds(7)).setPumpThreads(3).build();

        var ownBound = shared.withStuckHandlerTimeout(Duration.ofHours(2));

        assertThat(ownBound.stuckHandlerTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(ownBound.withStuckHandlerTimeout(shared.stuckHandlerTimeout())).isEqualTo(shared);
        assertThat(shared.stuckHandlerTimeout()).as("the shared settings are left as they were").isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void the_stuck_handler_timeout_must_be_positive() {
        var defaults = ShardOwnerSettings.defaults();
        assertThatThrownBy(() -> defaults.withStuckHandlerTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withStuckHandlerTimeout(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.withStuckHandlerTimeout(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
