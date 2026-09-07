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

import java.sql.SQLException;
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
 *     <dt>Acknowledge, delete, retry and dead-letter by message id, from any caller</dt>
 *     <dd>These assume any caller may act on any message, which is only true when every message
 *         carries a claim flag. Supporting them here would mean writing that flag back per message —
 *         the single largest cost the design removes. Under ownership these are operations on a
 *         {@link QueueSession}, which is the thing that actually holds the right to perform them.</dd>
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
     * Messages enqueued and not yet acknowledged, per lane. Cheap enough to poll for monitoring.
     */
    QueueDepth depth() throws SQLException;

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
