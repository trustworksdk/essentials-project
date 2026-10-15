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

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void observers_sharing_a_registry_and_tag_set_publish_the_sum_of_their_stalls() {
        var registry = new SimpleMeterRegistry();
        var first = new MicrometerQueueObserver(registry, QueueName.of("orders"));
        // Same registry, same tags: an observer rebuilt for a re-created queue, or one bound next to the starter's.
        var second = new MicrometerQueueObserver(registry, QueueName.of("orders"));

        second.takeoverStalled(MessageId.Lane.ORDERED, 3, true);
        assertThat(awaitingTakeover(registry)).as("a stall reported to the second observer shows").isEqualTo(1.0);

        first.takeoverStalled(MessageId.Lane.UNORDERED, 1, true);
        assertThat(awaitingTakeover(registry)).as("and so does one reported to the first").isEqualTo(2.0);

        first.takeoverStalled(MessageId.Lane.UNORDERED, 1, false);
        assertThat(awaitingTakeover(registry)).isEqualTo(1.0);

        // The first never saw this stall start: its own floor keeps it from ending the second observer's stall.
        first.takeoverStalled(MessageId.Lane.ORDERED, 3, false);
        assertThat(awaitingTakeover(registry)).as("an observer only ends stalls it saw start").isEqualTo(1.0);

        second.takeoverStalled(MessageId.Lane.ORDERED, 3, false);
        assertThat(awaitingTakeover(registry)).isZero();
    }

    @Test
    void observers_given_the_same_tags_in_a_different_order_publish_into_the_same_gauge() {
        var registry = new SimpleMeterRegistry();
        var first = new MicrometerQueueObserver(registry, List.of(Tag.of("queue", "orders"), Tag.of("module", "billing")));
        var second = new MicrometerQueueObserver(registry, List.of(Tag.of("module", "billing"), Tag.of("queue", "orders")));

        first.takeoverStalled(MessageId.Lane.ORDERED, 3, true);
        second.takeoverStalled(MessageId.Lane.ORDERED, 5, true);
        assertThat(awaitingTakeover(registry)).isEqualTo(2.0);
    }

    @Test
    void observers_with_different_tags_or_registries_do_not_add_into_each_other() {
        var registry = new SimpleMeterRegistry();
        var orders = new MicrometerQueueObserver(registry, QueueName.of("orders"));
        new MicrometerQueueObserver(registry, QueueName.of("shipments"));
        var otherRegistry = new SimpleMeterRegistry();
        var ordersElsewhere = new MicrometerQueueObserver(otherRegistry, QueueName.of("orders"));

        orders.takeoverStalled(MessageId.Lane.ORDERED, 3, true);
        ordersElsewhere.takeoverStalled(MessageId.Lane.ORDERED, 3, true);
        ordersElsewhere.takeoverStalled(MessageId.Lane.ORDERED, 4, true);

        assertThat(awaitingTakeover(registry, "orders")).isEqualTo(1.0);
        assertThat(awaitingTakeover(registry, "shipments")).isZero();
        assertThat(awaitingTakeover(otherRegistry, "orders")).isEqualTo(2.0);
    }

    /**
     * Gauges from two registries with the same name and tags are equal meters. Interleaving observers on them must not
     * let one registry's gauge take the other's count: the second observer on the first registry gets that registry's
     * gauge back, and its stalls must show there.
     */
    @Test
    void an_observer_on_another_registry_with_the_same_tags_does_not_detach_a_later_observer_from_its_gauge() {
        var registry = new SimpleMeterRegistry();
        var otherRegistry = new SimpleMeterRegistry();
        var first = new MicrometerQueueObserver(registry, QueueName.of("orders"));
        var elsewhere = new MicrometerQueueObserver(otherRegistry, QueueName.of("orders"));
        var second = new MicrometerQueueObserver(registry, QueueName.of("orders"));

        second.takeoverStalled(MessageId.Lane.ORDERED, 3, true);
        assertThat(awaitingTakeover(registry, "orders")).as("the second observer's stall, on its own registry").isEqualTo(1.0);
        assertThat(awaitingTakeover(otherRegistry, "orders")).as("not on the other registry").isZero();

        first.takeoverStalled(MessageId.Lane.UNORDERED, 1, true);
        elsewhere.takeoverStalled(MessageId.Lane.ORDERED, 4, true);
        assertThat(awaitingTakeover(registry, "orders")).isEqualTo(2.0);
        assertThat(awaitingTakeover(otherRegistry, "orders")).isEqualTo(1.0);
    }

    /**
     * The retired first observer is gone and collected, its replacement only ever told about stalls: the gauge must read
     * the replacement's count, neither the retired observer's zero nor {@code NaN}.
     */
    @Test
    void the_gauge_reads_a_replacement_observer_after_the_first_is_collected() throws Exception {
        var registry = new SimpleMeterRegistry();
        registerAndDropAnObserver(registry);
        forceCollection();

        var replacement = new MicrometerQueueObserver(registry, QueueName.of("orders"));
        replacement.takeoverStalled(MessageId.Lane.ORDERED, 3, true);
        forceCollection();
        assertThat(awaitingTakeover(registry, "orders")).as("the replacement's stall").isEqualTo(1.0);

        replacement.takeoverStalled(MessageId.Lane.ORDERED, 3, false);
        assertThat(awaitingTakeover(registry, "orders")).isZero();
    }

    private static void registerAndDropAnObserver(SimpleMeterRegistry registry) {
        new MicrometerQueueObserver(registry, QueueName.of("orders"));
    }

    private static void forceCollection() throws InterruptedException {
        for (var attempt = 0; attempt < 5; attempt++) {
            System.gc();
            Thread.sleep(50);
        }
    }

    private static double awaitingTakeover(SimpleMeterRegistry registry, String queue) {
        return registry.get(MicrometerQueueObserver.SHARDS_AWAITING_TAKEOVER_GAUGE).tag(MicrometerQueueObserver.QUEUE_TAG, queue).gauge().value();
    }
}
