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

package dk.trustworks.essentials.components.queue.shardowned.api;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What the queue's consumers have done <b>in the instance answering the request</b>.
 *
 * @param runningInThisInstance whether this instance consumes any of the queue. When false every
 *                              counter below is zero and means nothing — the queue is served
 *                              elsewhere, which {@code ApiShardOwnedQueueStatus.unownedShards} is the
 *                              field to check. Carried explicitly because a console cannot otherwise
 *                              tell "nothing happened here" from "nothing happened"
 */
public record ApiShardOwnedQueueStatistics(QueueName queueName,
                                           boolean runningInThisInstance,
                                           long delivered,
                                           long handlerFailures,
                                           long retriesScheduled,
                                           long retriesDispatched,
                                           long deadLettered,
                                           long orderViolations,
                                           long sweepRecoveries,
                                           long shardsAcquired,
                                           long shardsReleased,
                                           long leasesLost,
                                           long watermarkCapped,
                                           long keysBlockedByDeadLetter,
                                           long messagesPoisonedBehindDeadLetter) {

    public static ApiShardOwnedQueueStatistics from(QueueName queueName, QueueStatistics statistics, boolean running) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(statistics, "No statistics provided");
        return new ApiShardOwnedQueueStatistics(queueName,
                                                running,
                                                statistics.delivered(),
                                                statistics.handlerFailures(),
                                                statistics.retriesScheduled(),
                                                statistics.retriesDispatched(),
                                                statistics.deadLettered(),
                                                statistics.orderViolations(),
                                                statistics.sweepRecoveries(),
                                                statistics.shardsAcquired(),
                                                statistics.shardsReleased(),
                                                statistics.leasesLost(),
                                                statistics.watermarkCapped(),
                                                statistics.keysBlockedByDeadLetter(),
                                                statistics.messagesPoisonedBehindDeadLetter());
    }
}
