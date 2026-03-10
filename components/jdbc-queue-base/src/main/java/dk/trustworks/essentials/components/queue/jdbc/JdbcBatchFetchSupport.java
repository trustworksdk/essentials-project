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

package dk.trustworks.essentials.components.queue.jdbc;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.jdbi.v3.core.statement.Query;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class JdbcBatchFetchSupport {
    private JdbcBatchFetchSupport() {
    }

    public static <F extends JdbcMessageMappingResult.FailedMessageMapping> void logFailedMappingsSummary(
            JdbcMessageMappingResult<F> mappingResult,
            Logger log) {
        if (mappingResult.failedMappings().isEmpty()) {
            return;
        }

        log.warn("Failed to deserialize {} messages during batch fetch. Failed QueueEntryIds: {}",
                 mappingResult.failedMappings().size(),
                 mappingResult.failedMappings().stream()
                              .map(failed -> failed.queueEntryId().toString())
                              .collect(Collectors.joining(", ")));
    }

    public static Map<QueueName, List<QueuedMessage>> groupMessagesByQueue(List<QueuedMessage> messages) {
        return messages.stream().collect(Collectors.groupingBy(QueuedMessage::getQueueName));
    }

    public static void forEachActiveQueueMessages(Collection<QueueName> activeQueues,
                                                  Map<QueueName, List<QueuedMessage>> byQueue,
                                                  BiConsumer<QueueName, List<QueuedMessage>> queueMessagesConsumer) {
        for (var queueName : activeQueues) {
            queueMessagesConsumer.accept(queueName, byQueue.getOrDefault(queueName, Collections.emptyList()));
        }
    }

    public static Map<QueueName, Integer> queueSizes(Map<QueueName, List<QueuedMessage>> byQueue) {
        return byQueue.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
    }

    public static void applyQueryBindings(Query query,
                                          Map<String, ?> singleValueBindings,
                                          Map<String, ? extends Collection<?>> listBindings) {
        for (var entry : singleValueBindings.entrySet()) {
            query.bind(entry.getKey(), entry.getValue());
        }
        for (var entry : listBindings.entrySet()) {
            query.bindList(entry.getKey(), entry.getValue());
        }
    }

    public static Optional<QueuePollingOptimizer> resolveQueuePollingOptimizer(QueueName queueName,
                                                                                Map<QueueName, Integer> availableWorkerSlotsPerQueue,
                                                                                Function<QueueName, QueuePollingOptimizer> queuePollingOptimizerLookup,
                                                                                Logger log) {
        var availableWorkerSlotsForThisQueue = availableWorkerSlotsPerQueue.get(queueName);
        if (availableWorkerSlotsForThisQueue == null || availableWorkerSlotsForThisQueue <= 0) {
            log.trace("[{}] Skipping queue as it has no available worker slots", queueName);
            return Optional.empty();
        }

        var optimizer = queuePollingOptimizerLookup.apply(queueName);
        if (optimizer == null) {
            log.trace("[{}] Skipping queue as it has no consumer", queueName);
            return Optional.empty();
        }

        if (optimizer.shouldSkipPolling()) {
            log.trace("[{}] skipping centralized polling", queueName);
            return Optional.empty();
        }

        return Optional.of(optimizer);
    }

    public static <MR extends JdbcMessageMappingResult<? extends JdbcMessageMappingResult.FailedMessageMapping>> List<QueuedMessage> fetchMessagesForQueue(
            boolean useOrderedUnorderedQuery,
            Supplier<MR> orderedFetch,
            Supplier<MR> unorderedFetch,
            Supplier<MR> combinedFetch,
            Consumer<MR> failedHandler) {
        if (useOrderedUnorderedQuery) {
            var mappingResult = orderedFetch.get();
            failedHandler.accept(mappingResult);
            var messages = mappingResult.successfulMessages();
            if (messages.isEmpty()) {
                mappingResult = unorderedFetch.get();
                failedHandler.accept(mappingResult);
                messages = mappingResult.successfulMessages();
            }
            return messages;
        } else {
            var mappingResult = combinedFetch.get();
            failedHandler.accept(mappingResult);
            return mappingResult.successfulMessages();
        }
    }

    public static void updateQueuePollingOptimizerAndLog(QueueName queueName,
                                                         QueuePollingOptimizer queuePollingOptimizer,
                                                         List<QueuedMessage> messagesForQueue,
                                                         Logger log) {
        if (messagesForQueue.isEmpty()) {
            queuePollingOptimizer.queuePollingReturnedNoMessages();
            log.trace("[{}] No messages fetched for this queue", queueName);
        } else {
            queuePollingOptimizer.queuePollingReturnedMessages(messagesForQueue);
            log.trace("[{}] Fetched {} messages for this queue", queueName, messagesForQueue.size());
        }
    }
}
