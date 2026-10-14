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

package dk.trustworks.essentials.components.queue.shardowned.observability.micrometer;

import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The awaiting-takeover gauge is fed by stall start and end events, so an observer only ever sees the
 * transitions that happen after it was registered. The real-takeover path is covered by
 * {@code ShardOwnedMicrometerIT}; this pins the arithmetic it cannot reach.
 */
class MicrometerQueueObserverTest {

    private static double awaitingTakeover(SimpleMeterRegistry registry) {
        return registry.get(MicrometerQueueObserver.SHARDS_AWAITING_TAKEOVER_GAUGE).gauge().value();
    }

    @Test
    void the_awaiting_takeover_gauge_counts_stalls_from_start_to_end() {
        var registry = new SimpleMeterRegistry();
        var observer = new MicrometerQueueObserver(registry);
        assertThat(awaitingTakeover(registry)).as("registered eagerly, at zero").isZero();

        observer.takeoverStalled(MessageId.Lane.ORDERED, 3, true);
        observer.takeoverStalled(MessageId.Lane.UNORDERED, 1, true);
        assertThat(awaitingTakeover(registry)).isEqualTo(2.0);

        observer.takeoverStalled(MessageId.Lane.ORDERED, 3, false);
        assertThat(awaitingTakeover(registry)).isEqualTo(1.0);
    }

    @Test
    void a_stall_that_ends_before_the_observer_saw_it_start_does_not_drive_the_gauge_below_zero() {
        var registry = new SimpleMeterRegistry();
        // Registered while a shard was already stalled: it sees only the end of that stall.
        var observer = new MicrometerQueueObserver(registry);

        observer.takeoverStalled(MessageId.Lane.ORDERED, 3, false);
        assertThat(awaitingTakeover(registry)).as("a shard count is never negative").isZero();

        observer.takeoverStalled(MessageId.Lane.ORDERED, 5, true);
        assertThat(awaitingTakeover(registry)).as("a later stall still counts as one").isEqualTo(1.0);
    }
}
