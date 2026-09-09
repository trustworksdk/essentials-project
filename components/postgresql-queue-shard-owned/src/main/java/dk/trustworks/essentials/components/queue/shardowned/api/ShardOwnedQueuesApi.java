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

import java.time.Duration;
import java.util.*;

/**
 * The administrative contract over the shard-owned queues in a process.
 *
 * <h2>What this is, and what {@link MessageQueue} is</h2>
 * {@link MessageQueue} is the engine: it is what an application calls, it is bound to one queue, it
 * trusts its caller, and it throws {@link java.sql.SQLException} because its caller is usually inside
 * a transaction that has to decide what a failure means. This interface is the operator's surface: it
 * is registry-scoped, every operation is authorised against a principal, payloads are withheld by
 * default, and nothing is checked. The layering mirrors {@code DurableQueuesApi} over
 * {@code DurableQueues} in {@code foundation} — this one delegates rather than reimplements, and
 * every method here has a {@link MessageQueue} method behind it.
 *
 * <h2>Every operation takes a queue name, and that is not incidental</h2>
 * {@code DurableQueuesApi} addresses messages by {@code QueueEntryId} alone, because that id is a
 * UUID and globally unique. {@link MessageId} is {@code (lane, shard, sequence)} and sequences are per
 * {@code (queue, shard)} — so {@code u-0-1} exists in every queue that has ever enqueued an unordered
 * message. Dropping the queue name would not make the API more convenient; it would make
 * {@code deleteMessage} delete an arbitrary queue's message. The name is mandatory for that reason,
 * and there is deliberately no {@code getQueueNameFor(messageId)} counterpart, because the question
 * has no answer.
 *
 * <h2>Roles</h2>
 * <table>
 *     <caption>Role required per operation</caption>
 *     <tr><th>Operation</th><th>Role (or {@code ESSENTIALS_ADMIN})</th></tr>
 *     <tr><td>reads</td><td>{@code QUEUE_READER}</td></tr>
 *     <tr><td>message payloads</td><td>{@code QUEUE_PAYLOAD_READER}, additionally</td></tr>
 *     <tr><td>delete, retry, dead-letter, resurrect, purge</td><td>{@code QUEUE_WRITER}</td></tr>
 * </table>
 * A reader without {@code QUEUE_PAYLOAD_READER} still gets the message — its id, key, attempt count,
 * timestamps and last error — with {@code payload} null. That is the shape most administration needs,
 * and it keeps message contents behind a role that can be granted separately from the ability to see
 * that a queue is stuck.
 *
 * <h2>A write races an in-flight delivery, and that cannot be fixed</h2>
 * Whether a message is being delivered right now lives in the owning consumer's memory, not in a
 * column. {@link #deleteMessage} on a message a handler is presently running will succeed, and the
 * handler will still finish. The engine already tolerates the acknowledgement that follows — it
 * resolves to zero rows and {@code stillOwns} disambiguates it — so nothing is corrupted; what is not
 * guaranteed is that the handler did not run. Treat these operations as what they are: interventions
 * on a running system, not transactional edits.
 */
public interface ShardOwnedQueuesApi {

    /**
     * The names of every registered queue.
     * <p>
     * From the registry, not from what this process has built: an operator asking what exists is not
     * asking what this pod happens to consume.
     */
    List<QueueName> getQueueNames(Object principal);

    /**
     * How much a queue is holding and whether anybody is reading it.
     * <p>
     * The two are served together deliberately — see {@link ApiShardOwnedQueueStatus}.
     *
     * @return empty if no queue is registered under that name
     */
    Optional<ApiShardOwnedQueueStatus> getQueueStatus(Object principal, QueueName queueName);

    /**
     * A single message by its id.
     *
     * @param messageId as rendered by {@link MessageId#toString()}, e.g. {@code u-3-1042}
     * @return empty if the queue does not exist, or the message is not in it
     */
    Optional<ApiShardOwnedMessage> getMessage(Object principal, QueueName queueName, MessageId messageId);

    /**
     * A page of the queue's dead letters, oldest first.
     *
     * @param limit capped by the implementation; ask for a page, not for the table
     */
    List<ApiShardOwnedMessage> getDeadLetterMessages(Object principal, QueueName queueName, int offset, int limit);

    /**
     * Remove a message from its lane without delivering it.
     *
     * @return whether a row was removed. {@code false} means it was already gone — delivered and
     *         acknowledged, or deleted by someone else
     */
    boolean deleteMessage(Object principal, QueueName queueName, MessageId messageId);

    /**
     * Make a message deliverable again after {@code delay}.
     * <p>
     * {@link Duration#ZERO} means immediately, which is the useful case after fixing whatever the
     * handler was failing on.
     *
     * @return whether the message was found and rescheduled
     */
    boolean retryMessage(Object principal, QueueName queueName, MessageId messageId, Duration delay);

    /**
     * Move a message out of its lane and into the dead-letter table, without delivering it again.
     *
     * @param reason recorded as the message's last error, so the dead-letter table says why a human
     *               put it there rather than showing the last handler failure
     * @return whether the message was found and moved
     */
    boolean markAsDeadLetterMessage(Object principal, QueueName queueName, MessageId messageId, String reason);

    /**
     * Move a dead letter back into its lane.
     * <p>
     * It re-enters at a <em>fresh</em> sequence, so it lands ahead of the owning consumer's cursor and
     * is picked up on the next read rather than waiting for a head sweep. For an ordered message that
     * means it is re-queued behind anything already pending for its key — resurrection restores
     * delivery, it does not restore the message's original position.
     *
     * @return whether a dead letter with that id was found and re-queued
     */
    boolean resurrectDeadLetterMessage(Object principal, QueueName queueName, MessageId messageId);

    /**
     * Delete everything in a queue: both lanes and its dead letters.
     * <p>
     * Irreversible, and it does not stop consumers — messages in flight when this runs are still
     * being handled.
     *
     * @return how many rows were removed
     */
    long purgeQueue(Object principal, QueueName queueName);
}
