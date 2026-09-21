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

package dk.trustworks.essentials.components.foundation.messaging.queue.observability;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.*;

import java.time.*;

import static org.assertj.core.api.Assertions.*;

class QueueStatisticsRegistryTest {
    private static final QueueName QUEUE = QueueName.of("TestQueue");
    private static final QueueName OTHER = QueueName.of("OtherQueue");

    private Clock                                           clock;
    private QueueStatisticsRegistry                         registry;
    private StatisticsCollectingDurableQueueMessageObserver observer;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);
        registry = new QueueStatisticsRegistry(3, clock);
        observer = new StatisticsCollectingDurableQueueMessageObserver(registry);
    }

    @SuppressWarnings("removal")
    private static QueuedMessage message(QueueName queueName) {
        return new DefaultQueuedMessage(QueueEntryId.random(),
                                        queueName,
                                        Message.of("a-payload"),
                                        OffsetDateTime.now(),
                                        OffsetDateTime.now(),
                                        OffsetDateTime.now(),
                                        null,
                                        1,
                                        0,
                                        false,
                                        false);
    }

    @Test
    void a_queue_with_no_deliveries_is_not_tracked() {
        assertThat(registry.findStatistics(QUEUE)).isEmpty();
        assertThat(registry.trackedQueues()).isZero();
    }

    @Test
    void handled_messages_are_counted_with_their_durations() {
        observer.messageHandled(message(QUEUE), Duration.ofMillis(10));
        observer.messageHandled(message(QUEUE), Duration.ofMillis(30));

        var delivery = registry.findStatistics(QUEUE).orElseThrow().delivery();

        assertThat(delivery.messagesHandled()).isEqualTo(2);
        assertThat(delivery.averageHandlerDuration()).isEqualTo(Duration.ofMillis(20));
        assertThat(delivery.maxHandlerDuration()).isEqualTo(Duration.ofMillis(30));
        assertThat(delivery.lastHandledAt()).isEqualTo(clock.instant());
    }

    @Test
    void durations_are_absent_rather_than_zero_when_nothing_was_handled() {
        observer.messageDeadLettered(message(QUEUE), new IllegalStateException("boom"), MessageDeliveryOutcome.PERMANENT_ERROR);

        var delivery = registry.findStatistics(QUEUE).orElseThrow().delivery();

        assertThat(delivery.messagesHandled()).isZero();
        assertThat(delivery.averageHandlerDuration()).isNull();
        assertThat(delivery.maxHandlerDuration()).isNull();
        assertThat(delivery.lastHandledAt()).isNull();
    }

    @Test
    void failures_are_counted_separately_and_the_last_reason_is_rendered_text() {
        observer.messageRetried(message(QUEUE), new IllegalStateException("try later"), Duration.ofSeconds(1));
        observer.messageDeadLettered(message(QUEUE), new IllegalArgumentException("never"), MessageDeliveryOutcome.PERMANENT_ERROR);
        observer.messageRedeliveryRequested(message(QUEUE));

        var outcomes = registry.findStatistics(QUEUE).orElseThrow().outcomes();

        assertThat(outcomes.messagesRetried()).isEqualTo(1);
        assertThat(outcomes.messagesDeadLettered()).isEqualTo(1);
        assertThat(outcomes.redeliveryRequests()).isEqualTo(1);
        assertThat(outcomes.lastFailureAt()).isEqualTo(clock.instant());
        assertThat(outcomes.lastFailureReason()).isEqualTo("IllegalArgumentException: never");
    }

    @Test
    void a_long_failure_message_is_truncated_so_the_registry_does_not_become_a_log() {
        observer.messageDeadLettered(message(QUEUE), new IllegalStateException("x".repeat(10_000)), MessageDeliveryOutcome.PERMANENT_ERROR);

        assertThat(registry.findStatistics(QUEUE).orElseThrow().outcomes().lastFailureReason())
                .hasSize(512);
    }

    @Test
    void statistics_are_kept_per_queue() {
        observer.messageHandled(message(QUEUE), Duration.ofMillis(1));
        observer.messageHandled(message(OTHER), Duration.ofMillis(1));
        observer.messageHandled(message(OTHER), Duration.ofMillis(1));

        assertThat(registry.findStatistics(QUEUE).orElseThrow().delivery().messagesHandled()).isEqualTo(1);
        assertThat(registry.findStatistics(OTHER).orElseThrow().delivery().messagesHandled()).isEqualTo(2);
        assertThat(registry.allStatistics()).hasSize(2);
    }

    @Test
    void remove_discards_one_queue_and_clear_discards_all() {
        observer.messageHandled(message(QUEUE), Duration.ofMillis(1));
        observer.messageHandled(message(OTHER), Duration.ofMillis(1));

        registry.remove(QUEUE);
        assertThat(registry.findStatistics(QUEUE)).isEmpty();
        assertThat(registry.findStatistics(OTHER)).isPresent();

        registry.clear();
        assertThat(registry.allStatistics()).isEmpty();
    }

    @Test
    void tracking_stops_at_the_cap_but_already_tracked_queues_keep_recording() {
        for (var i = 0; i < 3; i++) {
            observer.messageHandled(message(QueueName.of("Queue" + i)), Duration.ofMillis(1));
        }
        assertThat(registry.trackedQueues()).isEqualTo(3);

        observer.messageHandled(message(QueueName.of("OneTooMany")), Duration.ofMillis(1));

        assertThat(registry.findStatistics(QueueName.of("OneTooMany"))).isEmpty();
        assertThat(registry.trackedQueues()).isEqualTo(3);

        observer.messageHandled(message(QueueName.of("Queue0")), Duration.ofMillis(1));
        assertThat(registry.findStatistics(QueueName.of("Queue0")).orElseThrow().delivery().messagesHandled())
                .isEqualTo(2);
    }

    @Test
    void the_cap_must_be_positive() {
        assertThatThrownBy(() -> new QueueStatisticsRegistry(0, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statistics_since_is_when_the_queue_was_first_seen() {
        observer.messageHandled(message(QUEUE), Duration.ofMillis(1));

        assertThat(registry.findStatistics(QUEUE).orElseThrow().statisticsSince()).isEqualTo(clock.instant());
    }
}
