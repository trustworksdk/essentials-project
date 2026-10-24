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

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

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
 *                         flush took its batch, or without a flush when the row was already found taken; as the row no
 *                         longer named the instance under the fence it was kept under, that flush may have been refused
 *                         and acknowledged nothing, so these keys do not say which messages are delivered again. On
 *                         {@link Outcome#BUSY} as they stood when asked. On {@link Outcome#RELEASED} their messages are
 *                         delivered again by whoever takes the unit next, possibly while these handlers still run them
 * @param longestInHandler how long the key handed to its handler first had been there, {@link Duration#ZERO} when no key
 *                         was in a handler
 * @param charged          how many messages in a handler were charged an attempt before the release, as a takeover
 *                         would charge one: every message in a handler, however long it had been there. Each message is
 *                         charged once per kept entry, under the fence the unit was kept under, and counted here with
 *                         every other this entry has charged: a request asked again after a release that failed reports
 *                         the charge the earlier request committed, also once the handler has returned; asked again
 *                         while a key is still in its handler, the request makes the dead-letter moves that charge left
 *                         to make. {@code 0} on {@link Outcome#BUSY}, and when no key was in a handler and no earlier
 *                         attempt to release the unit committed a charge. On
 *                         {@link Outcome#ALREADY_TAKEN} what was charged before the release found the row taken - nothing
 *                         when the row was already taken when the charge was made, as the charge is fenced too
 * @param deadLetteredKeys the keys whose message that charge brought to {@code maxAttempts}, and which were dead-lettered
 *                         by it, empty when none was (and on {@link Outcome#BUSY}). A key named here is blocked behind
 *                         its dead letter - its later messages are dead-lettered behind it rather than delivered - until
 *                         its dead letters are resurrected ({@link MessageQueue#resurrectKey}) or deleted
 */
public record KeptUnitRelease(int unit,
                              String instanceId,
                              Outcome outcome,
                              List<String> keysInHandler,
                              Duration longestInHandler,
                              int charged,
                              List<String> deadLetteredKeys) {

    /**
     * Keeps unmodifiable copies of {@code keysInHandler} and {@code deadLetteredKeys}, and refuses a charge that cannot
     * be: a negative {@code charged}, or more {@code deadLetteredKeys} than {@code charged} - each dead-lettered key's
     * message is one of those charged.
     */
    public KeptUnitRelease {
        requireNonNull(instanceId, "No instanceId provided");
        requireNonNull(outcome, "No outcome provided");
        keysInHandler = List.copyOf(requireNonNull(keysInHandler, "No keysInHandler provided"));
        requireNonNull(longestInHandler, "No longestInHandler provided");
        requireTrue(charged >= 0, msg("charged must not be negative, was {}", charged));
        deadLetteredKeys = List.copyOf(requireNonNull(deadLetteredKeys, "No deadLetteredKeys provided"));
        requireTrue(deadLetteredKeys.size() <= charged,
                    msg("deadLetteredKeys names {} key(s), more than the {} message(s) charged", deadLetteredKeys.size(), charged));
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
         * unit once this one's liveness lapsed, a run under the same instance id had taken it over past
         * {@code stuckHandlerTimeout}, or an earlier hand-back or release of it, whose answer the instance stopped waiting
         * for, went through on the server after all. Nothing was released, and nothing waits for its handler any more.
         */
        ALREADY_TAKEN,
        /**
         * Nothing was released. Either something else is working on the unit right now - the stop that kept it is
         * still flushing its acknowledgements, or another hand-back or release of it is running - and nothing was done;
         * or a dead-letter move a charge before an earlier release of it left owed was still to be made when its row
         * was to be released, and a unit is released only once every such move is made, its lease row, as far as the
         * instance knows, still naming it. In that case this request may have flushed its acknowledgements and charged
         * its messages in a handler first: the keys it dead-lettered are named in a WARN, and, should a later request
         * release the unit, what this one charged is counted in that answer's {@link #charged()}; a hand-back or
         * takeover that gets there first gives no answer. Ask again.
         */
        BUSY
    }
}
