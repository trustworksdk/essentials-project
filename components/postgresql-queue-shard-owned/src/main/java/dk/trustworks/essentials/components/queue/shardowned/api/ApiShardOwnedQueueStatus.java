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
 * What a queue is holding and whether anybody is reading it, in one response.
 *
 * <h2>Why depth and health are one resource here</h2>
 * They answer half a question each, and every operational mistake this engine has produced came from
 * reading one without the other. A depth of 40 000 is normal under load and an outage when
 * {@code unownedShards} is non-zero; {@code unownedShards} of 4 is a rebalance in progress when depth
 * is falling and a stall when it is not. Two endpoints would let a dashboard show one and not the
 * other, which is exactly the failure being guarded against — so they are served together.
 *
 * @param unownedShards shards, across both lanes, that no live instance is reading. Persistently
 *                      above zero is the alerting condition; briefly non-zero is a rebalance
 * @param liveInstances instances heartbeating for this queue. Below the number of running processes
 *                      means two of them share an instance id, which halves the share each may hold
 * @param maxInstances  the most instances that can hold anything for this queue. The two lanes have
 *                      different ceilings — the unordered lane's is {@code shardCount}, which an
 *                      operator chooses and can grow, and the ordered lane's is its fixed unit space
 *                      — so this is the larger of the two. Deploying more than this leaves the extras
 *                      consuming nothing; it is a ceiling on horizontal scale, not just on throughput
 */
public record ApiShardOwnedQueueStatus(QueueName queueName,
                                       int shardCount,
                                       int orderedUnits,
                                       long unorderedDepth,
                                       long orderedDepth,
                                       long deadLetteredDepth,
                                       int unorderedShardsOwned,
                                       int orderedShardsOwned,
                                       int unownedShards,
                                       boolean fullyOwned,
                                       int liveInstances,
                                       int maxInstances) {

    public static ApiShardOwnedQueueStatus from(QueueName queueName, QueueDepth depth, QueueHealth health) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(depth, "No depth provided");
        requireNonNull(health, "No health provided");
        return new ApiShardOwnedQueueStatus(queueName,
                                            health.shardCount(),
                                            health.orderedUnits(),
                                            depth.unordered(),
                                            depth.ordered(),
                                            depth.deadLettered(),
                                            health.unorderedOwned(),
                                            health.orderedOwned(),
                                            health.unownedShards(),
                                            health.fullyOwned(),
                                            health.liveInstances(),
                                            Math.max(health.shardCount(), health.orderedUnits()));
    }
}
