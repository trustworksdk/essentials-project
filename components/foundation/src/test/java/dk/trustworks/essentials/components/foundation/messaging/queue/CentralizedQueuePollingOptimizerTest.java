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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A due time reported through {@link QueuePollingOptimizer#messageScheduledFor(Instant)} ends the backoff early until a
 * poll whose claim could have seen the message has run. These pin that "could have seen" means "the claim's
 * {@code now} was at or after the due time" - not "the poll reported back after the due time".
 */
class CentralizedQueuePollingOptimizerTest {
    private static final QueueName QUEUE_NAME = QueueName.of("TestQueue");

    /**
     * A long initial delay, so after any empty poll the optimizer is backing off and only a due time ends that early
     */
    private static CentralizedQueuePollingOptimizer backingOffOptimizer() {
        var optimizer = new CentralizedQueuePollingOptimizer(QUEUE_NAME, 60_000, 120_000, 1.0, 0.0);
        optimizer.queuePollingReturnedNoMessages(Instant.now());
        assertThat(optimizer.shouldSkipPolling()).isTrue();
        return optimizer;
    }

    @Test
    void a_due_time_after_the_claims_now_is_kept_however_late_the_poll_reports_back() {
        // A poll of many queues read its claim time 2 s ago and only now reached this queue; the message came due 1 s ago
        var optimizer = backingOffOptimizer();
        var now       = Instant.now();
        optimizer.messageScheduledFor(now.minusSeconds(1));

        optimizer.queuePollingReturnedNoMessages(now.minusSeconds(2));

        assertThat(optimizer.shouldSkipPolling()).as("the claim could not have seen the message, so the next poll must look").isFalse();
    }

    @Test
    void a_due_time_at_or_before_the_claims_now_is_forgotten() {
        var optimizer = backingOffOptimizer();
        var now       = Instant.now();
        optimizer.messageScheduledFor(now.minusSeconds(2));

        optimizer.queuePollingReturnedNoMessages(now.minusSeconds(1));

        assertThat(optimizer.shouldSkipPolling()).as("the claim looked for the message, so the backoff applies again").isTrue();
    }

    @Test
    void a_due_time_in_the_same_millisecond_as_the_claim_is_kept() {
        // Due times are tracked per millisecond; one in the claim's millisecond may still lie after the claim's now
        var optimizer = backingOffOptimizer();
        var claimedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS).plusNanos(100_000);
        optimizer.messageScheduledFor(claimedAt.plusNanos(500_000));

        optimizer.queuePollingReturnedNoMessages(claimedAt);

        assertThat(optimizer.shouldSkipPolling()).isFalse();
    }

    @Test
    void a_poll_that_claimed_messages_also_keeps_a_due_time_after_its_claims_now() {
        var optimizer = backingOffOptimizer();
        var now       = Instant.now();
        optimizer.messageScheduledFor(now.minusSeconds(1));

        optimizer.queuePollingReturnedMessages(List.of(), now.minusSeconds(2));
        // Back off again with a claim that, too, ran before the due time - only the due time can end it early
        optimizer.queuePollingReturnedNoMessages(now.minusSeconds(3));

        assertThat(optimizer.shouldSkipPolling()).isFalse();
    }

    @Test
    void a_report_without_a_claim_time_still_forgets_due_times_older_than_the_grace() {
        var optimizer = backingOffOptimizer();
        optimizer.messageScheduledFor(Instant.now().minusSeconds(1));

        optimizer.queuePollingReturnedNoMessages();

        assertThat(optimizer.shouldSkipPolling()).isTrue();
    }

    @Test
    void the_claim_time_methods_default_to_the_original_ones_for_existing_optimizers() {
        var calls = new ArrayList<String>();
        QueuePollingOptimizer optimizer = new QueuePollingOptimizer() {
            @Override
            public void queuePollingReturnedNoMessages() {
                calls.add("none");
            }

            @Override
            public void queuePollingReturnedMessage(QueuedMessage queuedMessage) {
            }

            @Override
            public void queuePollingReturnedMessages(List<QueuedMessage> queuedMessages) {
                calls.add("messages");
            }

            @Override
            public boolean shouldSkipPolling() {
                return false;
            }

            @Override
            public void messageAdded(QueuedMessage queuedMessage) {
            }
        };

        optimizer.queuePollingReturnedNoMessages(Instant.now());
        optimizer.queuePollingReturnedMessages(List.of(), Instant.now());

        assertThat(calls).containsExactly("none", "messages");
    }
}
