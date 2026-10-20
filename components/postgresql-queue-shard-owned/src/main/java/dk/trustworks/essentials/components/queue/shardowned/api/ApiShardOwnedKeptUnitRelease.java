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

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What {@link ShardOwnedQueuesApi#releaseKeptOrderedUnit} did with one ordered unit a stop kept, in the instance that
 * answered: see {@link KeptUnitRelease}.
 *
 * @param queueName              the queue the unit belongs to
 * @param unit                   the ordered unit
 * @param instanceId             the instance id whose lease row it was
 * @param outcome                {@code RELEASED} - its acknowledgements flushed and its lease row released, so any
 *                               instance below its fair share may take it under a new fence; {@code ALREADY_TAKEN} - the
 *                               row had been taken under a newer fence meanwhile, nothing released; {@code BUSY} - the stop
 *                               that kept it is still flushing it, or another hand-back or release of it is running, nothing
 *                               done, ask again
 * @param keysInHandler          the unit's keys in a handler when it was asked. After {@code RELEASED}, whoever takes the
 *                               unit next delivers their messages again, possibly while these handlers still run them
 * @param longestInHandlerMillis how long the key handed to its handler first had been there; zero when none was
 */
public record ApiShardOwnedKeptUnitRelease(QueueName queueName,
                                           int unit,
                                           String instanceId,
                                           String outcome,
                                           List<String> keysInHandler,
                                           long longestInHandlerMillis) {

    public static ApiShardOwnedKeptUnitRelease from(QueueName queueName, KeptUnitRelease release) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(release, "No release provided");
        return new ApiShardOwnedKeptUnitRelease(queueName,
                                                release.unit(),
                                                release.instanceId(),
                                                release.outcome().name(),
                                                release.keysInHandler(),
                                                release.longestInHandler().toMillis());
    }
}
