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

import dk.trustworks.essentials.shared.Lifecycle;

import java.sql.*;
import java.time.Duration;
import java.util.*;

/**
 * The queue contract for the shard-owned engine.
 * <p>
 * A new interface rather than an implementation of the existing {@code DurableQueues}, because
 * roughly two fifths of that interface encodes decisions this design does not make. Adopting it
 * would not be compatibility, it would be reintroducing the mechanism the design exists to remove.
 * The reasoning, member by member, is recorded here so that a future reader can disagree with it on
 * the merits rather than assume it was never considered.
 *
 * <h2>What the existing interface required that this one does not</h2>
 * <dl>
 *     <dt>{@code parallelConsumers} on the consume operation</dt>
 *     <dd>Concurrency here is a product of shard count and per-key parallelism, both decided by the
 *         engine. A consumer-supplied thread count would be a no-op, and a knob that does nothing is
 *         worse than an absent one.</dd>
 *
 *     <dt>{@code TransactionalMode}</dt>
 *     <dd>Its two values exist because the old design could give either transactional atomicity or
 *         working retries, not both: joining the caller's transaction made a rollback revert the
 *         attempt count. Under a lease, the acknowledgement can join the caller's unit of work while
 *         attempt counting and dead-lettering stay outside its rollback scope, so both properties are
 *         available at once and there is nothing to choose between.</dd>
 *
 *     <dt>{@code getNextMessageReadyForDelivery}</dt>
 *     <dd>Named as a query, implemented as a claim-and-return. It bundles three separate requirements
 *         — a caller-controlled transaction boundary, a caller-driven loop, and a long-running
 *         handler — behind one signature. {@link #openSession} serves them explicitly instead.</dd>
 *
 *     <dt>Acknowledging a message by id from any caller</dt>
 *     <dd>Acknowledgement is what the owner does when a handler returns, and letting an arbitrary
 *         caller do it means the engine cannot know whether the work was done. That one stays a
 *         {@link QueueSession} operation, which is the thing that actually holds the right.
 *         <p>
 *         <b>The rest of the by-id family is now supported, and an earlier version of this javadoc
 *         was wrong to exclude it.</b> It argued that acting on a message by id requires a per-message
 *         claim flag, which conflated two different things: addressing a row, and knowing whether
 *         someone is working on it. A {@link MessageId} is {@code (lane, shard, seq)} and the primary
 *         key is {@code (queue_id, shard, seq)}, so addressing costs a point lookup and no flag at
 *         all. The claim write the design removes is paid once per <em>delivery</em>; an
 *         administrative write is paid once per <em>administrator</em>. Those are not the same
 *         frequency and should never have been priced as if they were.</dd>
 * </dl>
 *
 * <h2>What it required that this one keeps</h2>
 * Transactional enqueue, handler-driven consumption, per-key ordering, retry policy and dead letters,
 * depth and dead-letter inspection, and purge. Those are consumer needs rather than artefacts of
 * either implementation, and dropping them would be building a different product.
 */
public interface MessageQueue extends Lifecycle, AutoCloseable {

    /**
     * Enqueue a batch. Batching is in the signature rather than hidden behind a single-message
     * convenience, because the measurements are unambiguous: batching is worth 4 percentage points of
     * write volume and roughly 29× in wall-clock time, and an API that makes the batch the exception
     * teaches callers to pay that.
     */
    List<MessageId> enqueue(List<Message> messages) throws SQLException;

    default MessageId enqueue(Message message) throws SQLException {
        return enqueue(List.of(message)).getFirst();
    }

    /**
     * Enqueue inside a transaction the caller controls — the outbox case.
     * <p>
     * The messages are written on {@code connection} and nothing is committed here: <b>the caller's
     * commit decides</b>, so business writes and the enqueue land together or not at all. A rollback
     * takes the messages with it, and the wake-up notification goes with them, because it is issued
     * inside the same transaction rather than sent alongside it.
     * <p>
     * A {@code java.sql.Connection} rather than a unit-of-work type, deliberately. This module depends
     * on nothing but {@code shared}, and a connection is what every caller can produce — Spring's
     * {@code DataSourceUtils.getConnection}, a JDBI handle's {@code getConnection}, or a plain
     * {@code DataSource}. Taking a framework's transaction abstraction here would make the queue
     * depend on the framework rather than the other way round.
     * <p>
     * The connection must already be in a transaction ({@code autoCommit == false}). A connection in
     * autocommit mode cannot express "together with my business writes", which is the entire point,
     * so passing one is a programming error rather than a slower path.
     */
    List<MessageId> enqueue(Connection connection, List<Message> messages) throws SQLException;

    default MessageId enqueue(Connection connection, Message message) throws SQLException {
        return enqueue(connection, List.of(message)).getFirst();
    }

    /**
     * Consume with a handler. The engine leases shards, enforces per-key order, retries and
     * dead-letters according to {@code policy}, and acknowledges on the handler's behalf.
     *
     * @return a handle that stops consumption; closing it releases the leases
     */
    Subscription consume(MessageHandler handler, ConsumerOptions options) throws SQLException;

    /**
     * Take ownership explicitly and pull, for the cases the handler API cannot serve: a caller that
     * needs the message inside a transaction it controls, a caller that must drive its own loop, or a
     * handler that runs longer than any sensible lease.
     * <p>
     * A session, not a per-message claim, because the scope of ownership is the parameter that
     * matters and hiding it produces the wrong default. See {@link SessionScope}.
     */
    QueueSession openSession(SessionScope scope, Duration leaseDuration) throws SQLException;

    /**
     * Attach cross-cutting instrumentation — metrics, tracing, audit logging.
     * <p>
     * Must be called before {@link #consume}; observers are read when a subscription starts, so one
     * added afterwards would silently see nothing.
     */
    MessageQueue addObserver(QueueObserver observer);

    /**
     * Attach an interceptor to the enqueue and delivery paths.
     * <p>
     * Unlike an observer, an interceptor can change what happens — see
     * {@link MessageQueueInterceptor} for the distinction and for why only those two operations are
     * interceptable. Ordered by {@link dk.trustworks.essentials.shared.interceptor.InterceptorOrder}.
     * <p>
     * Must be called before {@link #consume}, for the same reason observers must.
     */
    MessageQueue addInterceptor(MessageQueueInterceptor interceptor);

    /**
     * Messages enqueued and not yet acknowledged, per lane. Cheap enough to poll for monitoring.
     */
    QueueDepth depth() throws SQLException;

    /**
     * Whether this queue is being served — shards with a live owner, and instances alive.
     * <p>
     * Depth says how much work is waiting; this says whether anybody is doing it. They are different
     * questions and only the second catches a queue that has quietly stopped being consumed. Cheap
     * enough to poll: one query against the lease table and one against the membership table, both
     * of which hold a handful of rows per queue.
     */
    QueueHealth health() throws SQLException;

    /**
     * Read one message by id, for an admin surface or an operator with a support ticket.
     * <p>
     * A primary-key point lookup that costs delivery nothing. Returns empty if the message has been
     * handled, deleted, or moved to the dead letter lane — see {@link #deadLetters}.
     */
    Optional<QueuedMessage> getMessage(MessageId messageId) throws SQLException;

    /**
     * Remove one message without delivering it.
     * <p>
     * <b>Races a delivery in progress, and cannot be made not to.</b> Whether a message is currently
     * in a handler lives in the owner's memory, so nothing this call can read will tell it. Deleting
     * a message the owner is holding means the handler still runs to completion and its
     * acknowledgement then matches no row — which the engine already tolerates, because a fenced-out
     * owner produces the same thing. The message is gone either way; what is not guaranteed is that
     * its handler did not run.
     *
     * @return false if it was already gone
     */
    boolean deleteMessage(MessageId messageId) throws SQLException;

    /**
     * Make a message deliverable again after {@code delay}, resetting its attempt count.
     * <p>
     * For an operator releasing a message stuck behind a long backoff. Same race as
     * {@link #deleteMessage}: if a handler is running, it may complete and acknowledge, and the retry
     * is then lost rather than doubled.
     *
     * @return false if the message no longer exists
     */
    boolean retryMessage(MessageId messageId, Duration delay) throws SQLException;

    /**
     * Park a message in the dead letter lane whatever its attempt count, for a poison message an
     * operator wants out of the way now rather than after the policy is exhausted.
     *
     * @return false if the message no longer exists
     */
    boolean markAsDeadLetter(MessageId messageId, String reason) throws SQLException;

    /**
     * A page of the messages currently in this queue, across both lanes, for an operator browsing it.
     * <p>
     * <b>Ordered by {@code (lane, shard, sequence)}, which is stable rather than chronological.</b>
     * There is no global arrival order to report — each shard carries its own sequence and the design
     * is that no reader needs a total order — so this reports the one property paging requires: a row
     * is never returned twice and never skipped. Do not present it to an operator as "oldest first".
     * <p>
     * An administrative operation, priced per administrator. A deep offset sorts this queue's rows;
     * delivery never does, and nothing here is on the delivery path.
     *
     * @param offset    rows to skip
     * @param limit     maximum rows to return
     * @param ascending direction over that ordering. Since the ordering <em>is</em> the message id,
     *                  this is exactly "sort by id", which is what {@code DurableQueues} asks for
     */
    List<QueuedMessage> messages(int offset, int limit, boolean ascending) throws SQLException;

    List<DeadLetter> deadLetters(int offset, int limit) throws SQLException;

    /**
     * Return a dead letter to its lane for redelivery, resetting its attempt count.
     *
     * @return true if it was still parked and has been resurrected
     */
    boolean resurrect(MessageId messageId) throws SQLException;

    /**
     * Remove everything, both lanes and dead letters. Administrative, not a delivery operation.
     *
     * @return the number of messages removed
     */
    long purge() throws SQLException;

    /**
     * {@inheritDoc}
     * <p>
     * Equivalent to {@link #stop()}, so the queue can be used in a try-with-resources as well as
     * managed by a container. {@code Lifecycle} is the contract the rest of this project's long-lived
     * resources expose — {@code DurableQueues} and {@code DurableQueueConsumer} both extend it — and a
     * queue that only offered {@code close()} could not be started and stopped by whatever manages it.
     */
    @Override
    default void close() {
        stop();
    }
}
