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
 *                               row no longer named the instance under the fence it was kept under - taken under a newer
 *                               fence meanwhile, or released by an earlier hand-back or release whose answer the instance
 *                               stopped waiting for; nothing released; {@code BUSY} - nothing released: the stop that
 *                               kept it is still flushing it, or another hand-back or release of it is running, and
 *                               nothing was done - ask again; or a dead-letter move an earlier release's charge left
 *                               owed was still to be made when its row was to be released - a guard behind the release
 *                               sites that a release on request does not normally reach, as it makes every owed move
 *                               before it releases: a {@code BUSY} that comes back on every request (its WARN that the
 *                               instance did not release the unit logged at most once a minute per unit, the repeats at
 *                               DEBUG) points at a defect in the engine, to be investigated, not retried (see
 *                               {@link KeptUnitRelease.Outcome#BUSY})
 * @param keysInHandler          the unit's keys still in a handler, as {@link KeptUnitRelease#keysInHandler()} reads them:
 *                               on {@code RELEASED} when the release's flush took its batch of acknowledgements, so a
 *                               handler that returned before that had its acknowledgement flushed and is not named, and
 *                               no named key's acknowledgement was flushed; on {@code ALREADY_TAKEN} when that flush took
 *                               its batch, or without a flush when the row was already found taken, and as the row was
 *                               no longer held under that fence, that flush may have been refused and acknowledged
 *                               nothing, so these keys do not say which messages are delivered again; on {@code BUSY} as
 *                               they stood when asked. After
 *                               {@code RELEASED}, whoever takes the unit next delivers their messages again, possibly
 *                               while these handlers still run them
 * @param longestInHandlerMillis how long the key handed to its handler first had been there; zero when none was
 * @param charged                how many messages in a handler were charged an attempt before the release, as
 *                               {@link KeptUnitRelease#charged()} counts them; zero on {@code BUSY}, and when no key was in a
 *                               handler and no earlier attempt to release the unit committed a charge
 * @param deadLetteredKeys       the keys whose message that charge brought to {@code maxAttempts} and dead-lettered, as
 *                               {@link KeptUnitRelease#deadLetteredKeys()} names them; empty when none was. Each is blocked
 *                               behind its dead letter until its dead letters are resurrected or deleted
 */
public record ApiShardOwnedKeptUnitRelease(QueueName queueName,
                                           int unit,
                                           String instanceId,
                                           String outcome,
                                           List<String> keysInHandler,
                                           long longestInHandlerMillis,
                                           int charged,
                                           List<String> deadLetteredKeys) {

    /**
     * Keeps unmodifiable copies of {@code keysInHandler} and {@code deadLetteredKeys}, so neither the lists given here
     * nor the ones {@link #keysInHandler()} and {@link #deadLetteredKeys()} return can change this record. A {@code null}
     * key in either is refused.
     * <p>
     * A {@code null} list becomes an empty one, unlike {@link KeptUnitRelease}, which refuses it: this is the admin
     * API's shape, which a client may build from a JSON answer that lacks the field (as {@code ApiTableStatistics}
     * does for its indexes), and an absent list is read as no key in a handler, or none dead-lettered. {@link #from}
     * never passes {@code null}, since {@link KeptUnitRelease} refuses it; build this record through {@link #from}, so
     * an empty {@link #keysInHandler()} means the engine found no key in a handler, and an empty
     * {@link #deadLetteredKeys()} that the release dead-lettered none.
     */
    public ApiShardOwnedKeptUnitRelease {
        keysInHandler = copyRefusingNullKeys(keysInHandler, "keysInHandler holds a null key");
        deadLetteredKeys = copyRefusingNullKeys(deadLetteredKeys, "deadLetteredKeys holds a null key");
    }

    private static List<String> copyRefusingNullKeys(List<String> keys, String nullKeyMessage) {
        return keys == null
               ? List.of()
               : keys.stream()
                     .map(key -> requireNonNull(key, nullKeyMessage))
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
                                                release.longestInHandler().toMillis(),
                                                release.charged(),
                                                release.deadLetteredKeys());
    }
}
