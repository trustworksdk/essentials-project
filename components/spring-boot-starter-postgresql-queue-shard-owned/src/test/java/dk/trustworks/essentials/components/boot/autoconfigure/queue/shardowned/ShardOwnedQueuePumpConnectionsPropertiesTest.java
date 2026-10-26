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


package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.PumpConnections;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ShardOwnedQueuePumpConnectionsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(BindsTheProperties.class);

    @Test
    void every_pump_holds_its_connection_unless_a_minimum_is_set() {
        contextRunner.run(context -> assertThat(context.getBean(ShardOwnedQueueProperties.class).toPumpConnections())
                .isEqualTo(PumpConnections.allHeld()));
    }

    @Test
    void a_minimum_makes_the_other_pumps_release_their_connection_after_the_idle_time() {
        contextRunner.withPropertyValues("essentials.shard-owned-queue.min-held-pump-connections=1",
                                         "essentials.shard-owned-queue.release-idle-pump-connection-after=3s")
                     .run(context -> assertThat(context.getBean(ShardOwnedQueueProperties.class).toPumpConnections())
                             .isEqualTo(PumpConnections.elastic(1, Duration.ofSeconds(3))));
    }

    @Test
    void the_idle_time_defaults_to_ten_seconds() {
        contextRunner.withPropertyValues("essentials.shard-owned-queue.min-held-pump-connections=0")
                     .run(context -> assertThat(context.getBean(ShardOwnedQueueProperties.class).toPumpConnections())
                             .isEqualTo(PumpConnections.elastic(0, Duration.ofSeconds(10))));
    }

    @Configuration
    @EnableConfigurationProperties(ShardOwnedQueueProperties.class)
    static class BindsTheProperties {
    }
}
