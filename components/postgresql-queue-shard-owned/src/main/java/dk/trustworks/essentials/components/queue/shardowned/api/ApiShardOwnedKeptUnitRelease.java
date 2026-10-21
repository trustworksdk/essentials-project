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
 * @param keysInHandler          the unit's keys still in a handler, as {@link KeptUnitRelease#keysInHandler()} reads them:
 *                               on {@code RELEASED} when the release's flush took its batch of acknowledgements, so a
 *                               handler that returned before that had its acknowledgement flushed and is not named, and
 *                               no named key's acknowledgement was flushed; on {@code ALREADY_TAKEN} when that flush took
 *                               its batch, or without a flush when the row was already found taken, and as the row had
 *                               been taken under a newer fence, that flush may have been refused and acknowledged
 *                               nothing, so these keys do not say which messages are delivered again; on {@code BUSY} as
 *                               they stood when asked. After
 *                               {@code RELEASED}, whoever takes the unit next delivers their messages again, possibly
 *                               while these handlers still run them
 * @param longestInHandlerMillis how long the key handed to its handler first had been there; zero when none was
 */
public record ApiShardOwnedKeptUnitRelease(QueueName queueName,
                                           int unit,
                                           String instanceId,
                                           String outcome,
                                           List<String> keysInHandler,
                                           long longestInHandlerMillis) {

    /**
     * Keeps an unmodifiable copy of {@code keysInHandler}, so neither the list given here nor the one
     * {@link #keysInHandler()} returns can change this record. A {@code null} key in it is refused.
     * <p>
     * A {@code null} list becomes an empty one, unlike {@link KeptUnitRelease}, which refuses it: this is the admin
     * API's shape, which a client may build from a JSON answer that lacks the field (as {@code ApiTableStatistics}
     * does for its indexes), and an absent list is read as no key in a handler. {@link #from} never passes
     * {@code null}, since {@link KeptUnitRelease} refuses it; build this record through {@link #from}, so an empty
     * {@link #keysInHandler()} means the engine found no key in a handler.
     */
    public ApiShardOwnedKeptUnitRelease {
        keysInHandler = keysInHandler == null
                        ? List.of()
                        : keysInHandler.stream()
                                       .map(key -> requireNonNull(key, "keysInHandler holds a null key"))
                                       .toList();
    }

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
