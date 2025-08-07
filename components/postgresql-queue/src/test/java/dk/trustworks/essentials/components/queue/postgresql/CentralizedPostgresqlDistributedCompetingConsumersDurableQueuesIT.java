/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.queue.postgresql.test_data.TestMessageFactory;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.shared.collections.Lists.partition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.*;

/**
 * Integration tests for distributed competing consumers using the centralized message fetcher
 */
class CentralizedPostgresqlDistributedCompetingConsumersDurableQueuesIT extends PostgresqlDistributedCompetingConsumersDurableQueuesIT {

    public static final int BATCH_SIZE = 500;

    @Override
    protected boolean useCentralizedMessageFetcher() {
        return true;
    }

    @Test
    public void test_distributed_queueing_and_dequeueing_multiple_consumers() {
        QueueName queueName1 = QueueName.of("TestQueue1");
        QueueName queueName2 = QueueName.of("TestQueue2");
        QueueName queueName3 = QueueName.of("TestQueue3");
        QueueName queueName4 = QueueName.of("TestQueue4");
        var       queuesList = List.of(queueName1, queueName2, queueName3, queueName4);

        var numberOfMessages = 10_000;
        int half         = numberOfMessages / 2;
        var unorderedMap = TestMessageFactory.createUnorderedMessages(half, queuesList);
        var orderedMap   = TestMessageFactory.createOrderedMessages(half, queuesList, half);
        var messages     = new ArrayList<Message>(numberOfMessages);

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            for (QueueName queueName : queuesList) {
                var unOrderedMessages = unorderedMap.get(queueName);
                messages.addAll(unOrderedMessages);
                for (List<Message> chunk : partition(unOrderedMessages, BATCH_SIZE)) {
                    var unOrderedIds = durableQueues1.queueMessages(queueName, chunk);
                    assertThat(unOrderedIds).hasSize(chunk.size());
                }

                var orderedMessages = orderedMap.get(queueName);
                messages.addAll(orderedMessages);
                for (List<OrderedMessage> chunk : partition(orderedMessages, BATCH_SIZE)) {
                    var orderedIds = durableQueues1.queueMessages(queueName, chunk);
                    assertThat(orderedIds).hasSize(chunk.size());
                }
            }
        });

        assertThat(messages).hasSize(numberOfMessages);
        assertThat(durableQueues1.getTotalMessagesQueuedFor(queueName1)).isGreaterThan(0);
        assertThat(durableQueues2.getTotalMessagesQueuedFor(queueName2)).isGreaterThan(0);
        var recordingQueueMessageHandler1   = new RecordingQueuedMessageHandler();
        var recordingQueueMessageHandler2   = new RecordingQueuedMessageHandler();
        var recordingQueueMessageHandler3   = new RecordingQueuedMessageHandler();
        var recordingQueueMessageHandler4   = new RecordingQueuedMessageHandler();


        var consumer1 = durableQueues1.consumeFromQueue(queueName1,
                                                        RedeliveryPolicy.fixedBackoff(Duration.ofMillis(1), 5),
                                                        10,
                                                        recordingQueueMessageHandler1
                                                       );

        var consumer2 = durableQueues2.consumeFromQueue(queueName2,
                                                        RedeliveryPolicy.fixedBackoff(Duration.ofMillis(1), 5),
                                                        10,
                                                        recordingQueueMessageHandler2
                                                       );

        var consumer3 = durableQueues1.consumeFromQueue(queueName3,
                                                        RedeliveryPolicy.fixedBackoff(Duration.ofMillis(1), 5),
                                                        10,
                                                        recordingQueueMessageHandler3
                                                       );

        var consumer4 = durableQueues2.consumeFromQueue(queueName4,
                                                        RedeliveryPolicy.fixedBackoff(Duration.ofMillis(1), 5),
                                                        10,
                                                        recordingQueueMessageHandler4
                                                       );


        waitAtMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(recordingQueueMessageHandler1.messages.size() +
                                                          recordingQueueMessageHandler2.messages.size() +
                                                          recordingQueueMessageHandler3.messages.size() +
                                                          recordingQueueMessageHandler4.messages.size())
                          .isEqualTo(numberOfMessages));
        var receivedMessages = new ArrayList<>(recordingQueueMessageHandler1.messages);
        receivedMessages.addAll(recordingQueueMessageHandler2.messages);
        receivedMessages.addAll(recordingQueueMessageHandler3.messages);
        receivedMessages.addAll(recordingQueueMessageHandler4.messages);

        var softAssertions = new SoftAssertions();
        softAssertions.assertThat(receivedMessages.stream().distinct().count()).isEqualTo(numberOfMessages);
        softAssertions.assertThat(receivedMessages).containsAll(messages);
        softAssertions.assertThat(messages).containsAll(receivedMessages);
        softAssertions.assertAll();

        // Check no messages are delivered after our assertions
        await()
                  .during(Duration.ofMillis(1990))
                  .atMost(Duration.ofSeconds(2000))
                  .until(() -> recordingQueueMessageHandler1.messages.size() +
                          recordingQueueMessageHandler2.messages.size() +
                          recordingQueueMessageHandler3.messages.size() +
                          recordingQueueMessageHandler4.messages.size() == numberOfMessages);

        consumer1.cancel();
        consumer2.cancel();
        consumer3.cancel();
        consumer4.cancel();
    }
}