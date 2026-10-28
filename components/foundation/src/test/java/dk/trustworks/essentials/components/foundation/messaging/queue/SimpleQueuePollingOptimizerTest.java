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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optimizer a consumer polling on its own ({@link DefaultDurableQueueConsumer}) backs off with by default: a due
 * time reported through {@link QueuePollingOptimizer#messageScheduledFor(Instant)} ends the backoff early until a poll
 * whose claim could have seen the message has run - as for {@link CentralizedQueuePollingOptimizer}.
 */
class SimpleQueuePollingOptimizerTest {
    /**
     * A one-minute backoff after the first empty poll, so only a due time ends it early
     */
    private static SimpleQueuePollingOptimizer backingOffOptimizer() {
        var consumeFromQueue = ConsumeFromQueue.builder()
                                               .setQueueName(QueueName.of("TestQueue"))
                                               .setPollingInterval(Duration.ofMillis(100))
                                               .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(100), 1))
                                               .setQueueMessageHandler(message -> {
                                               })
                                               .build();
        var optimizer = new SimpleQueuePollingOptimizer(consumeFromQueue, 60_000, 60_000);
        optimizer.queuePollingReturnedNoMessages(Instant.now());
        assertThat(optimizer.shouldSkipPolling()).isTrue();
        return optimizer;
    }

    @Test
    void a_due_time_that_has_passed_ends_the_backoff() {
        var optimizer = backingOffOptimizer();

        optimizer.messageScheduledFor(Instant.now().minusMillis(1));

        assertThat(optimizer.shouldSkipPolling()).isFalse();
    }

    @Test
    void a_due_time_still_ahead_does_not_end_the_backoff() {
        var optimizer = backingOffOptimizer();

        optimizer.messageScheduledFor(Instant.now().plusSeconds(30));

        assertThat(optimizer.shouldSkipPolling()).isTrue();
    }

    @Test
    void a_due_time_after_the_claims_now_is_kept() {
        var optimizer = backingOffOptimizer();
        var now       = Instant.now();
        optimizer.messageScheduledFor(now.minusSeconds(1));

        optimizer.queuePollingReturnedNoMessages(now.minusSeconds(2));

        assertThat(optimizer.shouldSkipPolling()).as("the claim could not have seen the message, so the next poll must look").isFalse();
    }

    @Test
    void a_due_time_before_the_claims_now_is_forgotten() {
        var optimizer = backingOffOptimizer();
        var now       = Instant.now();
        optimizer.messageScheduledFor(now.minusSeconds(2));

        optimizer.queuePollingReturnedNoMessages(now.minusSeconds(1));

        assertThat(optimizer.shouldSkipPolling()).as("the claim looked for the message, so the backoff applies again").isTrue();
    }

    @Test
    void a_report_without_a_claim_time_still_forgets_due_times_older_than_the_grace() {
        var optimizer = backingOffOptimizer();
        optimizer.messageScheduledFor(Instant.now().minusSeconds(1));

        optimizer.queuePollingReturnedNoMessages();

        assertThat(optimizer.shouldSkipPolling()).isTrue();
    }

    @Test
    void the_tracked_due_times_are_capped_by_dropping_the_latest() {
        var optimizer = backingOffOptimizer();
        var later     = Instant.now().plus(Duration.ofDays(1));
        for (var i = 0; i < 1_000; i++) {
            optimizer.messageScheduledFor(later.plusMillis(i));
        }

        optimizer.messageScheduledFor(Instant.now().minusMillis(1));

        assertThat(optimizer.shouldSkipPolling()).as("the earliest due time is kept").isFalse();
    }
}
