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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import java.time.Duration;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What {@link MessageQueue#releaseKeptOrderedUnit} did with one ordered unit a stop kept: a stop keeps a unit, its
 * lease row still naming the instance, when a key of it is still in its handler {@code shedGrace} after the stop began,
 * and nothing takes the unit before that handler returns, the instance's liveness lapses, the handler has been there
 * longer than {@code stuckHandlerTimeout}, or the unit is released on request through this operation.
 *
 * @param unit             the ordered unit
 * @param instanceId       the instance id whose lease row it was - the subscription's, which may carry a suffix
 * @param outcome          what was done; see {@link Outcome}
 * @param keysInHandler    the unit's keys in a handler, empty when none was. On {@link Outcome#RELEASED} read when the
 *                         release's flush took its batch of acknowledgements, before the row was released: a handler
 *                         that returned before that had its acknowledgement flushed and is not named, and no named
 *                         key's acknowledgement was flushed. On {@link Outcome#ALREADY_TAKEN} read when the release's
 *                         flush took its batch, or without a flush when the row was already found taken; as the row had
 *                         been taken under a newer fence, that flush may have been refused and acknowledged nothing, so
 *                         these keys do not say which messages are delivered again. On {@link Outcome#BUSY} as they
 *                         stood when asked. On {@link Outcome#RELEASED} their messages are delivered again by whoever
 *                         takes the unit next, possibly while these handlers still run them
 * @param longestInHandler how long the key handed to its handler first had been there, {@link Duration#ZERO} when no key
 *                         was in a handler
 */
public record KeptUnitRelease(int unit, String instanceId, Outcome outcome, List<String> keysInHandler, Duration longestInHandler) {

    public KeptUnitRelease {
        requireNonNull(instanceId, "No instanceId provided");
        requireNonNull(outcome, "No outcome provided");
        keysInHandler = List.copyOf(requireNonNull(keysInHandler, "No keysInHandler provided"));
        requireNonNull(longestInHandler, "No longestInHandler provided");
    }

    /**
     * What {@link MessageQueue#releaseKeptOrderedUnit} did with a kept unit.
     */
    public enum Outcome {
        /**
         * Its acknowledgements were flushed under the fence it was kept under, and its lease row released. Any instance
         * below its fair share may take it now, under a new fence.
         */
        RELEASED,
        /**
         * Its lease row no longer named the instance under the fence it was kept under: another instance had taken the
         * unit once this one's liveness lapsed, or a run under the same instance id had taken it over past
         * {@code stuckHandlerTimeout}. Nothing was released, and nothing waits for its handler any more.
         */
        ALREADY_TAKEN,
        /**
         * Nothing was done, as something else is working on the unit right now: the stop that kept it is still
         * flushing its acknowledgements, or another hand-back or release of it is running. Ask again.
         */
        BUSY
    }
}
