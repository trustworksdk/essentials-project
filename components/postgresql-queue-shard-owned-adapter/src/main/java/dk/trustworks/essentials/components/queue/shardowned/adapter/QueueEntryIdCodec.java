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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Carries the queue name inside the {@link QueueEntryId}.
 *
 * <h2>Why the queue name has to be in there</h2>
 * Most of {@link DurableQueues}' by-id surface — {@code getQueuedMessage}, {@code deleteMessage},
 * {@code retryMessage}, {@code markAsDeadLetterMessage}, {@code resurrectDeadLetterMessage},
 * {@code acknowledgeMessageAsHandled} — takes a {@link QueueEntryId} and nothing else. That works for
 * {@code PostgresqlDurableQueues} because its ids are globally unique.
 * <p>
 * A shard-owned {@link MessageId} is {@code (lane, shard, sequence)} and sequences are per
 * {@code (queue, shard)}, so {@code u-0-1} exists in every queue that has ever enqueued an unordered
 * message. An adapter that ignored the difference would let {@code deleteMessage} delete an unrelated
 * queue's message, silently and with a successful return value.
 * <p>
 * {@link QueueEntryId} is a {@link dk.trustworks.essentials.types.CharSequenceType} — free-form text,
 * not a UUID — so the fix is to put the missing half in it: {@code <queueName>:<lane>-<shard>-<seq>},
 * for example {@code orders:u-3-1042}. Every by-id operation then resolves the queue first and the
 * message within it, and {@code getQueueNameFor} answers by reading rather than by searching.
 *
 * <h2>The separator, and why splitting is on the LAST one</h2>
 * A colon — and queue names routinely contain colons already: {@code InboxName.asQueueName()} yields
 * {@code Inbox:orders} and {@code OutboxName.asQueueName()} yields {@code Outbox:orders}, which are
 * exactly the queues this adapter exists to serve. Splitting on the first colon would truncate every
 * inbox and outbox name to {@code "Inbox"}, and the symptom would be "no such message" against a queue
 * that does exist — a wrong answer rather than an error.
 * <p>
 * Splitting on the last colon is unambiguous because a {@link MessageId} never contains one: its text
 * form is a lane character, a shard and a sequence, separated by hyphens. So whatever precedes the
 * final colon is the queue name, however many colons it has of its own.
 */
public final class QueueEntryIdCodec {

    private static final char SEPARATOR = ':';

    private QueueEntryIdCodec() {
    }

    public static QueueEntryId encode(QueueName queueName, MessageId messageId) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(messageId, "No messageId provided");
        // No check that the name is colon-free: 'Inbox:orders' and 'Outbox:orders' are the normal
        // shape here. decode() splits on the last colon, which a message id never contains.
        return QueueEntryId.of(queueName + String.valueOf(SEPARATOR) + messageId);
    }

    /**
     * @throws IllegalArgumentException if {@code queueEntryId} was not produced by {@link #encode}.
     *                                  Callers reaching this from outside get a message naming the
     *                                  expected shape, and — importantly — an id minted by a
     *                                  <em>different</em> {@code DurableQueues} implementation fails
     *                                  here rather than being half-understood
     */
    public static Decoded decode(QueueEntryId queueEntryId) {
        requireNonNull(queueEntryId, "No queueEntryId provided");
        var raw       = queueEntryId.toString();
        var separator = raw.lastIndexOf(SEPARATOR);
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new IllegalArgumentException(
                    "'" + raw + "' is not a shard-owned queue entry id - expected '<queueName>"
                            + SEPARATOR + "<lane>-<shard>-<sequence>', e.g. 'orders" + SEPARATOR + "u-3-1042'");
        }
        return new Decoded(QueueName.of(raw.substring(0, separator)),
                           MessageId.parse(raw.substring(separator + 1)));
    }

    public record Decoded(QueueName queueName, MessageId messageId) {
    }
}
