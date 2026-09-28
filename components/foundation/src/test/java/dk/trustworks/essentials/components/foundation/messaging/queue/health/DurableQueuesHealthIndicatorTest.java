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

package dk.trustworks.essentials.components.foundation.messaging.queue.health;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.components.foundation.messaging.queue.health.DurableQueuesHealthIndicator.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DurableQueuesHealthIndicatorTest {
    private static final QueueName ORDERS   = QueueName.of("OrdersQueue");
    private static final QueueName PAYMENTS = QueueName.of("PaymentsQueue");

    private DurableQueues durableQueues;

    @BeforeEach
    void setUp() {
        durableQueues = mock(DurableQueues.class);
    }

    private void givenDeadLetters(Map<QueueName, Long> deadLettersPerQueue) {
        when(durableQueues.getQueueNames()).thenReturn(new LinkedHashSet<>(deadLettersPerQueue.keySet()));
        deadLettersPerQueue.forEach((queueName, count) ->
                                            when(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).thenReturn(count));
    }

    private DurableQueuesHealthIndicator indicatorWithThreshold(long threshold) {
        return new DurableQueuesHealthIndicator(durableQueues, threshold, Duration.ZERO);
    }

    @Test
    void a_queue_without_dead_letters_is_up() {
        givenDeadLetters(Map.of(ORDERS, 0L));

        var health = indicatorWithThreshold(0).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry(DETAIL_TOTAL_DEAD_LETTER_MESSAGES, 0L);
        assertThat(health.getDetails().get(DETAIL_DEAD_LETTER_MESSAGES_PER_QUEUE)).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                                                                                  .isEmpty();
    }

    @Test
    void dead_letters_alone_do_not_take_the_application_down() {
        // The default. A HealthIndicator contributes to the composite /actuator/health status, which readiness
        // and liveness probes are routinely pointed at, so going DOWN on a poison message would cycle pods that
        // are working correctly — and remove the consumers that would drain the queue behind it.
        givenDeadLetters(new LinkedHashMap<>(Map.of(ORDERS, 17L)));

        var health = indicatorWithThreshold(0).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry(DETAIL_TOTAL_DEAD_LETTER_MESSAGES, 17L)
                                       .containsEntry(DETAIL_DEAD_LETTER_THRESHOLD, 0L);
        assertThat(health.getDetails().get(DETAIL_QUEUES_AT_OR_ABOVE_THRESHOLD)).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                                                                                .isEmpty();
    }

    @Test
    void the_counts_are_reported_per_queue_and_summed() {
        var deadLetters = new LinkedHashMap<QueueName, Long>();
        deadLetters.put(ORDERS, 3L);
        deadLetters.put(PAYMENTS, 4L);
        givenDeadLetters(deadLetters);

        var health = indicatorWithThreshold(0).health();

        assertThat(health.getDetails()).containsEntry(DETAIL_TOTAL_DEAD_LETTER_MESSAGES, 7L);
        assertThat(health.getDetails().get(DETAIL_DEAD_LETTER_MESSAGES_PER_QUEUE))
                .isEqualTo(Map.of(ORDERS.toString(), 3L, PAYMENTS.toString(), 4L));
    }

    @Test
    void a_queue_that_reaches_an_opted_in_threshold_takes_the_indicator_down() {
        var deadLetters = new LinkedHashMap<QueueName, Long>();
        deadLetters.put(ORDERS, 10L);
        deadLetters.put(PAYMENTS, 2L);
        givenDeadLetters(deadLetters);

        var health = indicatorWithThreshold(10).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get(DETAIL_QUEUES_AT_OR_ABOVE_THRESHOLD)).isEqualTo(List.of(ORDERS.toString()));
    }

    @Test
    void the_threshold_applies_per_queue_not_to_the_total() {
        // Two queues below the threshold do not add up to a breach — otherwise the threshold an operator picks
        // would silently mean something different in an application with more queues.
        var deadLetters = new LinkedHashMap<QueueName, Long>();
        deadLetters.put(ORDERS, 6L);
        deadLetters.put(PAYMENTS, 6L);
        givenDeadLetters(deadLetters);

        var health = indicatorWithThreshold(10).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry(DETAIL_TOTAL_DEAD_LETTER_MESSAGES, 12L);
    }

    @Test
    void a_queue_below_the_threshold_stays_up() {
        givenDeadLetters(new LinkedHashMap<>(Map.of(ORDERS, 9L)));

        assertThat(indicatorWithThreshold(10).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void a_failure_reading_the_counts_is_unknown_rather_than_down() {
        // An unreachable database is not a statement about dead letters, and Spring Boot's own DataSource health
        // indicator already reports it. UNKNOWN does not drag the aggregated status down on its own.
        when(durableQueues.getQueueNames()).thenThrow(new IllegalStateException("connection refused"));

        var health = indicatorWithThreshold(10).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat((String) health.getDetails().get(DETAIL_ERROR)).contains("IllegalStateException", "connection refused");
    }

    @Test
    void the_result_is_reused_for_the_cache_duration() {
        givenDeadLetters(new LinkedHashMap<>(Map.of(ORDERS, 1L)));
        var indicator = new DurableQueuesHealthIndicator(durableQueues, 0, Duration.ofMinutes(5));

        indicator.health();
        indicator.health();
        indicator.health();

        // One query for the queue names plus one count per queue, once — not once per probe. A probe hitting
        // /actuator/health every few seconds from every pod is what makes this matter.
        verify(durableQueues, times(1)).getQueueNames();
        verify(durableQueues, times(1)).getTotalDeadLetterMessagesQueuedFor(any(QueueName.class));
    }

    @Test
    void a_zero_cache_duration_reads_every_time() {
        givenDeadLetters(new LinkedHashMap<>(Map.of(ORDERS, 1L)));
        var indicator = indicatorWithThreshold(0);

        indicator.health();
        indicator.health();

        verify(durableQueues, times(2)).getQueueNames();
    }
}
