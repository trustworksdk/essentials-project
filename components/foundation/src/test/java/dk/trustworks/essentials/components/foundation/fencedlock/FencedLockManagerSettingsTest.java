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

package dk.trustworks.essentials.components.foundation.fencedlock;

import dk.trustworks.essentials.shared.network.Network;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N-8: the default lock-manager instance id must tell apart two JVMs on one host (or two containers sharing a hostname),
 * so it is {@code hostname:pid}, not the bare hostname.
 */
class FencedLockManagerSettingsTest {
    private static final String HOSTNAME_AND_PID = Network.hostName() + ":" + ProcessHandle.current().pid();

    @Test
    void the_default_instance_id_is_hostname_and_pid() {
        var settings = FencedLockManagerSettings.withDefaults(Duration.ofSeconds(15), Duration.ofSeconds(4));

        assertThat(settings.lockManagerInstanceId()).isEqualTo(HOSTNAME_AND_PID);
        assertThat(FencedLockManagerSettings.defaultLockManagerInstanceId()).isEqualTo(HOSTNAME_AND_PID);
    }

    @Test
    void an_unset_instance_id_resolves_to_hostname_and_pid() {
        var settings = FencedLockManagerSettings.builder()
                                                .setLockManagerInstanceId((String) null)
                                                .setLockTimeOut(Duration.ofSeconds(15))
                                                .setLockConfirmationInterval(Duration.ofSeconds(4))
                                                .build();

        assertThat(settings.lockManagerInstanceId()).isEqualTo(HOSTNAME_AND_PID);
    }

    @Test
    void an_explicit_instance_id_is_kept() {
        var settings = FencedLockManagerSettings.builder()
                                                .setLockManagerInstanceId("node-a")
                                                .setLockTimeOut(Duration.ofSeconds(15))
                                                .setLockConfirmationInterval(Duration.ofSeconds(4))
                                                .build();

        assertThat(settings.lockManagerInstanceId()).isEqualTo("node-a");
    }
}
