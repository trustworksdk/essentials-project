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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static dk.trustworks.essentials.components.queue.shardowned.adapter.TestProxies.proxy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code maxUnservedAfterInstanceLoss()} is the whole window a dead instance's unit stays unserved: the instance row ages
 * past the lease TTL, then a survivor takes the unit on its next heartbeat tick, a third of the lease TTL later. Returning
 * the lease TTL alone understated it, so a retry budget sized by it ran out before the failover completed.
 */
class ShardOwnedMaxUnservedAfterInstanceLossTest {

    @Test
    void is_the_lease_ttl_plus_one_heartbeat_interval() {
        assertThat(adapterWithLeaseTtl(Optional.of(Duration.ofSeconds(30))).maxUnservedAfterInstanceLoss())
                .contains(Duration.ofSeconds(40));
    }

    @Test
    void uses_the_lease_ttl_the_engine_runs_with_when_the_configured_one_is_shorter_than_a_second() {
        // the engine raises a lease TTL under 1 s to 1 s, and heartbeats every third of that
        assertThat(adapterWithLeaseTtl(Optional.of(Duration.ofMillis(300))).maxUnservedAfterInstanceLoss())
                .contains(Duration.ofMillis(1_333));
    }

    @Test
    void is_empty_when_the_engine_does_not_say_its_lease_ttl() {
        assertThat(adapterWithLeaseTtl(Optional.empty()).maxUnservedAfterInstanceLoss()).isEmpty();
    }

    private static ShardOwnedDurableQueues adapterWithLeaseTtl(Optional<Duration> leaseTtl) {
        MessageQueues queues = proxy(MessageQueues.class, (method, args) -> switch (method) {
            case "leaseTtl" -> leaseTtl;
            default -> throw new UnsupportedOperationException(method);
        });
        return new ShardOwnedDurableQueues(queues, EssentialsObjectMappers.createJSONSerializer(), null, null, 0);
    }
}
