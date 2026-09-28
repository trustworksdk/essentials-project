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

/**
 * A message the redelivery policy gave up on, with enough context for a human to decide what to do.
 *
 * <h2>Two kinds of row, on the ordered lane</h2>
 * A dead letter is usually a message that was tried and failed, and {@code lastError} is its own
 * cause. On the ordered lane there is a second kind: a message that <b>never ran</b>, because an
 * earlier {@code key_order} for its key was dead-lettered and a key never advances past one.
 * {@link #neverDelivered()} separates them.
 * <p>
 * The distinction is operational, not cosmetic: a queue full of the first kind means a handler is
 * broken, and a queue full of the second means <em>one</em> message is, and everything behind it is
 * waiting on a decision about that one. {@code attempts} does not separate them — a takeover bumps it
 * on rows that were never delivered, so a message that never ran can still show attempts.
 *
 * @param blockedByKeyOrder the {@code key_order} of the dead letter this message is stuck behind, or
 *                          null when the message was itself tried and failed. A column on the row
 *                          rather than a convention inside {@code lastError}: the error text is for a
 *                          human, and a discriminator parsed out of prose is one bad edit from
 *                          silently reclassifying every row.
 */
public record DeadLetter(MessageId id, String key, byte[] payload, int payloadType, int attempts, String lastError,
                         Long blockedByKeyOrder) {

    /**
     * The error recorded for a message parked because its key was already blocked.
     */
    public static final String BLOCKED_BEHIND_DEAD_LETTER = "never delivered: the key was blocked by an earlier dead letter";

    /**
     * True when this message was never handed to a handler — it was moved here because an earlier
     * {@code key_order} for its key is dead-lettered. Resurrect that one first; this message becomes
     * deliverable again once nothing lower for its key is parked.
     */
    public boolean neverDelivered() {
        return blockedByKeyOrder != null;
    }
}
