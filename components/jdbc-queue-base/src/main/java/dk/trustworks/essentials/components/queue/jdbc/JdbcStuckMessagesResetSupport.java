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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.slf4j.Logger;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class JdbcStuckMessagesResetSupport {
    private JdbcStuckMessagesResetSupport() {
    }

    public static void resetMessagesStuckBeingDeliveredAcrossMultipleQueues(Collection<QueueName> queueNames,
                                                                            TransactionalMode transactionalMode,
                                                                            int messageHandlingTimeoutMs,
                                                                            ConcurrentMap<QueueName, Instant> lastResetStuckMessagesCheckTimestamps,
                                                                            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                                            String resetSql,
                                                                            Logger log) {
        Objects.requireNonNull(queueNames, "No queueNames provided");
        if (transactionalMode != TransactionalMode.SingleOperationTransaction || queueNames.isEmpty()) {
            return;
        }

        log.trace("resetMultipleQueuesStuckBeingDelivered called for queues: {}", queueNames);
        var now = Instant.now();
        var queuesToReset = queueNames.stream()
                                      .filter(queueName -> {
                                          var lastReset = lastResetStuckMessagesCheckTimestamps.get(queueName);
                                          return lastReset == null ||
                                                  Duration.between(now, lastReset).abs().toMillis() > messageHandlingTimeoutMs;
                                      })
                                      .collect(Collectors.toList());

        if (queuesToReset.isEmpty()) {
            log.trace("No stuck messages to reset across multiple queues: {}", queueNames);
            return;
        }

        log.debug("Looking for messages stuck marked as isBeingDelivered across queues: {}", queuesToReset);

        var queueNamesForQuery = queuesToReset.stream()
                                              .map(QueueName::toString)
                                              .toList();

        var numberOfChanges = unitOfWorkFactory.getRequiredUnitOfWork().handle().createUpdate(resetSql)
                                               .bind("threshold", now.minusMillis(messageHandlingTimeoutMs))
                                               .bind("error", "Handler Processing of the Message was determined to have Timed Out")
                                               .bind("now", now)
                                               .bindList("queueNames", queueNamesForQuery)
                                               .execute();

        if (numberOfChanges > 0) {
            log.debug("Reset {} messages stuck marked as isBeingDelivered across queues: {}",
                      numberOfChanges,
                      queuesToReset);
        } else {
            log.debug("No stuck messages found across queues: {}", queuesToReset);
        }

        queuesToReset.forEach(queueName -> lastResetStuckMessagesCheckTimestamps.put(queueName, now));
    }

    public static void resetMessagesStuckBeingDelivered(QueueName queueName,
                                                         TransactionalMode transactionalMode,
                                                         int messageHandlingTimeoutMs,
                                                         ConcurrentMap<QueueName, Instant> lastResetStuckMessagesCheckTimestamps,
                                                         HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                         String resetSql,
                                                         Logger log) {
        if (transactionalMode != TransactionalMode.SingleOperationTransaction) {
            return;
        }

        var now = Instant.now();
        var lastStuckMessageResetTimestamp = lastResetStuckMessagesCheckTimestamps.get(queueName);
        if (lastStuckMessageResetTimestamp == null ||
                Duration.between(now, lastStuckMessageResetTimestamp).abs().toMillis() > messageHandlingTimeoutMs) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Looking for messages stuck marked as isBeingDelivered. Last check was performed: {}",
                          queueName, lastStuckMessageResetTimestamp);
            }

            var numberOfChanges = unitOfWorkFactory.getRequiredUnitOfWork().handle().createUpdate(resetSql)
                                                   .bind("threshold", now.minusMillis(messageHandlingTimeoutMs))
                                                   .bind("error", "Handler Processing of the Message was determined to have Timed Out")
                                                   .bind("now", now)
                                                   .execute();
            if (numberOfChanges > 0) {
                log.debug("[{}] Reset {} messages stuck marked as isBeingDelivered", queueName, numberOfChanges);
            } else {
                log.debug("[{}] Didn't find any messages being stuck marked as isBeingDelivered", queueName);
            }
            lastResetStuckMessagesCheckTimestamps.put(queueName, now);
        }
    }
}
