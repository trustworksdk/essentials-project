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
import dk.trustworks.essentials.shared.concurrent.Locks;
import org.slf4j.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
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
     * Guards {@link #holder}. Held for a few reads and writes of it only.
     */
    private final Object        handOver      = new Object();
    /**
     * What the holder of {@link #lifecycleLock} is doing, and whether it owes a stop - one value every stop and start
     * decides the hand-over from ({@link Holder}), moved only along its diagram ({@link #moveHolder}).
     */
    private       Holder        holder        = Holder.NONE;
    /**
     * Set by {@link #retire()}, under {@link #lifecycleLock}, and by {@link #cancel()} before it takes that lock - hence
     * volatile; read by {@link #start()} under the lock. Once set, a start that has not read it yet does nothing.
     */
    private volatile boolean    retired;

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
     * Subscribes with the queue's {@code consume()} each time: every way this consumer stops ends its subscription with
     * {@link Subscription#unsubscribe()} ({@link #stopSubscription()}), so the queue holds no subscription of it while it
     * is stopped, and the restart registers under the same instance id rather than as a competing consumer.
     * <p>
     * A {@link #stop()} that gave up waiting for this start, still subscribing, has stopped the consumer without it. This
     * start then stops - unsubscribes - the subscription the queue returns, logging at WARN rather than throwing what that
     * throws, since the stop's caller has already returned, and returns with the consumer not started.
     * <p>
     * A start called meanwhile - {@link ShardOwnedDurableQueues#start()} restarts the same consumer objects - waits for
     * that earlier start to return - as long as the queue's {@code consume()} takes, then the stop of the subscription it
     * returned - the thread's interrupt included: once it has waited {@link #STOP_WAITS_FOR_START} it logs one WARN naming
     * the consumer and the queue, and goes on waiting ({@link #lockForStart}).
     * <p>
     * Does nothing once the consumer has been {@link #retire() retired} or {@link #cancel() cancelled}.
     */
    @Override
    public void start() {
        lockForStart();
        try {
            if (retired || subscription != null) {
                return;
            }
            moveHolder(Holder.STARTING);
            Subscription subscribed = null;
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
                    owed = holder == Holder.STOP_OWED;
                    // Moved in the same block that reads the owed stop, so no stop sees NONE in between: one that did would
                    // wait for the whole of the subscription's stop below.
                    moveHolder(owed && subscribed != null ? Holder.FINISHING_OWED_STOP : Holder.NONE);
                }
            }
            if (owed) {
                try {
                    stopForAStopThatDidNotWait(subscribed);
                } finally {
                    moveHolder(Holder.NONE);
                }
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
     * {@link #STOP_WAITS_FOR_START}, it logs one WARN saying what holds the lock, and goes on waiting. When the
     * {@link #holder} then reads {@link Holder#NONE} - a holder that has just taken the lock or is about to let go of it -
     * there is nothing to say yet, and it reads it again one {@code STOP_WAITS_FOR_START} later. A stop holding the lock is
     * bounded by the subscription's own stop, and so is an earlier start stopping the subscription a stop left it. An
     * earlier start still in the queue's {@code consume()} is not: that blocks as long as a statement does.
     */
    private void lockForStart() {
        lockWarningOnce("start");
    }

    /**
     * Take {@link #lifecycleLock} as {@link #lockForStart} does, for whatever {@code waitingTo} names in the WARN: "start",
     * or "stop" for {@link #stopIf} and {@link #retire()}, which wait for an earlier start as long as a start does.
     */
    private void lockWarningOnce(String waitingTo) {
        Locks.lockWarningOnce(lifecycleLock, STOP_WAITS_FOR_START, () -> {
            Holder heldBy;
            synchronized (handOver) {
                heldBy = holder;
            }
            if (heldBy == Holder.NONE) {
                // The holder has just taken the lock, or is just letting go of it - nothing to say yet: asked again one
                // STOP_WAITS_FOR_START later, as PostgresqlMessageQueue's holdLifecycle does.
                return false;
            }
            if (heldBy == Holder.STOPPING) {
                log.warn("Consumer '{}' has waited {} and is still waiting to {} on queue '{}': a stop of the consumer is "
                                 + "still stopping its subscription",
                         consumerName(), STOP_WAITS_FOR_START, waitingTo, queueName());
            } else if (heldBy == Holder.FINISHING_OWED_STOP) {
                log.warn("Consumer '{}' has waited {} and is still waiting to {} on queue '{}': an earlier start of the "
                                 + "consumer is stopping the subscription the queue's consume() returned it, for a stop that gave "
                                 + "up waiting for it",
                         consumerName(), STOP_WAITS_FOR_START, waitingTo, queueName());
            } else {
                // STARTING or STOP_OWED: an earlier start still in the queue's consume().
                log.warn("Consumer '{}' has waited {} and is still waiting to {} on queue '{}': an earlier start of the "
                                 + "consumer still holds its lifecycle, in the queue's consume(), registering the instance and "
                                 + "leasing units. Nothing here bounds the consume(): a statement that hangs ends at the "
                                 + "driver's socketTimeout, and without one never",
                         consumerName(), STOP_WAITS_FOR_START, waitingTo, queueName());
            }
            return true;
        });
    }

    /**
     * Stop the consumer if {@code condition} holds, read once this holds {@link #lifecycleLock} - the lock a
     * {@link #start()} holds throughout - and say whether it held. A start called meanwhile has therefore either returned
     * before the condition is read, or starts the consumer again once this has stopped it: whatever the condition reads
     * cannot be undone by a start that slips in between the read and the stop.
     * <p>
     * Waits for the lock as long as a start does, with the same one WARN past {@link #STOP_WAITS_FOR_START}
     * ({@link #lockForStart}), rather than giving up as {@link #stop()} does: a start still subscribing is a start this
     * must see finished before it can tell whether to stop it. What the subscription's stop throws escapes, as from
     * {@link #stop()}, with the consumer not started.
     *
     * @param condition read under the lock; the consumer is stopped only when it returns true
     * @return the condition's value
     */
    boolean stopIf(BooleanSupplier condition) {
        requireNonNull(condition, "No condition provided");
        lockWarningOnce("stop");
        try {
            if (!condition.getAsBoolean()) {
                return false;
            }
            stopSubscription();
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Stop the consumer, under {@link #lifecycleLock} as {@link #stopIf} does, and keep every later {@link #start()} from
     * starting it again. For a consumer {@link ShardOwnedDurableQueues} is dropping from its registry without anyone holding
     * it: a {@code ShardOwnedDurableQueues.start()} may have taken it from the registry before it was dropped, and would
     * otherwise start it, unregistered, after it had been dropped. What the subscription's stop throws escapes, as from
     * {@link #stop()}, with the consumer retired and not started.
     */
    void retire() {
        lockWarningOnce("stop");
        try {
            retired = true;
            stopSubscription();
        } finally {
            lifecycleLock.unlock();
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
     * handler. A stop called while another stop is stopping the subscription, or while the start is stopping it for a stop
     * that gave up waiting, returns at once past that wait, leaving the stopping to them.
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
                    moveHolder(Holder.STOP_OWED);
                }
            }
            if (heldBy == Holder.STARTING || heldBy == Holder.STOP_OWED) {
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
            if (heldBy == Holder.STOPPING || heldBy == Holder.FINISHING_OWED_STOP) {
                // Nothing left to do: this stop stops nothing without the lock, so a start finishing an owed stop, which ends
                // with the consumer not started, owes it nothing more (unlike PostgresqlMessageQueue's, see Holder).
                return;
            }
            // Holder.NONE: whoever holds the lock has just taken it or is just letting go of it. The consumer may still be
            // started (a start about to set its subscription, a stop about to stop it), so take the lock and look: returning
            // here, as on STOPPING, could leave it running.
            locked = tryLockAfterAHandOver();
        }
        try {
            stopSubscription();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * End the subscription, if there is one, holding {@link #lifecycleLock}: the part of {@link #stop()}, {@link #stopIf}
     * and {@link #retire()} that runs once the lock is theirs.
     * <p>
     * Unsubscribed ({@link Subscription#unsubscribe()}), not only stopped: the next {@link #start()} consumes from the
     * queue anew, and a subscription merely stopped stays registered with the queue. Each stop and start of this consumer,
     * and each cancel of one followed by a {@code consumeFromQueue} - a {@code DurableLocalCommandBus} restarting, an
     * {@code Inbox} handing its lock over - then left one more stopped subscription with the queue: the next
     * {@code consume()} logged a WARN calling this consumer a COMPETING consumer under a new instance id suffix, and a
     * {@code start()} of the queue after its {@code stop()} started every such subscription again, running the handler of
     * a consumer nobody held any more.
     */
    private void stopSubscription() {
        if (subscription == null) {
            return;
        }
        moveHolder(Holder.STOPPING);
        try {
            subscription.unsubscribe();
        } finally {
            // Dropped even when it throws: a subscription's unsubscribe throws only once both of its lanes have stopped (a
            // QueueObserver failing for a release), and one left here made every later start() return without consuming.
            subscription = null;
            moveHolder(Holder.NONE);
        }
        log.info("Consumer '{}' stopped on queue '{}'", consumerName(), queueName());
    }

    /**
     * How long a {@link #stop()} that read {@link Holder#NONE} waits for the lock before it reads {@link #holder} again.
     * Short, because the lock may meanwhile have passed to a start subscribing, which the stop must leave stopping the
     * subscription to rather than wait for; a lock let go of meanwhile is taken without waiting out the rest.
     */
    private static final long HAND_OVER_WAIT_MILLIS = 10;

    /**
     * Wait up to {@link #HAND_OVER_WAIT_MILLIS} for {@link #lifecycleLock}, parked rather than spinning. An interrupt
     * pending on entry is cleared for the wait, so it does not cut the wait short. An interrupt arriving during the wait
     * does: the timed {@code tryLock} throws, the wait ends without the lock ({@code false}) and the caller reads
     * {@link #holder} again. Either way the flag is set again afterwards, so the stop leaves it set as it found or got it.
     */
    private boolean tryLockAfterAHandOver() {
        var interrupted = Thread.interrupted();
        try {
            return lifecycleLock.tryLock(HAND_OVER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Move {@link #holder} to {@code target} if {@link Holder}'s diagram has an edge from where it stands to there
     * ({@link Holder#canMoveTo}), else leave it where it is. Under {@link #handOver}, which this takes; a caller that reads
     * the holder and moves it as one step holds it already. Every move of the holder goes through here, so the rule lives
     * once, as {@code ShardOwnedQueue}'s {@code Run.advanceTo} keeps it for a run's phase.
     *
     * @return the holder before this call, so the caller can tell whether it moved it and from where
     */
    Holder moveHolder(Holder target) {
        requireNonNull(target, "No target holder provided");
        synchronized (handOver) {
            var previous = holder;
            if (previous.canMoveTo(target)) {
                holder = target;
            }
            return previous;
        }
    }

    /**
     * What the holder of {@link #lifecycleLock} is doing now ({@link #holder}), read under {@link #handOver}.
     */
    Holder holder() {
        synchronized (handOver) {
            return holder;
        }
    }

    /**
     * What the holder of {@link #lifecycleLock} is doing, and whether it owes a stop - one value, read under
     * {@link #handOver} and moved only by {@link #moveHolder}, along the edges below, so a {@link #stop()} that did not get
     * the lock, the {@link #start()} holding it and a start waiting for it ({@link #lockForStart}) each decide from it.
     * <pre>
     *   NONE ----------------&gt; STARTING | STOPPING
     *   STARTING ------------&gt; STOP_OWED | NONE
     *   STOP_OWED -----------&gt; FINISHING_OWED_STOP | NONE   (NONE: the queue's consume() threw, no subscription to stop)
     *   FINISHING_OWED_STOP -&gt; NONE
     *   STOPPING ------------&gt; NONE
     * </pre>
     * Not forward-only, as {@code ShardOwnedQueue}'s {@code Run.Phase} is: this describes whoever holds the lock now, and
     * goes back to {@link #NONE} as each holder lets go, where a {@code Run.Phase} belongs to one run.
     * <p>
     * {@code PostgresqlMessageQueue}'s lifecycle holder has these five values, and a sixth, {@code UNDOING_A_START},
     * for a failed start of its own stopping the consumers it started, which this consumer leaves to the queue's
     * {@code consume()}. Nor are the edges the same, because its stop does more without the lock. A
     * {@code PostgresqlMessageQueue.stop()} that did not get the lock stops every consumer itself, counted in its
     * {@code stopsWithoutLock}, and the holder waits for it and then stops every consumer again; so a stop arriving
     * while the holder does that ({@code FINISHING_OWED_STOP}) is owed again ({@code STOP_OWED}), and the holder
     * repeats the stopping. A {@link #stop()} here that did not get the lock stops nothing, and leaves all of it to the
     * start: once that start is stopping the subscription ({@link #FINISHING_OWED_STOP}), after which the consumer is
     * not started, a stop arriving owes nothing further, and returns. Both read {@link #NONE} the same way: a start
     * waiting for the lock asks again one {@link #STOP_WAITS_FOR_START} later rather than guess what holds it, and a
     * stop waits for the lock a few milliseconds at a time, parked.
     */
    enum Holder {
        /**
         * A start or stop that found nothing to do, or one that has just taken the lock or is about to let go of it - all
         * brief. The stop waits for the lock a few milliseconds at a time, reading the holder again in between; a start
         * waiting for it says nothing yet, and reads it again one {@link #STOP_WAITS_FOR_START} later.
         */
        NONE,
        /**
         * A {@link #start()} subscribing, owing no stop: a stop that did not get the lock moves it to {@link #STOP_OWED}
         * and leaves stopping the subscription to it.
         */
        STARTING,
        /**
         * A {@link #start()} still subscribing, owing a stop that gave up waiting for it: the start stops the subscription
         * the queue's {@code consume()} returns ({@link #FINISHING_OWED_STOP}), or, when {@code consume()} threw, has none
         * to stop ({@link #NONE}). A further stop has nothing to add, and leaves it to the start too.
         */
        STOP_OWED,
        /**
         * A {@link #start()} stopping the subscription the queue's {@code consume()} returned it, for a stop that gave up
         * waiting for it - which lasts as long as that subscription's stop: each lane may drain for up to {@code shedGrace}
         * and then hand back its units. The consumer ends not started, so a stop arriving meanwhile has nothing left to do.
         */
        FINISHING_OWED_STOP,
        /** A {@link #stop()}, {@link #stopIf} or {@link #retire()} stopping the subscription: the stop has nothing left to do. */
        STOPPING;

        /**
         * Whether the diagram above has an edge from this holder to {@code target}: from {@link #NONE} to
         * {@link #STARTING} and {@link #STOPPING}; from {@code STARTING} to {@link #STOP_OWED} and {@code NONE}; from
         * {@code STOP_OWED} to {@link #FINISHING_OWED_STOP} and {@code NONE}; from {@code FINISHING_OWED_STOP} and
         * {@code STOPPING} to {@code NONE} alone. Staying where it is is not an edge.
         */
        boolean canMoveTo(Holder target) {
            return switch (this) {
                case NONE -> target == STARTING || target == STOPPING;
                case STARTING -> target == STOP_OWED || target == NONE;
                case STOP_OWED -> target == FINISHING_OWED_STOP || target == NONE;
                case FINISHING_OWED_STOP, STOPPING -> target == NONE;
            };
        }
    }

    /**
     * Unsubscribe the subscription the queue's {@code consume()} returned a start for a stop that gave up waiting for it:
     * unsubscribed, as {@link #stopSubscription()} does, or it stayed registered with the queue, stopped, and the next
     * start's {@code consume()} added another.
     */
    private void stopForAStopThatDidNotWait(Subscription subscribed) {
        try {
            subscribed.unsubscribe();
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

    /**
     * {@inheritDoc}
     * <p>
     * Stops the consumer as {@link #stop()} does - waiting for a start still subscribing up to
     * {@link #STOP_WAITS_FOR_START}, and no longer - and deregisters it from the {@link ShardOwnedDurableQueues}, even when
     * the stop throws. From the start of the cancel on, no {@link #start()} starts the consumer again: a
     * {@code ShardOwnedDurableQueues.start()} may have taken it from the registry before it was deregistered, and used to
     * start it afterwards - a consumer no {@code stop()} reaches and its caller has let go of. A start already subscribing
     * when the cancel begins is stopped as by {@link #stop()}: by the cancel, or by that start itself once the queue's
     * {@code consume()} returns. Unlike {@link #retire()}, which waits for such a start as long as it takes, this stays
     * bounded by {@code STOP_WAITS_FOR_START}.
     */
    @Override
    public void cancel() {
        // Set before the stop: a start that has not yet read it, under the lifecycle lock, then does not subscribe.
        retired = true;
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
