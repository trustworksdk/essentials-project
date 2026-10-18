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

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
// Single-type imports, not the package: shardowned.spi and foundation...queue both export QueueName,
// Message and QueuedMessage, and this class deals in the foundation's.
import dk.trustworks.essentials.components.queue.shardowned.spi.ConsumerOptions;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageHandler;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue;
import dk.trustworks.essentials.components.queue.shardowned.spi.RedeliveryRequested;
import dk.trustworks.essentials.components.queue.shardowned.spi.Subscription;
import dk.trustworks.essentials.shared.Exceptions;
import org.slf4j.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A {@link DurableQueueConsumer} over a shard-owned {@link Subscription}.
 *
 * <h2>Retry is the engine's, not the foundation's</h2>
 * {@code DefaultDurableQueueConsumer} implements redelivery itself: it counts attempts, consults the
 * {@link RedeliveryPolicy}, and calls back into {@code DurableQueues} to reschedule or dead-letter.
 * This consumer does none of that, because the engine already does all of it — per shard, under the
 * owner's fence, with the schedule held in memory as well as in the row. Running both would produce
 * two independent retry clocks over one message.
 * <p>
 * So the policy is translated once, at subscription time, into the engine's {@link ConsumerOptions},
 * and the engine owns the outcome from there. The attempt limit is copied; the wait before each retry is
 * not, it is the policy's own {@link RedeliveryPolicy#calculateNextRedeliveryDelay(int)}, so every
 * strategy produces the same waits here as on the other engines.
 */
class ShardOwnedDurableQueueConsumer implements DurableQueueConsumer {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedDurableQueueConsumer.class);

    private final ConsumeFromQueue               operation;
    private final MessageQueue                   queue;
    private final JSONSerializer                 jsonSerializer;
    private final Consumer<DurableQueueConsumer> onCancel;
    private final DeliveryDispatch               delivery;

    /**
     * How long a {@link #stop()} waits for a {@link #start()} still subscribing: the 2 s each lane's own stop waits for
     * that lane's start ({@code ShardOwnedQueue.stop()}), and {@code PostgresqlMessageQueue.stop()} for a
     * {@code consume()}. Also how long a {@link #start()} waits for the lock before it logs that it is still waiting.
     */
    static final Duration STOP_WAITS_FOR_START = Duration.ofSeconds(2);

    /**
     * Held across the whole of {@link #start()} and {@link #stop()}, so they run one at a time. A lock rather than this
     * object's monitor, which it used to be, because a stop must be able to stop waiting for it: a start holds it across
     * the queue's {@code consume()}, which registers the instance and leases units on the database, and nothing bounds
     * that - a statement caught by a network partition blocks until the driver's {@code socketTimeout}, and without one
     * indefinitely.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    /**
     * Guards {@link #holder} and {@link #stopOwed}. Held for a few reads and writes of them only.
     */
    private final Object        handOver      = new Object();
    private       Holder        holder        = Holder.NONE;
    /**
     * Whether a {@link #stop()} that did not get {@link #lifecycleLock} has stopped the consumer while a {@link #start()}
     * was subscribing, so the start must stop the subscription the queue returns.
     */
    private       boolean       stopOwed;

    private volatile Subscription subscription;

    ShardOwnedDurableQueueConsumer(ConsumeFromQueue operation,
                                   MessageQueue queue,
                                   JSONSerializer jsonSerializer,
                                   Consumer<DurableQueueConsumer> onCancel,
                                   DeliveryDispatch delivery) {
        this.operation = requireNonNull(operation, "No operation provided");
        this.queue = requireNonNull(queue, "No queue provided");
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
        this.onCancel = requireNonNull(onCancel, "No onCancel provided");
        this.delivery = requireNonNull(delivery, "No delivery provided");
    }

    /**
     * How a delivery reaches its handler.
     * <p>
     * {@link ShardOwnedDurableQueues} supplies this so that the handler is invoked inside the
     * {@code HandleQueuedMessage} interceptor chain, which is where an interceptor expects to sit on
     * the other engine too. Kept as a parameter rather than a reference back to the adapter so that
     * this class stays testable without one.
     */
    @FunctionalInterface
    interface DeliveryDispatch {
        void handle(QueuedMessage message, QueuedMessageHandler messageHandler);
    }

    @Override
    public QueueName queueName() {
        return operation.getQueueName();
    }

    @Override
    public String consumerName() {
        return operation.getConsumerName();
    }

    @Override
    public RedeliveryPolicy getRedeliveryPolicy() {
        return operation.getRedeliveryPolicy();
    }

    /**
     * {@inheritDoc}
     * <p>
     * A {@link #stop()} that gave up waiting for this start, still subscribing, has stopped the consumer without it. This
     * start then stops the subscription the queue returns, logging at WARN rather than throwing what that stop throws,
     * since the stop's caller has already returned, and returns with the consumer not started.
     * <p>
     * A start called meanwhile - {@link ShardOwnedDurableQueues#start()} restarts the same consumer objects - waits for
     * that earlier start to return - as long as the queue's {@code consume()} takes, then the stop of the subscription it
     * returned - the thread's interrupt included: once it has waited {@link #STOP_WAITS_FOR_START} it logs one WARN naming
     * the consumer and the queue, and goes on waiting ({@link #lockForStart}).
     */
    @Override
    public void start() {
        lockForStart();
        try {
            if (subscription != null) {
                return;
            }
            synchronized (handOver) {
                holder = Holder.STARTING;
            }
            Subscription subscribed;
            boolean      owed;
            try {
                subscribed = queue.consume(new MessageHandler() {
                    @Override
                    public void handle(MessageId messageId, String key, byte[] payload, int payloadType) {
                        throw new IllegalStateException("The engine delivers with the key order");
                    }

                    @Override
                    public void handle(MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
                        deliver(messageId, key, keyOrder, payload, payloadType);
                    }
                }, toConsumerOptions(operation));
            } catch (Exception e) {
                throw new DurableQueueException("Failed to start consumer '" + consumerName() + "'", e, queueName());
            } finally {
                synchronized (handOver) {
                    owed = stopOwed;
                    stopOwed = false;
                    holder = Holder.NONE;
                }
            }
            if (owed) {
                stopForAStopThatDidNotWait(subscribed);
                return;
            }
            subscription = subscribed;
            log.info("Consumer '{}' started on queue '{}'", consumerName(), queueName());
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Take {@link #lifecycleLock} for {@link #start()}, waiting as long as it takes - as a plain {@code lock()} would, the
     * thread's interrupt deferred and set again once the lock is held - but not silently: once it has waited
     * {@link #STOP_WAITS_FOR_START}, it logs one WARN saying what holds the lock, and goes on waiting. A stop holding it is
     * bounded by the subscription's own stop, and so is an earlier start stopping the subscription a stop left it. An
     * earlier start still in the queue's {@code consume()} is not: that blocks as long as a statement does.
     */
    private void lockForStart() {
        var interrupted = false;
        var warned      = false;
        try {
            while (true) {
                try {
                    if (lifecycleLock.tryLock(STOP_WAITS_FOR_START.toMillis(), TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    // Waited on regardless, as lock() would; the flag is set again once the lock is held.
                    interrupted = true;
                    continue;
                }
                if (!warned) {
                    warned = true;
                    Holder heldBy;
                    synchronized (handOver) {
                        heldBy = holder;
                    }
                    if (heldBy == Holder.STOPPING) {
                        log.warn("Consumer '{}' has waited {} and is still waiting to start on queue '{}': a stop of the consumer is "
                                         + "still stopping its subscription",
                                 consumerName(), STOP_WAITS_FOR_START, queueName());
                    } else {
                        // STARTING, or NONE: past the budget, NONE is almost always an earlier start stopping the subscription
                        // a stop left it (the lock is unfair, so it may have just changed hands). The text covers both.
                        log.warn("Consumer '{}' has waited {} and is still waiting to start on queue '{}': an earlier start of the "
                                         + "consumer still holds its lifecycle - either still in the queue's consume(), registering the "
                                         + "instance and leasing units, or stopping the subscription consume() returned, for a stop that "
                                         + "gave up waiting for it. Nothing here bounds the consume(): a statement that hangs ends at the "
                                         + "driver's socketTimeout, and without one never",
                                 consumerName(), STOP_WAITS_FOR_START, queueName());
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Called while a {@link #start()} on another thread is still subscribing - the queue's {@code consume()} registers the
     * instance and leases units on the database, and nothing bounds that - this waits for it up to
     * {@link #STOP_WAITS_FOR_START}, and no longer; on an interrupted thread not at all, returning with the interrupt flag
     * still set. Past that, the consumer is not started from then on, and the start stops the subscription itself once
     * {@code consume()} returns. Until then, a lane that {@code consume()} has already started may still deliver to the
     * handler. A stop called while another stop is stopping the subscription past that wait returns at once.
     */
    @Override
    public void stop() {
        var     interrupted = false;
        boolean locked;
        try {
            locked = lifecycleLock.tryLock(STOP_WAITS_FOR_START.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Restored, so the caller still sees it.
            Thread.currentThread().interrupt();
            interrupted = true;
            locked = false;
        }
        while (!locked) {
            Holder heldBy;
            synchronized (handOver) {
                heldBy = holder;
                if (heldBy == Holder.STARTING) {
                    stopOwed = true;
                }
            }
            if (heldBy == Holder.STARTING) {
                if (interrupted) {
                    log.warn("Consumer '{}' was interrupted while its stop waited for its start on queue '{}' to subscribe, and "
                                     + "stopped without it. The start stops the subscription once the queue's consume() returns",
                             consumerName(), queueName());
                } else {
                    log.warn("Consumer '{}' gave up waiting {} for its start on queue '{}' to subscribe, and stopped without it. "
                                     + "The start stops the subscription once the queue's consume() returns",
                             consumerName(), STOP_WAITS_FOR_START, queueName());
                }
                return;
            }
            if (heldBy == Holder.STOPPING) {
                return;
            }
            // Holder.NONE: whoever holds the lock has just taken it or is just letting go of it, or is a start stopping the
            // subscription a stop left it. The consumer may still be started (a start about to set its subscription, a stop
            // about to stop it), so take the lock and look: returning here, as on STOPPING, could leave it running.
            Thread.onSpinWait();
            locked = lifecycleLock.tryLock();
        }
        try {
            if (subscription == null) {
                return;
            }
            synchronized (handOver) {
                holder = Holder.STOPPING;
            }
            try {
                subscription.stop();
            } finally {
                // Dropped even when the stop throws: a subscription's stop throws only once both of its lanes have stopped (a
                // QueueObserver failing for a release), and one left here made every later start() return without consuming.
                subscription = null;
                synchronized (handOver) {
                    holder = Holder.NONE;
                }
            }
            log.info("Consumer '{}' stopped on queue '{}'", consumerName(), queueName());
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * What the holder of {@link #lifecycleLock} is doing, for a {@link #stop()} that did not get it.
     */
    private enum Holder {
        /**
         * A start or stop that found nothing to do, or one that has just taken the lock or is about to let go of it - all
         * brief - or a start stopping the subscription a stop that did not wait for it left it, which lasts as long as
         * that subscription's stop. The stop tries the lock again.
         */
        NONE,
        /** A {@link #start()} subscribing: the stop leaves stopping the subscription to it. */
        STARTING,
        /** A {@link #stop()} stopping the subscription: the stop has nothing left to do. */
        STOPPING
    }

    private void stopForAStopThatDidNotWait(Subscription subscribed) {
        try {
            subscribed.stop();
            log.info("Consumer '{}' stopped on queue '{}' as it finished starting, for a stop that did not wait for it", consumerName(), queueName());
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Consumer '{}' failed stopping its subscription to queue '{}' for a stop that did not wait for its start. That stop "
                             + "has returned, so this is logged rather than thrown; both lanes have still been stopped",
                     consumerName(), queueName(), e);
        }
    }

    @Override
    public boolean isStarted() {
        var current = subscription;
        return current != null && current.isStarted();
    }

    @Override
    public void cancel() {
        try {
            stop();
        } finally {
            // Deregistered even when the stop throws, or the DurableQueues' next start() would start a cancelled consumer
            onCancel.accept(this);
        }
    }

    /**
     * One delivery.
     * <p>
     * Returning normally acknowledges the message; throwing hands it back to the engine's retry
     * schedule. There is no third outcome, which is why a handler asking for redelivery has to be
     * turned into a throw — see {@link ShardOwnedQueuedMessage#markForRedeliveryIn}.
     * <p>
     * The handler is reached through {@link DeliveryDispatch} rather than called here, so that a
     * {@code HandleQueuedMessage} interceptor wraps it. An interceptor that does not proceed therefore
     * returns normally, and the message is acknowledged as handled — the same outcome as a handler
     * that returns without doing anything.
     */
    private void deliver(MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
        if (payloadType != MessageEnvelope.FORMAT_VERSION) {
            throw new DurableQueueException(
                    "Message was written in envelope format " + payloadType + ", and this adapter reads format "
                            + MessageEnvelope.FORMAT_VERSION
                            + ". A message enqueued by something other than this adapter cannot be delivered through it.",
                    queueName());
        }
        // The order matters, not only the key: an EventProcessor's forwarded event is a reference whose order is the
        // event's position in its stream. Rebuilt as 0, every reference resolved to the aggregate's first event
        var message = MessageEnvelope.deserialize(jsonSerializer, payload, key, keyOrder);
        var queuedMessage = ShardOwnedQueuedMessage.beingDelivered(queueName(),
                                                                   QueueEntryIdCodec.encode(queueName(), messageId),
                                                                   message);

        delivery.handle(queuedMessage, operation.getQueueMessageHandler());

        if (queuedMessage.isManuallyMarkedForRedelivery()) {
            throw new ManualRedeliveryRequested(queuedMessage.getRedeliveryDelay());
        }
    }

    static ConsumerOptions toConsumerOptions(ConsumeFromQueue operation) {
        var policy   = operation.getRedeliveryPolicy();
        var defaults = ConsumerOptions.defaults();
        // A DurableQueues policy counts REdeliveries; the engine counts attempts. Off by one on both
        // counts below: missed in the limit it is one whole extra delivery of every failing message,
        // missed in the backoff every wait is shifted one step along the policy's sequence.
        return new ConsumerOptions(operation.getParallelConsumers(),
                                   defaults.maxShards(),
                                   policy.maximumNumberOfRedeliveries + 1,
                                   // Delegated rather than mapped onto the engine's own formula, which
                                   // has one delay where a policy has an initial and a follow-up, and
                                   // cannot express a linear policy at all. Mapping it used to run every
                                   // retry from initialRedeliveryDelay and ignore followupRedeliveryDelay.
                                   attemptsSoFar -> policy.calculateNextRedeliveryDelay(Math.max(0, attemptsSoFar - 1)));
    }

    /**
     * Thrown to turn {@code markForRedeliveryIn} into the engine's {@link RedeliveryRequested} outcome: the message
     * is delivered again after the requested delay, and - as a manual redelivery in the classic
     * {@code DurableQueues} - the request is never a failure that could dead-letter it.
     */
    static final class ManualRedeliveryRequested extends RedeliveryRequested {
        ManualRedeliveryRequested(java.time.Duration requestedDelay) {
            super(requestedDelay, "The handler asked for redelivery in " + requestedDelay);
        }
    }
}
