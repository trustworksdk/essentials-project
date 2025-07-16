/*
 *
 *  * Copyright 2021-2025 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package dk.trustworks.essentials.components.queue.postgresql.test_data;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.types.CorrelationId;

import java.util.*;
import java.util.stream.*;

public class TestMessageFactory {

    private TestMessageFactory() {}

    /**
     * Splits totalMessages evenly across the given queues, returning a map
     * from each QueueName to a List of unordered Message<?> instances.
     */
    public static Map<QueueName, List<Message>> createUnorderedMessages(
            int totalMessages,
            List<QueueName> queueNames
                                                                       ) {
        int qCount = queueNames.size();
        int base   = totalMessages / qCount;
        int extra  = totalMessages % qCount;

        Map<QueueName, List<Message>> result = new LinkedHashMap<>();
        for (int qi = 0; qi < qCount; qi++) {
            QueueName qn = queueNames.get(qi);
            int count = base + (qi < extra ? 1 : 0);
            int       finalQi = qi;
            List<Message> messages = IntStream.range(0, count)
                                              .mapToObj(i -> Message.of(
                                                     "Unordered-" + finalQi + "-" + i,
                                                     MessageMetaData.of(
                                                             "correlation_id", CorrelationId.random(),
                                                             "trace_id", UUID.randomUUID().toString()
                                                                       )
                                                                      ))
                                              .collect(Collectors.toList());
            result.put(qn, messages);
        }
        return result;
    }

    /**
     * Generate a map from each QueueName to a list of OrderedMessage,
     * splitting `totalMessages` evenly across the queues and across
     * `distinctKeysPerQueue` distinct keys.
     */
    public static Map<QueueName, List<OrderedMessage>> createOrderedMessages(
            int totalMessages,
            List<QueueName> queues,
            int distinctKeysPerQueue
                         ) {
        int qCount = queues.size();
        int perQueue = totalMessages / qCount;
        Map<QueueName, List<OrderedMessage>> result = new LinkedHashMap<>();
        for (int qi = 0; qi < qCount; qi++) {
            QueueName qn = queues.get(qi);
            List<OrderedMessage> msgs = new ArrayList<>(perQueue);
            // Pre-build your distinct key values
            List<String> distinctKeys = IntStream.range(0, distinctKeysPerQueue)
                                                 .mapToObj(k -> qn + "-K" + k)
                                                 .toList();
            // Round-robin assign messages to keys and orders
            int[] orderCounters = new int[distinctKeysPerQueue];
            for (int i = 0; i < perQueue; i++) {
                int keyIdx = i % distinctKeysPerQueue;
                msgs.add(OrderedMessage.of(
                        "Msg-" + qi + "-" + i,
                        distinctKeys.get(keyIdx),
                        orderCounters[keyIdx]++,
                        MessageMetaData.of(
                                "correlation_id", CorrelationId.random(),
                                "trace_id",       UUID.randomUUID().toString()
                                          )
                                          ));
            }
            result.put(qn, msgs);
        }
        return result;
    }
}
