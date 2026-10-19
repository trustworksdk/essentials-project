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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.operations.*;
import dk.trustworks.essentials.shared.Exceptions;
import dk.trustworks.essentials.shared.concurrent.Locks;
import dk.trustworks.essentials.shared.interceptor.*;

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The shard-owned engine behind the {@link MessageQueue} contract.
 * <p>
 * Written to check that the contract is implementable rather than merely well-argued. Two things it
 * turned up that the interface alone did not show: enqueue has to split a mixed batch by lane before
 * it can route anything, and a single handler signature has to serve both lanes, which is why
 * {@link MessageHandler} takes a nullable key rather than the two separate shapes the engine uses
 * internally.
 *
 * <h2>One queue here, two engines underneath</h2>
 * {@link #consume} builds <b>two</b> {@link ShardOwnedQueue} instances, one per lane, because a
 * {@code ShardOwnedQueue} serves one lane by construction — the two lanes have separate owners,
 * separate leases and separate unit spaces. They share a {@code consumerInstanceId} so the pair
 * counts as one member of the cluster rather than two; registering them separately is what once made
 * a single process claim half the shards of each lane and strand the rest.
 * <p>
 * The lane is chosen by the <em>message</em>, never by the consumer: {@code Message.of} is unordered
 * and {@code Message.ordered} carries a key. So one {@code consume} covers both lanes, and a second
 * {@code consume} on the same queue is a genuinely separate competing consumer — not "the other
 * lane".
 * <p>
 * {@code Subscription.shardsHeld()} therefore sums the pair, with {@code unorderedShardsHeld()} and
 * {@code orderedUnitsHeld()} for the per-lane figures. Those are not interchangeable: anything about
 * {@code shardCount} — growth, the shrink refusal, a shard-scope session excluding consumers — is an
 * unordered-lane question, and the total buries it under a constant routing space.
 */
public final class PostgresqlMessageQueue implements MessageQueue {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlMessageQueue.class);

    private final    ShardOwnedStorage storage;
    private final    DataSource        dataSource;
    private final    short             queueId;
    /**
     * Refreshed from the registry, because enqueue routing has to learn about a grown shard count
     * too. Wiring only the consumer side would leave a producer routing keys by the old modulus
     * while its own consumers leased the new shards — which is the divergence the registry exists to
     * prevent, reintroduced by fixing half of it.
     */
    private volatile int               shardCount;
    /**
     * This queue's ordered routing space, from the registry. 0 until resolved; never changes after.
     */
    private volatile int               orderedUnits;
    private volatile long              shardCountCheckedAtNanos;
    private final    String            instanceId;

    private final ShardOwnerSettings settings;
    /**
     * The runtime this queue's consumers run on, or null to borrow the one shared per
     * {@code DataSource}. A caller that already owns a runtime must pass it: borrowing then stands up
     * a SECOND runtime next to it, with its own pumps and listener, each holding a connection for
     * good. The Spring starter did exactly that — its runtime bean held three connections and served
     * nothing, while the queues ran on a shared one holding three more.
     */
    private final ShardRuntime       runtime;

    /**
     * Added to and removed from only under {@link #lifecycleLock} - removed from by a {@link Subscription#unsubscribe()}
     * ({@link #deregister}), or by the holder of the lock it handed that to ({@link #letGoOfLifecycle}) - and copy-on-write
     * so a {@link #stop()} that did not get the lock, and {@link #statistics()}, can read it while a {@code consume()} adds
     * to it.
     */
    private final List<ShardOwnedQueue>         consumers          = new CopyOnWriteArrayList<>();
    /**
     * Whether the queue is started: by {@link #start()}, or by a {@link #consume} made while no subscription is
     * registered ({@link #consumers} empty - so again once every subscription has been unsubscribed). False until then
     * - it used to be seeded {@code true}, so a queue that had never consumed reported itself started and the first
     * {@link #start()} was a no-op - and again after a {@link #stop()}, until the next {@code start()}. A
     * {@link Subscription#start()}, or a
     * {@code consume()} after a {@code stop()} left other subscriptions stopped, starts its own lanes and leaves it as it
     * is, so a later {@code start()} still starts those others. It decides what {@code start()} does and what
     * {@link #isStarted()} reads, never what a {@code stop()} stops: that is every registered consumer.
     */
    private final AtomicBoolean                 started            = new AtomicBoolean();
    /**
     * Held across the whole of {@link #consume}'s registration and starts, {@link #start()}, a {@link Subscription#start()}
     * and {@link #stop()}, so they run one at a time. A lock rather than {@code consumers}' monitor, which it used to be,
     * because a stop must be able to stop waiting for it: a {@code consume()} or a start holds it across its consumers'
     * starts, which register the instance and lease units on the database, and nothing in this queue bounds those - a
     * statement caught by a network partition blocks until the driver's {@code socketTimeout}, and without one
     * indefinitely. A {@code stop()} waiting on the monitor never reached the 2 s budget each
     * {@link ShardOwnedQueue#stop()} keeps to. It now waits up to {@link ShardOwnedQueue#SHUTDOWN_BUDGET} for this lock,
     * on an interrupted thread not at all, and then stops without it ({@link #stopWhileStarting}); what the holder still
     * owes the stop, it does itself before it lets go ({@link #letGoOfLifecycle}). A stop on the holder's own thread
     * never takes it again ({@link #stopFromInsideTheHolder}). Who holds it and whether that holder owes a stop is one
     * value, {@link #holder}.
     */
    private final ReentrantLock                 lifecycleLock      = new ReentrantLock();
    /**
     * Guards {@link #holder}, {@link #beingStarted}, {@link #stopsWithoutLock}, {@link #toDeregister},
     * {@link #subscriptionIndexes} and {@link #heldIndexes}. Held for a few reads and writes of them only, never across a start or a stop.
     */
    private final Object                        handOver           = new Object();
    /**
     * What the holder of {@link #lifecycleLock} is doing, and whether it owes a stop: {@link Holder#NONE} while nobody
     * holds it, and just after a holder took it or just before it lets go. The one value every stop, start and the holder
     * itself decides the hand-over from ({@link Holder}), moved only along its diagram ({@link #moveHolder}).
     */
    private       Holder                        holder             = Holder.NONE;
    /**
     * The lane consumers of every {@code consume()} holding {@link #lifecycleLock} that has not yet registered its pair
     * in {@link #consumers} - it does so once its starts return: a stop that did not get the lock stops them too, so the
     * one still leasing hands the stop's work to its start, which then adopts nothing it leases. More than one pair when a
     * {@link QueueObserver} told of an acquisition calls {@code consume()} on the thread of a {@code consume()} still
     * starting, holding the lock reentrantly: each {@code consume()} adds its own pair and takes out only that pair once
     * it has registered it, or failed, so the outer pair stays here until the outer {@code consume()} registers it. It
     * used to be one field the nested {@code consume()} overwrote with its own pair and never set back, so a stop that
     * gave up waiting from then on did not stop the outer pair, and its unordered lane leased and dispatched after that
     * stop had returned, until the outer {@code consume()} stopped every consumer before it let go.
     */
    private final List<ShardOwnedQueue>         beingStarted       = new ArrayList<>();
    /**
     * Stops that did not get {@link #lifecycleLock} and are still stopping consumers. The holder waits for them before it
     * lets go, so none of them stops a consumer a later {@code start()} or {@code consume()} has just started.
     */
    private       int                           stopsWithoutLock;
    /**
     * Whether the start running {@link #startEachOrUndo} on the thread holding {@link #lifecycleLock} is the queue's
     * {@link #start()}, rather than a {@code consume()} or a {@link Subscription#start()}: each of the three sets it around
     * its own {@code startEachOrUndo} - {@code true} for the queue's {@code start()}, {@code false} for the other two - and
     * restores the value it found once that returns, so with starts nested on that thread (a {@link QueueObserver} told of
     * an acquisition calling one) it names the innermost. That is the start whose failure moved the holder to
     * {@link Holder#UNDOING_A_START}: a start called inside that undo starts nothing, so none can fail deeper. Written and read only on the
     * thread holding the lock - by a {@code start()} an observer told of a release makes there, which tells the undo of a
     * failed queue {@code start()}, marked started until it fails, from that of a {@code consume()} or
     * {@link Subscription#start()}, which leaves a started queue started. It was set once, by a queue {@code start()}
     * taking the lock first, so a queue {@code start()} failing inside a {@code consume()} or {@code Subscription.start()}
     * was taken for theirs, and a {@code consume()} failing inside a queue {@code start()} for the queue's.
     */
    private       boolean                       queueStartRunsTheStarts;
    /**
     * Subscriptions whose {@link Subscription#unsubscribe()} found {@link #lifecycleLock} held, past
     * {@link ShardOwnedQueue#SHUTDOWN_BUDGET} or on the holder's own thread: the holder - a {@link #start()} going over
     * {@link #consumers} - may still reach their lanes, which it starts no more from the moment that unsubscribe retired
     * them ({@link #startEachOrUndo}), so it removes them from {@code consumers}, stops them again, and gives their
     * subscription index back before it lets go ({@link #letGoOfLifecycle}). Whatever the holder started before the
     * retirement is stopped by whichever of the two stops, the unsubscribe's or the holder's, gets there first; the other
     * does nothing.
     */
    private final List<Deregistration>          toDeregister       = new ArrayList<>();
    /**
     * The subscription index of every subscription this queue has handed out and not yet ended: reserved by
     * {@link #consume}, given back when it fails without registering anything or when its subscription has been
     * unsubscribed and both its lanes stopped - unless a lane kept an ordered unit, or its hand-back to the database is
     * still running, see {@link #heldIndexes}. A {@code consume()} takes the lowest index free, so the instance id a
     * subscription registers under ({@link #consume}) is the caller's verbatim again once every earlier subscription has
     * ended, rather than one more suffix per subscription the process ever made.
     */
    private final BitSet                        subscriptionIndexes = new BitSet();
    /**
     * Subscription indexes still set in {@link #subscriptionIndexes} after their subscription ended - unsubscribed, or a
     * {@code consume()} that failed - because their instance id is still in use on the database
     * ({@link #releaseSubscriptionIndex}). Two reasons, either enough:
     * <ul>
     *     <li>A lane kept an ordered unit on stop ({@link ShardOwnedQueue#keptOrderedUnitsOnLastStop()}): the unit's lease
     *     row names the id until the instance's liveness lapses, a lease TTL after the lanes stopped, and a new pair
     *     registering under it would take the unit at once and could start a key beside the handler still running it.</li>
     *     <li>A lane's hand-back to the database is still running on a thread its stop gave up waiting for
     *     ({@link ShardOwnedQueue#handBackStillRunning()}): it may still delete the id's membership row, and a new pair
     *     registering under the id meanwhile could lose its row to it, its units then read as ownerless by every other
     *     instance until its next heartbeat.</li>
     * </ul>
     * The next {@code consume()} registers under another index meanwhile. {@link #reserveSubscriptionIndex} frees the
     * ones that are due.
     */
    private final Map<Integer, HeldIndex>       heldIndexes         = new HashMap<>();
    /**
     * What a test does once a {@code consume()}, a {@link #start()} or a {@link Subscription#start()} has taken
     * {@link #lifecycleLock}; {@link LifecycleHook#NONE} unless a test passed one to the builder
     * ({@link PostgresqlMessageQueueBuilder#setLifecycleHook}). Fixed at construction, so nothing can swap it while the
     * queue runs.
     */
    private final LifecycleHook                 lifecycleHook;
    private final List<QueueObserver>           observers          = new CopyOnWriteArrayList<>();
    /**
     * Sorted by {@link InterceptorOrder} on registration rather than on every call: the sort is
     * reflection over annotations, and doing it per delivered message would put it on the hot path.
     */
    private final List<MessageQueueInterceptor> interceptors       = new CopyOnWriteArrayList<>();
    /**
     * Round-robin across shards, one step per message.
     * <p>
     * Per message, not per call. Picking one shard for a whole batch — which is what a hash of the
     * instance id and {@code nanoTime} amounts to — puts every message of a five-thousand-row enqueue
     * on a single shard, so the other shards' owners sit idle and the batch is serialised behind one
     * of them. Shards are the unit of parallelism; the enqueue has to actually use them.
     */
    private final AtomicInteger                 enqueueShardCursor = new AtomicInteger();

    public static PostgresqlMessageQueueBuilder builder() {
        return new PostgresqlMessageQueueBuilder();
    }

    public PostgresqlMessageQueue(DataSource dataSource, short queueId, int shardCount, String instanceId) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.queueId = queueId;
        this.shardCount = shardCount;
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.settings = ShardOwnerSettings.defaults();
        this.runtime = null;
        this.lifecycleHook = LifecycleHook.NONE;
        this.storage = new ShardOwnedStorage(dataSource, queueId);
    }

    /**
     * @param settings engine tuning for this queue's consumers. The no-settings constructor uses
     *                 {@link ShardOwnerSettings#defaults()}; {@link #consume} used to hardcode that
     *                 call, which left the contract with no way to configure the engine at all.
     */
    PostgresqlMessageQueue(DataSource dataSource, short queueId, int shardCount, String instanceId,
                           ShardOwnerSettings settings) {
        this(dataSource, queueId, shardCount, instanceId, settings, null);
    }

    /**
     * @param runtime the runtime this queue's consumers run on, or null to borrow the one shared per
     *                {@code DataSource}
     */
    PostgresqlMessageQueue(DataSource dataSource, short queueId, int shardCount, String instanceId,
                           ShardOwnerSettings settings, ShardRuntime runtime) {
        this(dataSource, queueId, shardCount, instanceId, settings, runtime, LifecycleHook.NONE);
    }

    /**
     * @param runtime       the runtime this queue's consumers run on, or null to borrow the one shared per
     *                      {@code DataSource}
     * @param lifecycleHook what a test does once a start has taken the lifecycle lock ({@link LifecycleHook});
     *                      {@link LifecycleHook#NONE} outside tests
     */
    PostgresqlMessageQueue(DataSource dataSource, short queueId, int shardCount, String instanceId,
                           ShardOwnerSettings settings, ShardRuntime runtime, LifecycleHook lifecycleHook) {
        this.lifecycleHook = requireNonNull(lifecycleHook, "No lifecycleHook provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.queueId = queueId;
        this.shardCount = shardCount;
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.runtime = runtime;
        this.storage = new ShardOwnedStorage(dataSource, queueId);
    }

    /**
     * The interned id this queue addresses.
     */
    public short queueId() {
        return queueId;
    }

    /**
     * Fixed for the life of the queue; taken from the registry when built from a name.
     */
    public int shardCount() {
        return shardCount;
    }

    @Override
    public MessageQueue addInterceptor(MessageQueueInterceptor interceptor) {
        requireNonNull(interceptor, "No interceptor provided");
        var sorted = new ArrayList<>(interceptors);
        sorted.add(interceptor);
        DefaultInterceptorChain.sortInterceptorsByOrder(sorted);
        interceptors.clear();
        interceptors.addAll(sorted);
        return this;
    }

    @Override
    public MessageQueue addObserver(QueueObserver observer) {
        observers.add(requireNonNull(observer, "No observer provided"));
        return this;
    }

    /**
     * Observers are invoked without catching what they throw. An observer that fails is a bug in the
     * observer, and swallowing it would hide that bug while corrupting the measurement it was
     * installed to produce.
     */
    private void notifyObservers(Consumer<QueueObserver> notification) {
        observers.forEach(notification);
    }

    /**
     * The registered observers as a single one, reading the list live so an observer registered after
     * {@link #consume} still sees events. Handed to the engine, which has no notion of a list. Package-private so
     * {@code TakeoverStalledFanOutTest} can drive it without a database.
     */
    final QueueObserver fanOut = new QueueObserver() {
        @Override
        public void deliveryFailed(String key, int attempt, Throwable cause) {
            notifyObservers(observer -> observer.deliveryFailed(key, attempt, cause));
        }

        @Override
        public void retryScheduled(String key, int attempt, long delayMillis) {
            notifyObservers(observer -> observer.retryScheduled(key, attempt, delayMillis));
        }

        @Override
        public void deadLettered(MessageId id, int attempts, Throwable cause) {
            notifyObservers(observer -> observer.deadLettered(id, attempts, cause));
        }

        @Override
        public void shardOwnershipChanged(int shard, boolean acquired) {
            notifyObservers(observer -> observer.shardOwnershipChanged(shard, acquired));
        }

        /**
         * Every observer is told even when an earlier one throws: the engine catches this callback (see
         * {@link QueueObserver#takeoverStalled}), so stopping at the first throw would leave the observers after it
         * with a stall they never hear about, or one that never ends. The first exception is rethrown as it was thrown
         * once all have been told, for the engine to log, the later ones added to it as suppressed
         * ({@link Exceptions#forEachThenThrowFirst}). A non-critical {@code Error} (an {@code AssertionError}, say) is
         * treated the same way; a critical one (VirtualMachineError, LinkageError) propagates at once.
         */
        @Override
        public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
            Exceptions.forEachThenThrowFirst(observers, observer -> observer.takeoverStalled(lane, shard, stalled));
        }
    };

    @Override
    public List<MessageId> enqueue(List<Message> messages) throws SQLException {
        requireNonNull(messages, "No messages provided");
        if (messages.isEmpty()) {
            return List.of();
        }
        if (!interceptors.isEmpty()) {
            return intercepted(messages, null, this::enqueueDirect);
        }
        return enqueueDirect(messages, null);
    }

    /**
     * Run the enqueue through the interceptor chain.
     * <p>
     * Separate from the direct path, and reached only when an interceptor exists, so that the common
     * case allocates no operation object, no chain and no lambda. The engine's whole argument is
     * about what a message costs; adding an unconditional chain to enqueue would be the kind of
     * obligation the cost decomposition was written to expose.
     */
    private List<MessageId> intercepted(List<Message> messages,
                                        Connection connection,
                                        SqlBiFunction<List<Message>, Connection, List<MessageId>> enqueue) throws SQLException {
        var operation = new EnqueueMessages(messages, connection);
        try {
            return InterceptorChain.<EnqueueMessages, List<MessageId>, MessageQueueInterceptor>newInterceptorChainForOperation(
                    operation,
                    interceptors,
                    (interceptor, chain) -> interceptor.intercept(operation, chain),
                    () -> {
                        try {
                            // The interceptors may have replaced the batch entirely, so the write
                            // uses what the chain ended up with rather than what the caller passed.
                            return enqueue.apply(operation.getMessages(), connection);
                        } catch (SQLException e) {
                            throw new UncheckedSqlException(e);
                        }
                    }).proceed();
        } catch (UncheckedSqlException e) {
            throw e.getCause();
        }
    }

    /**
     * Carries a {@link SQLException} across the chain's unchecked functional boundary.
     */
    private static final class UncheckedSqlException extends RuntimeException {
        UncheckedSqlException(SQLException cause) {
            super(cause);
        }

        @Override
        public synchronized SQLException getCause() {
            return (SQLException) super.getCause();
        }
    }

    @FunctionalInterface
    private interface SqlBiFunction<A, B, R> {
        R apply(A a, B b) throws SQLException;
    }

    private List<MessageId> enqueueDirect(List<Message> messages, Connection ignored) throws SQLException {
        // A batch may mix lanes, and the lanes are different tables with different keys, so the
        // split has to happen before anything can be routed. Positions are carried through it so the
        // returned ids line up with the input however the batch ends up being divided.
        var routed           = route(messages);
        var unorderedByShard = routed.unordered();
        var orderedByShard   = routed.ordered();

        var ids = new MessageId[messages.size()];
        try (var connection = dataSource.getConnection()) {
            // One connection and one transaction for the whole call, both lanes included. Two
            // connections meant a mixed batch could commit its unordered half and fail its ordered
            // half, which is exactly the partial enqueue the storage layer's own transaction
            // handling exists to prevent — undone one level up.
            var autoCommit = connection.getAutoCommit();
            if (autoCommit) {
                connection.setAutoCommit(false);
            }
            try {
                writeBatch(connection, messages, unorderedByShard, orderedByShard, ids);
                if (autoCommit) {
                    connection.commit();
                }
            } catch (SQLException e) {
                if (autoCommit) {
                    connection.rollback();
                }
                throw e;
            } finally {
                if (autoCommit) {
                    connection.setAutoCommit(true);
                }
            }
        }
        notifyEnqueued(unorderedByShard, orderedByShard);
        return List.of(ids);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Nothing is committed here. The rows and the wake-up notification are written on the caller's
     * connection, inside the caller's transaction, so their commit is what publishes both.
     */
    @Override
    public List<MessageId> enqueue(Connection connection, List<Message> messages) throws SQLException {
        requireNonNull(connection, "No connection provided");
        requireNonNull(messages, "No messages provided");
        if (messages.isEmpty()) {
            return List.of();
        }
        requireTrue(!connection.getAutoCommit(),
                    "The connection is in autocommit mode, so it cannot carry a transaction for the "
                            + "enqueue to join. Begin a transaction first, or use enqueue(List) instead");
        if (!interceptors.isEmpty()) {
            return intercepted(messages, connection, this::enqueueOnConnection);
        }
        return enqueueOnConnection(messages, connection);
    }

    private List<MessageId> enqueueOnConnection(List<Message> messages, Connection connection) throws SQLException {
        var routed = route(messages);
        var ids    = new MessageId[messages.size()];
        writeBatch(connection, messages, routed.unordered(), routed.ordered(), ids);
        // Observers are told now rather than on commit: this method does not know when, or whether,
        // the caller commits. An observer counting enqueues on a rolled-back transaction is a
        // documented inaccuracy of the transactional path, not a defect in the engine.
        notifyEnqueued(routed.unordered(), routed.ordered());
        return List.of(ids);
    }

    private record Routed(Map<Integer, List<Integer>> unordered, Map<Integer, List<Integer>> ordered) {
    }

    /**
     * How often a producer re-reads the shard count. One query at most this often per producing
     * process — the same order as a heartbeat, and the only way a process that never consumes can
     * learn that the queue grew.
     */
    private static final long SHARD_COUNT_REFRESH_NANOS = Duration.ofSeconds(10).toNanos();

    /**
     * Pick up a grown shard count on the producing side.
     * <p>
     * Time-guarded rather than per-enqueue: a query on every enqueue would be a per-message cost, and
     * this engine's whole argument is about what a message costs. Growth is picked up within the
     * refresh interval, which is the same window the consumer side takes.
     */
    /**
     * The routing space this queue's ordered keys live in — the registry's, not the constant's.
     */
    private int orderedUnits() throws SQLException {
        var units = orderedUnits;
        if (units == 0) {
            units = storage.orderedUnits();
            orderedUnits = units;
        }
        return units;
    }

    private void refreshShardCountIfDue() {
        var now = System.nanoTime();
        if (now - shardCountCheckedAtNanos < SHARD_COUNT_REFRESH_NANOS && shardCountCheckedAtNanos != 0L) {
            return;
        }
        shardCountCheckedAtNanos = now;
        try {
            var registered = storage.currentShardCount();
            // Growth only. A smaller count would strand whatever is already in the shards this
            // producer stopped addressing.
            if (registered.isPresent() && registered.getAsInt() > shardCount) {
                log.info("Queue {} grew from {} to {} shards; enqueue routing follows",
                         queueId, shardCount, registered.getAsInt());
                shardCount = registered.getAsInt();
            }
        } catch (SQLException e) {
            log.warn("Could not re-read the shard count for queue {}; continuing with {}",
                     queueId, shardCount, e);
        }
    }

    /**
     * Decide the lane and shard of every message, keeping each one's position in the input.
     */
    private Routed route(List<Message> messages) throws SQLException {
        refreshShardCountIfDue();
        // Resolved once per queue, outside the loop: it is a property of the queue's stored data, not
        // of the batch, and it never changes while the queue exists.
        var units            = orderedUnits();
        var unorderedByShard = new LinkedHashMap<Integer, List<Integer>>();
        var orderedByShard   = new LinkedHashMap<Integer, List<Integer>>();
        for (var position = 0; position < messages.size(); position++) {
            var message = messages.get(position);
            var shard = message.isOrdered()
                        ? ShardOwnedSchema.unitForKey(message.key(), units)
                        : Math.floorMod(enqueueShardCursor.getAndIncrement(), shardCount);
            (message.isOrdered() ? orderedByShard : unorderedByShard)
                    .computeIfAbsent(shard, ignored -> new ArrayList<>())
                    .add(position);
        }
        return new Routed(unorderedByShard, orderedByShard);
    }

    private void notifyEnqueued(Map<Integer, List<Integer>> unorderedByShard,
                                Map<Integer, List<Integer>> orderedByShard) {
        var unorderedCount = unorderedByShard.values().stream().mapToInt(List::size).sum();
        var orderedCount   = orderedByShard.values().stream().mapToInt(List::size).sum();
        if (unorderedCount > 0) {
            notifyObservers(observer -> observer.enqueued(unorderedCount, false));
        }
        if (orderedCount > 0) {
            notifyObservers(observer -> observer.enqueued(orderedCount, true));
        }
    }

    private void writeBatch(Connection connection,
                            List<Message> messages,
                            Map<Integer, List<Integer>> unorderedByShard,
                            Map<Integer, List<Integer>> orderedByShard,
                            MessageId[] ids) throws SQLException {
        for (var entry : unorderedByShard.entrySet()) {
            var positions = entry.getValue();
            var rows = positions.stream()
                                .map(messages::get)
                                .map(message -> new ShardOwnedStorage.PayloadRow(message.payload(),
                                                                                 message.payloadType(),
                                                                                 message.delay()))
                                .toList();
            var seqs = storage.enqueueRows(connection, entry.getKey(), rows);
            assign(ids, positions, seqs, MessageId.Lane.UNORDERED, entry.getKey());
        }
        for (var entry : orderedByShard.entrySet()) {
            var positions = entry.getValue();
            var rows = positions.stream()
                                .map(messages::get)
                                .map(message -> new ShardOwnedStorage.OrderedPayload(message.key(),
                                                                                     message.keyOrder(),
                                                                                     message.payload(),
                                                                                     message.payloadType(),
                                                                                     message.delay()))
                                .toList();
            var seqs = storage.enqueueOrderedBatch(connection, entry.getKey(), rows);
            assign(ids, positions, seqs, MessageId.Lane.ORDERED, entry.getKey());
        }
    }

    /**
     * Scatter one shard's allocated sequence values back to the positions they came from.
     * <p>
     * Both lanes address a message by {@code seq}. An ordered id used to be built from
     * {@code key_order} instead, which is the producer's ordering hint rather than an identity — so
     * every by-id operation, {@code resurrect} included, was handed an address that resolved to
     * nothing or to the wrong row.
     */
    private static void assign(MessageId[] ids, List<Integer> positions, List<Long> seqs,
                               MessageId.Lane lane, int shard) {
        for (var index = 0; index < positions.size(); index++) {
            ids[positions.get(index)] = new MessageId(lane, shard, seqs.get(index));
        }
    }

    @Override
    public Subscription consume(MessageHandler handler, ConsumerOptions options) throws SQLException {
        requireNonNull(handler, "No handler provided");
        requireNonNull(options, "No options provided");

        var policy = RedeliveryPolicy.from(options);

        // ONE instance identity for both lanes of this subscription.
        //
        // The lanes used to register as "<id>-u" and "<id>-o". The membership table is not keyed by
        // lane, so a single process consuming through this contract counted as TWO instances, every
        // rebalance computed a fair share of ceil(shards / 2), and both lane consumers shed half
        // their shards to an instance that does not exist. Roughly twenty seconds after start, half
        // of both lanes was permanently unowned and the messages routed there were never delivered
        // — invisible to every test that finished before the first rebalance.
        //
        // A second consume() on the same queue is a genuinely separate competing consumer, so that
        // one does get its own identity. The index is the lowest no live subscription holds: one
        // given back by a failed consume() or an unsubscribed subscription is taken again, so a caller
        // that unsubscribes and consumes anew keeps its instance id instead of a new suffix each time -
        // unless the old pair kept an ordered unit on stop, whose lease names that id for a lease TTL,
        // or its stop gave up waiting for its hand-back to the database, which may still deregister
        // the id (heldIndexes): taking the id then would start the key beside its running handler, or
        // lose the new pair's membership row to that late deregistration.
        var subscriptionIndex = reserveSubscriptionIndex();
        var attempt           = new SubscribeAttempt();
        try {
            return subscribe(handler, options, policy, subscriptionIndex, attempt);
        } finally {
            if (!attempt.registered) {
                // Nothing registered and nothing running: a failed start has stopped both lanes. A stop like an
                // unsubscribe's, with what it may leave behind under the id: the ordered lane's stop keeps a unit whose key
                // is still in its handler - a QueueObserver throwing for that lane's acquisition escapes its start once it
                // has dispatched - and either lane's hand-back may outlast its stop's budget.
                releaseSubscriptionIndex(subscriptionIndex, attempt.lanes,
                                         attempt.lanes.stream().anyMatch(ShardOwnedQueue::keptOrderedUnitsOnLastStop),
                                         "a failed consume()");
            }
        }
    }

    /**
     * What {@link #subscribe} tells {@link #consume} about its attempt, on the consuming thread alone: the pair once
     * built, and whether it is registered - also by a critical error escaping its starts - after which the subscription
     * index is the pair's until it is unsubscribed. {@code consume()} gives the index back only when it is not, and
     * holds it ({@link #releaseSubscriptionIndex}) on what the pair's stops left behind.
     */
    private static final class SubscribeAttempt {
        private List<ShardOwnedQueue> lanes = List.of();
        private boolean               registered;
    }

    /**
     * The whole of {@link #consume} once the subscription index is reserved: build the pair, start it and register it.
     * Sets {@code attempt}'s lanes once the pair is built and its {@code registered} once the pair is in
     * {@link #consumers}, so {@code consume()} gives the index back only when it is not.
     */
    private Subscription subscribe(MessageHandler handler, ConsumerOptions options, RedeliveryPolicy policy, int subscriptionIndex,
                                   SubscribeAttempt attempt) {
        var consumerInstanceId = consumerInstanceId(subscriptionIndex);

        // The engine reports retries, dead letters and shard ownership from the places that know the
        // attempt count and the fence; handing it the observer fan-out is what makes those reachable.
        var engineMetrics = new ShardOwnerMetrics(fanOut);
        var unorderedConsumer = ShardOwnedQueue.builder()
                                               .setDataSource(dataSource)
                                               .setQueueId(queueId)
                                               .setShardCount(shardCount)
                                               .setInstanceId(consumerInstanceId)
                                               .setMetrics(engineMetrics)
                                               .setParallelConsumers(options.parallelConsumers())
                                               .setRuntime(runtime)
                                               .build();
        unorderedConsumer.configureUnordered((messageId, payload, payloadType) -> invoke(handler, messageId, null, 0L, payload, payloadType),
                                             settings, options.maxShards(), policy);
        // setShardCount is INERT on this consumer and is passed only because the builder requires a
        // positive value — it cannot know the lane, since configureOrdered comes after build(). Every
        // ordered path resolves its unit count from the registry instead: startOrdered pins
        // maxShardsHeld to orderedUnits(), the acquire loop and the shed iterate it, and rebalance
        // computes the lane's fair share from it. Passing the queue's real unordered count keeps the
        // value truthful rather than arbitrary, which matters for the two methods on ShardOwnedQueue
        // that still read the field directly — enqueue's round-robin and remaining() — neither of
        // which is called on this instance.
        var orderedConsumer = ShardOwnedQueue.builder()
                                             .setDataSource(dataSource)
                                             .setQueueId(queueId)
                                             .setShardCount(shardCount)
                                             .setInstanceId(consumerInstanceId)
                                             .setMetrics(engineMetrics)
                                             .setParallelConsumers(options.parallelConsumers())
                                             .setRuntime(runtime)
                                             .build();
        orderedConsumer.configureOrdered(new OrderedPayloadHandler() {
                                            @Override
                                            public void handle(MessageId messageId, String key, byte[] payload, int payloadType) {
                                                throw new IllegalStateException("The ordered owner delivers with the key order");
                                            }

                                            @Override
                                            public void handle(MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
                                                invoke(handler, messageId, key, keyOrder, payload, payloadType);
                                            }
                                        },
                                         settings, options.maxShards(), policy);
        // The pair shares one membership row (one instance id), and its lifetime is the pair's: each lane's stop deletes it
        // only when it kept no ordered unit itself and the other lane no longer needs it - neither running, nor draining,
        // nor keeping an ordered unit whose key is still in a handler. So the lane stopping last deletes it when neither
        // kept a unit; a unit kept by either leaves it to age out a lease TTL later (ShardOwnedQueue.shareMembershipWith says why the lanes decide it and not this queue). The unordered lane, stopped
        // first, used to delete it while the ordered lane still served or went on to keep units, which any other instance
        // could then take at once.
        unorderedConsumer.shareMembershipWith(orderedConsumer);
        orderedConsumer.shareMembershipWith(unorderedConsumer);
        // Registration, the started flag and the two start() calls are ONE critical section, and the
        // same lock start() and stop() take. Guarding only the list left two holes.
        //
        // A stop() interleaving after the add and before the starts stopped the new consumers while
        // they were still unstarted — a no-op — and stopped every PREVIOUSLY registered one for real,
        // after which this method set started back to true and started only its own pair. The queue
        // then reported started with an earlier subscription silently dead, and start() could not
        // recover it: its compareAndSet sees true and returns.
        //
        // And a start() that throws must leave nothing registered. That is not hypothetical on the
        // ordered lane — startOrdered begins with verifyWatermarkPrerequisites, so a database that
        // hides backend_xid fails here by design. Registering first meant the failure left a RUNNING
        // unordered consumer nobody had a handle to, and a caller retrying consume() took the next
        // subscription index, so the process registered as two instances and fairShare stranded half
        // of both lanes — the exact failure the per-subscription identity below exists to avoid.
        // A critical error is the exception: it stops nothing, so whatever it left running is
        // registered, for a stop() to reach (below) - a stop() stops every registered consumer.
        //
        // A consume() made while no subscription is registered starts the queue, which is what every
        // caller of this contract expects and what the tests rely on. Any other starts its own pair only: it leaves a started queue
        // started, and a queue a stop() left with other subscriptions stopped NOT started, so a later
        // start() still starts those. Marking it started there made that start() return at once with
        // the other subscriptions consuming nothing while isStarted() read true.
        //
        // The one exception to the critical section is a stop() that waited its budget for it, or
        // was interrupted: it stops the queue without the lock (stopWhileStarting), this pair
        // included. The starts below then stop at the first one not yet begun, the pair is still
        // registered, and letGoOfLifecycle stops every consumer again and leaves the queue not
        // started - as if the stop had come right after this consume(). Called on the thread of a
        // stop, or of a holder owing a stop, doing the stop it owes or stopping what its failed start
        // started (an observer told of a release, or of an acquisition after it stopped the queue),
        // this starts neither lane: it registers the pair, leaves the queue not started, for a later
        // start(), and says so at WARN - the Subscription it returns reads isStarted() false and
        // nothing else would tell the caller.
        var pair = List.of(unorderedConsumer, orderedConsumer);
        attempt.lanes = pair;
        holdLifecycle(Holder.STARTING, "consume");
        try {
            lifecycleHook.afterLockTaken("consume");
            var startsNothingInside = outerHolderThatLetsNothingStart();
            synchronized (handOver) {
                beingStarted.addAll(pair);
            }
            // Read before the starts: whether no other subscription is registered, which alone marks the queue started
            // (above). The competing-consumer WARN below asks what is registered once the starts have run instead: a
            // QueueObserver told of this pair's first acquisition can call consume() on this thread, and that pair is
            // registered by the time this one is.
            var firstSubscription = consumers.isEmpty();
            var queueStartRanTheStarts = queueStartRunsTheStarts;
            queueStartRunsTheStarts = false;
            try {
                startEachOrUndo(pair);
            } catch (RuntimeException | Error e) {
                // A non-critical failure has stopped both lanes, and nothing is registered (above). A critical error may
                // have left one running - a QueueObserver throwing it for the unordered lane's acquisition escapes that
                // lane's start with the lane running - so the pair is registered, and a stop() or close() still reaches
                // it. Unregistered, nothing did. The queue is marked as a consume() that succeeds marks it (above).
                if (Exceptions.isCriticalError(e)) {
                    consumers.add(unorderedConsumer);
                    consumers.add(orderedConsumer);
                    attempt.registered = true;
                    if (firstSubscription) {
                        markStartedUnlessHolderLetsNothingStart();
                    }
                }
                throw e;
            } finally {
                // Back to the outer start's, when an observer made this consume() inside one (queueStartRunsTheStarts).
                queueStartRunsTheStarts = queueStartRanTheStarts;
            }
            // A competing consumer is legal and occasionally wanted, but the common reason for a
            // second consume() is the belief that one call serves the ordered lane and another the
            // unordered one. It does not — every consume() serves both, and the lane reaches the
            // handler as the nullable ordering key. Both symptoms of that mistake are silent: this
            // handler receives the messages the caller meant for the other one, and the process
            // counts as two instances everywhere ownership is reported, so each of them owns half
            // of what a single subscription would. This is the only place that can tell.
            //
            // Reported on what is actually registered rather than on the subscription index: a
            // subscription unsubscribed leaves nothing registered, and warning about a competing
            // consumer that does not exist sends the reader after the wrong failure. A stopped
            // subscription still counts - the queue's start() starts it again. Read here, after the
            // starts, not from firstSubscription: a consume() an observer made while this pair was
            // starting found nothing registered and stayed quiet, so the two competing subscriptions
            // are warned about only from here.
            if (!consumers.isEmpty()) {
                log.warn("Queue {} already has {} consumer(s) registered in this process; instance '{}' is being added as "
                                 + "another COMPETING consumer, so each of them owns a share of the units and counts as a separate "
                                 + "live instance. One consume() already serves BOTH lanes — the ordering key is null for an "
                                 + "unordered message — so a second one is not 'the other lane'",
                         queueId, consumers.size() / 2, consumerInstanceId);
            }
            consumers.add(unorderedConsumer);
            consumers.add(orderedConsumer);
            attempt.registered = true;
            if (firstSubscription) {
                markStartedUnlessHolderLetsNothingStart();
            }
            if (startsNothingInside != null) {
                log.warn("Instance {} registered a subscription of queue {} under instance id '{}' WITHOUT starting it: consume() was "
                                 + "called on the thread of {} - by a QueueObserver, from inside that call - where nothing starts. "
                                 + "The subscription consumes nothing until the queue's start(), or its own start(), is called "
                                 + "once that call has returned",
                         instanceId, queueId, consumerInstanceId, startsNothingInside.describeAsAnOuterHolder());
            }
        } finally {
            // This pair only, and only once it is registered (consumers has it from here, for a stop to reach) or its
            // failed starts are undone: called by an observer on the thread of a consume() still starting, this lets go of
            // the lock reentrantly while that consume()'s pair is still being started, and a stop that gives up waiting
            // meanwhile must still find that pair here.
            synchronized (handOver) {
                beingStarted.removeAll(pair);
            }
            letGoOfLifecycle();
        }

        return new Subscription() {
            /**
             * Set by {@link #unsubscribe()}, before it takes the pair out of the queue; read by {@link #start()} under the
             * queue's lifecycle lock.
             */
            private final AtomicBoolean unsubscribed = new AtomicBoolean();

            /**
             * Units held across <b>both</b> lanes.
             * <p>
             * It used to return the unordered consumer's count alone, so a subscription holding 4
             * unordered shards and 64 ordered units reported 4 — and the ordered lane, which has the
             * larger space and the ownership failures that are invisible in queue depth, contributed
             * nothing to the one number a caller has for "am I actually serving this queue".
             */
            @Override
            public int shardsHeld() {
                return unorderedConsumer.shardsHeld() + orderedConsumer.shardsHeld();
            }

            @Override
            public int unorderedShardsHeld() {
                return unorderedConsumer.shardsHeld();
            }

            @Override
            public int orderedUnitsHeld() {
                return orderedConsumer.shardsHeld();
            }

            /**
             * Starts each lane not yet running, and on a failure stops only the lanes this start started: a start of
             * either lane that fails with anything but a critical error ({@link Exceptions#isCriticalError}) stops
             * every lane this call started - both when neither was running - and escapes as it was thrown, with any
             * failure of those stops added to it as suppressed ({@link #startEachOrUndo}). A lane that was running
             * before this call is left running, as it was, so {@link #isStarted()} reads true only when one was; a
             * later {@code start()} runs again. It used to start them one after the other and stop at the first throw:
             * a {@link QueueObserver} throwing for the unordered lane's acquisition left that lane running and the
             * ordered lane never started. A critical error escapes untouched, with nothing stopped; the queue's
             * {@code stop()} and {@code close()} still reach a lane it left running, as they reach every registered
             * consumer.
             * <p>
             * Starts this subscription's lanes only, and leaves the queue marked as it was: after the queue's
             * {@code stop()}, the queue stays not started, so its {@code start()} still starts every subscription that stop
             * stopped, this one's lanes doing nothing the second time. It used to mark the queue started, and the queue's
             * {@code start()}, finding it started, returned at once: every other subscription stayed stopped, consuming
             * nothing, while the queue's {@code isStarted()} read true. The queue's {@code stop()} and {@code close()} stop
             * these lanes whether the queue is marked started or not.
             * <p>
             * Runs under the queue's lifecycle lock, one at a time with the queue's {@code consume()}, {@code start()} and
             * {@code stop()}. Called while a {@code consume()} or start of the queue is still starting its consumers, this
             * waits for it, as the queue's {@code start()} does; and a queue {@code stop()} that gave up waiting for this
             * one is handled as it is for the queue's {@code start()}: this starts neither lane it has not begun, stops
             * every consumer of the queue again once the one it is starting returns, and leaves the queue not started. It
             * used to take none of that, and the queue's {@code stop()} stopped consumers only when the queue was marked
             * started: a {@code start()} after the queue's {@code stop()} brought both lanes back, and every later
             * {@code stop()} or {@code close()} of the queue, finding it not started, stopped nothing, so the lanes leased,
             * heartbeated and dispatched until the process exited.
             * <p>
             * Called on the thread of a queue {@code stop()}, or of a {@code consume()} or start owing a stop or doing the
             * stop it owes - by a {@link QueueObserver} told of a release, or of an acquisition after it stopped the queue -
             * this starts neither lane, leaves the queue not started, as the queue's {@code start()} does there, and logs a
             * WARN saying so: that stop is stopping every consumer. So it does on the thread of a {@code consume()} or start
             * stopping the consumers its failed start started: that start is about to fail. It logs that WARN with a lane,
             * or both, running too: the outer call may be about to stop them.
             * <p>
             * Throws {@link IllegalStateException}, starting nothing, once the subscription has been
             * {@link #unsubscribe() unsubscribed}: its lanes are no longer the queue's, and no stop of the queue would reach
             * them.
             */
            @Override
            public void start() {
                holdLifecycle(Holder.STARTING, "start a subscription of");
                try {
                    lifecycleHook.afterLockTaken("start a subscription of");
                    // Read under the lock, which unsubscribe() takes, or hands to the holder, to deregister the pair. A start
                    // that read it false just before an unsubscribe that then handed the pair to it starts no lane from the
                    // moment the unsubscribe retired them (startEachOrUndo); a lane it started before, or a lane start
                    // already under way, is ended by the unsubscribe's stop or by this holder's, whichever comes first.
                    if (unsubscribed.get()) {
                        throw new IllegalStateException("The subscription of queue " + queueId + " under instance '" + consumerInstanceId
                                                                + "' has been unsubscribed and cannot be started again; consume() anew");
                    }
                    var startsNothingInside = outerHolderThatLetsNothingStart();
                    if (startsNothingInside != null) {
                        // startEachOrUndo would start nothing either. Said at WARN whatever the lanes are doing, as nothing
                        // else tells the caller: a lane running now may be one the outer call is about to stop (a stop, an
                        // owed stop, a queue start()'s undo), which a start from another thread would start again once that
                        // call had let go of the lock.
                        log.warn("Instance {} started neither lane of the subscription of queue {} under instance id '{}': its "
                                         + "start() was called on the thread of {} - by a QueueObserver, from inside that call - where "
                                         + "nothing starts. Call it again once that call has returned",
                                 instanceId, queueId, consumerInstanceId, startsNothingInside.describeAsAnOuterHolder());
                        return;
                    }
                    // The queue stays marked as it was, whatever this does: a non-critical failure has stopped the lanes this
                    // started, and a lane a critical error left running is registered, which is all a stop() or close() needs.
                    var queueStartRanTheStarts = queueStartRunsTheStarts;
                    queueStartRunsTheStarts = false;
                    try {
                        startEachOrUndo(pair);
                    } finally {
                        // Back to the outer start's, when an observer made this start inside one (queueStartRunsTheStarts).
                        queueStartRunsTheStarts = queueStartRanTheStarts;
                    }
                } finally {
                    letGoOfLifecycle();
                }
            }

            /**
             * Stops this subscription's two lanes only, without the queue's lifecycle lock, and leaves the queue marked as
             * it was: a {@code stop()} of the queue still stops them, each lane's stop doing nothing the second time. The
             * subscription stays registered with the queue, whose {@code start()} starts it again; {@link #unsubscribe()}
             * ends it.
             */
            @Override
            public void stop() {
                stopEach(pair);
            }

            /**
             * {@inheritDoc}
             * <p>
             * Retires both lanes ({@link ShardOwnedQueue#retire()}), so no start reaching them from then on starts them, takes
             * the pair out of the queue's registered consumers under the queue's lifecycle lock, then stops both lanes, as
             * {@link #stop()} does, so whatever started them before is stopped. Bounded as the queue's {@code stop()} is: a
             * {@code consume()} or start of the queue that holds the lock past {@link ShardOwnedQueue#SHUTDOWN_BUDGET} - on
             * an interrupted thread, at once - is left to take them out before it lets go of the lock, starting neither of
             * them from the retirement on as it goes over the registered consumers, and this stops them meanwhile without it
             * ({@link #deregister}); a lane that holder started before the retirement is stopped by whichever of the two
             * stops gets there first. It used to start them, after this had returned, and stop them again only as it let
             * go. The instance id the pair registered under is given back once its lanes have stopped, for the next
             * {@code consume()} - a lease TTL later when a lane's stop kept an ordered unit because a key was still in its
             * handler, as that unit's lease names the id until then, and only once a lane's hand-back to the database
             * that its stop gave up waiting for has finished, as that may still deregister the id ({@link #heldIndexes}).
             * What a lane's stop throws escapes, once both have stopped, as from {@link #stop()}; the subscription is
             * unsubscribed either way.
             */
            @Override
            public void unsubscribe() {
                if (!unsubscribed.compareAndSet(false, true)) {
                    return;
                }
                // Before the lanes leave the queue and are stopped: a start that still goes over them - the holder of the
                // lifecycle lock, when deregister hands them to it, or this subscription's start() that read unsubscribed
                // just before it was set - starts neither from here on; one already under way, or one that holder started
                // before, is ended by the stop below, or by the holder's own should it get there first.
                unorderedConsumer.retire();
                orderedConsumer.retire();
                var deregistration = new Deregistration(pair, subscriptionIndex);
                deregister(deregistration);
                deregistration.stopLanes();
            }

            @Override
            public boolean isStarted() {
                return unorderedConsumer.isStarted() || orderedConsumer.isStarted();
            }
        };
    }

    /**
     * The lowest subscription index no live subscription holds and no ended one still holds for its instance id's use on
     * the database ({@link #heldIndexes}), now reserved ({@link #subscriptionIndexes}).
     */
    private int reserveSubscriptionIndex() {
        synchronized (handOver) {
            var now = System.nanoTime();
            var held = heldIndexes.entrySet().iterator();
            while (held.hasNext()) {
                var entry = held.next();
                if (entry.getValue().isDue(now)) {
                    subscriptionIndexes.clear(entry.getKey());
                    held.remove();
                }
            }
            var index = subscriptionIndexes.nextClearBit(0);
            subscriptionIndexes.set(index);
            return index;
        }
    }

    /**
     * Give back the index of a subscription that has ended - unsubscribed, or a {@code consume()} that failed without
     * registering it - once every stop of its {@code lanes} owed has returned. At once, unless the instance id is still in
     * use on the database ({@link #heldIndexes}): when {@code keptOrderedUnits} - a stop of a lane kept an ordered unit -
     * only a lease TTL from now, and either way only once no lane's hand-back is still running
     * ({@link ShardOwnedQueue#handBackStillRunning()}), which {@link #reserveSubscriptionIndex} asks each time.
     *
     * @param ended what ended, for the log: {@code "an unsubscribed subscription"} or {@code "a failed consume()"}
     */
    private void releaseSubscriptionIndex(int index, List<ShardOwnedQueue> lanes, boolean keptOrderedUnits, String ended) {
        var handBackStillRunning = lanes.stream().anyMatch(ShardOwnedQueue::handBackStillRunning);
        if (!keptOrderedUnits && !handBackStillRunning) {
            synchronized (handOver) {
                subscriptionIndexes.clear(index);
            }
            return;
        }
        var leaseTtl = Duration.ofMillis(leaseTtlMillis());
        var freeFrom = keptOrderedUnits ? System.nanoTime() + leaseTtl.toNanos() : System.nanoTime();
        synchronized (handOver) {
            heldIndexes.put(index, new HeldIndex(freeFrom, lanes));
        }
        // One line per hold, naming every reason it has and what ends it: both reasons must have passed (HeldIndex.isDue).
        String until;
        String because;
        if (keptOrderedUnits && handBackStillRunning) {
            until = "for at least " + leaseTtl + ", and until the hand-back of its lanes to the database has finished, whichever "
                    + "comes later";
            because = "it kept an ordered unit whose key was still in a handler, and the unit's lease names that id until it lapses; "
                      + "and the stop of its lanes gave up waiting for their hand-back, which may still deregister that id";
        } else if (keptOrderedUnits) {
            until = "for " + leaseTtl;
            because = "it kept an ordered unit whose key was still in a handler, and the unit's lease names that id until it lapses";
        } else {
            until = "until the hand-back of its lanes to the database has finished";
            because = "the stop of its lanes gave up waiting for that hand-back, which may still deregister that id";
        }
        log.info("Instance {} keeps the instance id '{}' of {} of queue {} out of use {}: {}. A consume() of the queue before then "
                         + "registers under another instance id",
                 instanceId, consumerInstanceId(index), ended, queueId, until, because);
    }

    /**
     * An index in {@link #heldIndexes}: free again from {@code freeFromNanos} ({@link System#nanoTime()}) on, once no
     * hand-back of its {@code lanes} is still running.
     */
    private record HeldIndex(long freeFromNanos, List<ShardOwnedQueue> lanes) {
        private boolean isDue(long nowNanos) {
            return nowNanos - freeFromNanos >= 0 && lanes.stream().noneMatch(ShardOwnedQueue::handBackStillRunning);
        }
    }

    /**
     * The instance id the lanes of the subscription holding {@code subscriptionIndex} register under: the caller's for
     * index 0, suffixed with the index for any other.
     */
    private String consumerInstanceId(int subscriptionIndex) {
        return subscriptionIndex == 0 ? instanceId : instanceId + "-" + subscriptionIndex;
    }

    /**
     * The lease TTL this queue's consumers run with: how long after its last heartbeat an instance's units become
     * takeable by any instance.
     */
    private long leaseTtlMillis() {
        return Math.max(1_000L, settings.leaseTtlMillis());
    }

    /**
     * Take an unsubscribed subscription's lanes out of {@link #consumers}, under {@link #lifecycleLock}, so that no later
     * {@link #start()} - which goes over {@code consumers} under that lock - reaches them. Waits for the lock up to
     * {@link ShardOwnedQueue#SHUTDOWN_BUDGET}, as {@link #stop()} does, and on an interrupted thread not at all. Past
     * that, or on the thread already holding the lock - a {@link QueueObserver} told of an acquisition or a release -
     * it hands them to the holder ({@link #toDeregister}), which may still go over them but starts neither from then on,
     * as the caller has retired them ({@link #startEachOrUndo}), and takes them out and stops them again before it lets
     * go ({@link #letGoOfLifecycle}). The caller stops them either way ({@link Deregistration#stopLanes}); a lane the
     * holder started before the retirement is stopped by whichever of the two stops gets there first.
     * <p>
     * Deregisters the pair from this queue, not the instance id from the database: the pair's membership row is deleted
     * by a lane's own stop, and only when that stop kept no ordered unit and the other lane no longer needs the row - it
     * is not running, not still draining, and its last stop kept no ordered unit ({@link ShardOwnedQueue#shareMembershipWith}).
     * A unit kept by either lane's stop therefore leaves the row to age out a lease TTL after the last heartbeat, even
     * when the other lane stops after it keeping nothing. Only the lanes know when their stops have finished - a lane's stop may hand its work to a start still running, and
     * its hand-back may outlast {@link ShardOwnedQueue#SHUTDOWN_BUDGET} - which is why that is not done here.
     */
    private void deregister(Deregistration deregistration) {
        if (lifecycleLock.isHeldByCurrentThread()) {
            synchronized (handOver) {
                deregistration.handOverToTheHolder();
            }
            return;
        }
        boolean locked;
        var     interrupted = false;
        try {
            locked = lifecycleLock.tryLock(ShardOwnedQueue.SHUTDOWN_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Restored, so the caller still sees it; each lane's stop then does not wait for its start either.
            Thread.currentThread().interrupt();
            locked = false;
            interrupted = true;
        }
        while (!locked) {
            synchronized (handOver) {
                if (holder != Holder.NONE) {
                    // Every holder passes letGoOfLifecycle's last check, which takes these, under handOver before it says
                    // the lock is free (NONE): it cannot let go without them.
                    deregistration.handOverToTheHolder();
                    var heldBy = holder.isAStart() ? "a consume() or start() of the queue, or a start() of one of its subscriptions"
                                                   : "a stop() of the queue";
                    if (interrupted) {
                        log.warn("Instance {} was interrupted while its unsubscribe of one of the subscriptions of queue {} waited "
                                         + "for the queue's lifecycle lock, held by {}: that holder starts neither of the subscription's "
                                         + "lanes from now on, and takes them out of the queue before it lets go",
                                 instanceId, queueId, heldBy);
                    } else {
                        log.warn("Instance {} gave up waiting {} for the lifecycle lock of queue {} to unsubscribe one of its "
                                         + "subscriptions, held by {}: that holder starts neither of the subscription's lanes from now on, and "
                                         + "takes them out of the queue before it lets go",
                                 instanceId, ShardOwnedQueue.SHUTDOWN_BUDGET, queueId, heldBy);
                    }
                    return;
                }
            }
            // Holder.NONE: whoever holds the lock has just taken it, or is just letting go of it.
            locked = tryLockAfterAHandOver();
        }
        try {
            // No holder value: held for this removal alone, which a stop or start waiting for the lock reads as NONE - a
            // holder that has just taken the lock or is about to let go of it.
            consumers.removeAll(deregistration.lanes);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * An unsubscribed subscription on its way out: its two lanes and its subscription index, which goes back to
     * {@link #subscriptionIndexes} - the instance id the lanes registered under then free for the next {@link #consume}
     * - once every stop of the lanes owed has returned: the unsubscribe's own, and, when it handed the deregistration to
     * the holder of {@link #lifecycleLock}, that holder's too. Given back earlier, a {@code consume()} could start a pair
     * under the same instance id while one of those stops was still releasing the old pair's units and deregistering
     * that id. A stop returning does not mean its database work is done, though: each lane's stop waits for its hand-back
     * - flushing acknowledgements, releasing lease rows, deregistering the id - no longer than
     * {@link ShardOwnedQueue#SHUTDOWN_BUDGET}, and the abandoned thread may deregister the id later; so the index stays
     * held until no lane's hand-back is still running ({@link ShardOwnedQueue#handBackStillRunning()},
     * {@link #heldIndexes}). A critical error ({@link Exceptions#isCriticalError}) from a stop may leave a lane running
     * under the id, so the index is kept then. A stop that kept an ordered unit because a key was still in its handler
     * ({@link ShardOwnedQueue#keptOrderedUnitsOnLastStop()}) leaves the unit's lease row naming the id until the
     * instance's liveness lapses, so the index is given back only a lease TTL after the last stop
     * ({@link #releaseSubscriptionIndex}): a pair registering under the id before then would take that unit at once.
     */
    private final class Deregistration {
        private final List<ShardOwnedQueue> lanes;
        private final int                   subscriptionIndex;
        private final AtomicInteger         stopsToGo = new AtomicInteger(1);
        /**
         * Whether a stop of the lanes owed kept an ordered unit: either of the two stops - the unsubscribe's or the
         * holder's - may find a lane already stopped by the other, which leaves the lane's own answer as that other stop
         * set it, so the two are ORed.
         */
        private final AtomicBoolean         keptOrderedUnits = new AtomicBoolean();

        private Deregistration(List<ShardOwnedQueue> lanes, int subscriptionIndex) {
            this.lanes = lanes;
            this.subscriptionIndex = subscriptionIndex;
        }

        /**
         * Under {@link #handOver}: leave taking the lanes out to the holder, which stops them once more.
         */
        private void handOverToTheHolder() {
            stopsToGo.incrementAndGet();
            toDeregister.add(this);
        }

        /**
         * The unsubscribe's stop of both lanes, which throws what {@link #stopEach} throws once both have stopped.
         */
        private void stopLanes() {
            try {
                stopEach(lanes);
            } catch (RuntimeException | Error e) {
                // stopEach has stopped both lanes before it throws anything but a critical error.
                if (!Exceptions.isCriticalError(e)) {
                    stopped();
                }
                throw e;
            }
            stopped();
        }

        /**
         * One owed stop of both lanes has returned; the last gives the subscription index back
         * ({@link #releaseSubscriptionIndex}).
         */
        private void stopped() {
            if (lanes.stream().anyMatch(ShardOwnedQueue::keptOrderedUnitsOnLastStop)) {
                keptOrderedUnits.set(true);
            }
            if (stopsToGo.decrementAndGet() == 0) {
                releaseSubscriptionIndex(subscriptionIndex, lanes, keptOrderedUnits.get(), "an unsubscribed subscription");
            }
        }
    }

    /**
     * The lane consumers registered with this queue, in registration order - two per subscription not unsubscribed.
     */
    List<ShardOwnedQueue> registeredConsumers() {
        return List.copyOf(consumers);
    }

    /**
     * Adapt a checked-exception handler to the engine's unchecked contract. A handler that throws a
     * checked exception means the same thing as one that throws unchecked — the message failed — and
     * the redelivery policy should not care which.
     */
    private void invoke(MessageHandler handler, MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
        if (interceptors.isEmpty()) {
            // The hot path, once per delivered message. No operation object, no chain, no lambda.
            deliver(handler, messageId, key, keyOrder, payload, payloadType);
            return;
        }
        var operation = new HandleMessage(messageId, key, keyOrder, payload, payloadType);
        InterceptorChain.<HandleMessage, Void, MessageQueueInterceptor>newInterceptorChainForOperation(
                operation,
                interceptors,
                (interceptor, chain) -> interceptor.intercept(operation, chain),
                () -> {
                    deliver(handler, messageId, key, keyOrder, payload, payloadType);
                    return null;
                }).proceed();
    }

    private void deliver(MessageHandler handler, MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
        var startNanos       = System.nanoTime();
        var handlerFailure   = new Throwable[1];
        var handlerSucceeded = new boolean[1];
        Runnable delivery = () -> {
            try {
                handler.handle(messageId, key, keyOrder, payload, payloadType);
                handlerSucceeded[0] = true;
            } catch (RuntimeException e) {
                handlerFailure[0] = e;
                throw e;
            } catch (Exception e) {
                handlerFailure[0] = e;
                throw new IllegalStateException(e);
            }
        };
        try {
            // Wrapped rather than notified-after, because a tracing span has to enclose the work. The
            // observers wrap in registration order, innermost last.
            var wrapped = delivery;
            for (var observer : observers) {
                var inner = wrapped;
                wrapped = () -> observer.aroundDelivery(key, inner);
            }
            wrapped.run();
        } catch (RuntimeException e) {
            // Whose exception is this?
            //
            // Everything thrown out of the wrapper chain used to be reported as a failed message, so
            // a broken observer got the message retried and eventually dead-lettered — while
            // QueueObserver's own javadoc says an observer's exception is the observer's bug. An
            // observer must not be able to destroy a message it was only supposed to watch.
            //
            // The handler succeeded and something outside it threw: the message IS handled, and the
            // observer is broken. Log it loudly and acknowledge.
            if (handlerSucceeded[0] && handlerFailure[0] == null) {
                log.error("An observer threw after the handler for key {} had already succeeded. "
                                  + "The message is acknowledged; fix the observer", key, e);
            } else {
                // Either the handler threw, or an observer threw BEFORE calling through — in which
                // case the message genuinely was not handled and must be retried.
                // deliveryFailed is NOT reported here: this frame does not know which attempt this
                // was, and an earlier version said "attempt 0" for every failure. The owners emit
                // it, from the one place that counts attempts.
                throw e;
            }
        }
        var duration = System.nanoTime() - startNanos;
        notifyObservers(observer -> observer.delivered(key, duration));
    }

    @Override
    public QueueSession openSession(SessionScope scope, Duration leaseDuration) throws SQLException {
        requireNonNull(scope, "No scope provided");
        requireNonNull(leaseDuration, "No leaseDuration provided");
        return switch (scope) {
            case SHARD -> new ShardQueueSession(storage, dataSource, instanceId + "-session-" + UUID.randomUUID(),
                                                shardCount, shardCount, leaseDuration);
            case MESSAGE, BATCH -> new RowLeaseQueueSession(storage, dataSource, shardCount, scope, leaseDuration);
        };
    }

    /**
     * Summed across this process's subscriptions to the queue. Zero when this instance consumes none
     * of it. An unsubscribed subscription ({@link Subscription#unsubscribe()}) is no longer one of them, and its counts
     * leave the sum with it.
     * <p>
     * <b>Per distinct metrics object, not per consumer.</b> {@link #consume} gives a subscription's
     * two lane consumers <em>one</em> {@code ShardOwnerMetrics} between them — deliberately, since a
     * subscription is one thing to the caller — so summing per consumer double-counts everything.
     * That reported 136 units acquired for an instance holding 68, which is the only reason it was
     * noticed; every other counter was doubled too and merely looked large.
     */
    @Override
    public boolean hasOrderedMessagesForKey(String key) throws SQLException {
        requireNonNull(key, "No key provided");
        return storage.hasOrderedMessagesForKey(ShardOwnedSchema.unitForKey(key, orderedUnits()), key);
    }

    @Override
    public QueueStatistics statistics() {
        // No lock: consumers is copy-on-write, and the lifecycle lock is held across a consume()'s
        // database work, which would hold a metrics scrape up with it.
        var counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ShardOwnerMetrics, Boolean>());
        var total   = QueueStatistics.NONE;
        for (var consumer : consumers) {
            if (counted.add(consumer.metrics())) {
                total = total.plus(consumer.metrics().statistics());
            }
        }
        return total;
    }

    @Override
    public QueueDepth depth() throws SQLException {
        var     unordered   = 0L;
        Instant oldestReady = null;
        for (var shard = 0; shard < shardCount; shard++) {
            var depth = storage.depthOf(shard);
            unordered += depth.count();
            oldestReady = earliest(oldestReady, depth.oldestReadyAt());
        }
        // Counted against the ordered lane's own, fixed space rather than the unordered shard count.
        var ordered = 0L;
        for (var unit = 0; unit < orderedUnits(); unit++) {
            var depth = storage.orderedDepthOf(unit);
            ordered += depth.count();
            oldestReady = earliest(oldestReady, depth.oldestReadyAt());
        }
        var deadLetters = storage.deadLetterSummary();
        return new QueueDepth(unordered, ordered, deadLetters.total(), oldestReady,
                              deadLetters.parked(), deadLetters.blockedKeys());
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    @Override
    public QueueHealth health() throws SQLException {
        var owned = storage.ownedShardsPerLane(Math.max(1_000L, settings.leaseTtlMillis()));
        // The lease TTL is what countLiveInstances treats as the staleness bound, and it is derived
        // from the settings this queue was built with.
        // countInstances, not countLiveInstances: the latter is floored at one so fairShare can
        // divide by it, which would report a queue nobody is consuming as having one instance —
        // exactly the state this method exists to make visible.
        return new QueueHealth(shardCount, orderedUnits(), owned[0], owned[1],
                               storage.countInstances(Math.max(1_000L, settings.leaseTtlMillis())));
    }

    @Override
    public Optional<QueuedMessage> getMessage(MessageId messageId) throws SQLException {
        requireNonNull(messageId, "No messageId provided");
        return storage.findMessage(messageId.shard(), messageId.sequence(), isOrdered(messageId))
                      .map(stored -> new QueuedMessage(messageId, stored.key(), stored.keyOrder(), stored.payload(),
                                                       stored.payloadType(), stored.attempts(),
                                                       stored.enqueuedAt(), stored.visibleAt()));
    }

    @Override
    public boolean deleteMessage(MessageId messageId) throws SQLException {
        requireNonNull(messageId, "No messageId provided");
        // A dead letter keeps the id it had in its lane, so the same id addresses it after it is
        // parked. The live lane is tried first; a sequence value is never reused, so the id cannot
        // match a live row and a dead letter at once.
        return storage.deleteMessage(messageId.shard(), messageId.sequence(), isOrdered(messageId))
                || storage.deleteDeadLetter(messageId.shard(), messageId.sequence(), isOrdered(messageId));
    }

    @Override
    public boolean retryMessage(MessageId messageId, Duration delay) throws SQLException {
        requireNonNull(messageId, "No messageId provided");
        requireNonNull(delay, "No delay provided");
        return storage.retryMessage(messageId.shard(), messageId.sequence(), isOrdered(messageId),
                                    Math.max(0L, delay.toMillis()));
    }

    @Override
    public boolean markAsDeadLetter(MessageId messageId, String reason) throws SQLException {
        requireNonNull(messageId, "No messageId provided");
        return storage.deadLetterMessage(messageId.shard(), messageId.sequence(), isOrdered(messageId),
                                         reason == null ? "marked as dead letter by an administrator" : reason);
    }

    private static boolean isOrdered(MessageId messageId) {
        return messageId.lane() == MessageId.Lane.ORDERED;
    }

    @Override
    public List<QueuedMessage> messages(int offset, int limit, boolean ascending) throws SQLException {
        requireTrue(offset >= 0, "offset must not be negative");
        requireTrue(limit > 0, "limit must be positive");
        return storage.listMessages(offset, limit, ascending).stream()
                      .map(listed -> new QueuedMessage(new MessageId("ordered".equals(listed.lane())
                                                                     ? MessageId.Lane.ORDERED
                                                                     : MessageId.Lane.UNORDERED,
                                                                     listed.shard(), listed.seq()),
                                                       listed.message().key(),
                                                       listed.message().keyOrder(),
                                                       listed.message().payload(),
                                                       listed.message().payloadType(),
                                                       listed.message().attempts(),
                                                       listed.message().enqueuedAt(),
                                                       listed.message().visibleAt()))
                      .toList();
    }

    @Override
    public List<DeadLetter> deadLetters(int offset, int limit) throws SQLException {
        return storage.deadLetters(offset, limit).stream()
                      .map(row -> new DeadLetter(new MessageId("ordered".equals(row.lane())
                                                               ? MessageId.Lane.ORDERED
                                                               : MessageId.Lane.UNORDERED,
                                                               row.shard(), row.seq()),
                                                 row.key(), row.keyOrder(), row.payload(), row.payloadType(),
                                                 row.attempts(), row.error(), row.blockedByKeyOrder()))
                      .toList();
    }

    @Override
    public boolean resurrect(MessageId messageId) throws SQLException {
        requireNonNull(messageId, "No messageId provided");
        return storage.resurrect(messageId.shard(), messageId.sequence(),
                                 messageId.lane() == MessageId.Lane.ORDERED ? "ordered" : "unordered");
    }

    @Override
    public int resurrectKey(String key) throws SQLException {
        requireNonNull(key, "No key provided");
        // Routed exactly as an enqueue would route it, so this addresses the same unit the key's
        // messages live in — there is only one, which is why the whole key can be restored at once.
        var restored = storage.resurrectKey(ShardOwnedSchema.unitForKey(key, orderedUnits()), key);
        if (restored > 0) {
            log.info("Queue {}: resurrected {} dead letter(s) for key '{}' — the key resumes at its lowest "
                             + "restored key_order", queueId, restored, key);
        }
        return restored;
    }

    @Override
    public long purge() throws SQLException {
        return storage.purge();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Restarts consumers this queue created and then stopped. A queue that has never had a consumer
     * has nothing to start, and enqueueing and reading depth never required it — matching
     * {@code DurableQueues}, where the lifecycle governs the consuming side.
     * <p>
     * Starts every consumer or none. A consumer's start that fails with anything but a critical error
     * ({@link Exceptions#isCriticalError}) - a {@link QueueObserver} throwing for an acquisition, say, which escapes that
     * consumer's start with the consumer running - stops every consumer this start started and escapes as it was thrown, with
     * any failure of those stops added to it as suppressed; the queue is then not started, so a later {@code start()}
     * runs again. It used to start them one after the other and stop at the first throw, which left the consumers
     * before it running, the ones after it never started, and the queue reporting itself started, so a second
     * {@code start()} did nothing. A critical error escapes untouched and the queue stays started; a {@link #stop()}
     * still stops what it left running, as it stops every registered consumer: a start's critical error stops nothing,
     * and a stop's while undoing a failed start leaves the consumers after it unstopped.
     * <p>
     * On a queue marked started, this does nothing. A {@link Subscription#start()}, and a {@link #consume} other than the
     * queue's first, start their own lanes and leave the queue marked as it was, so after a {@link #stop()} this still
     * starts every consumer that stop stopped; a consumer such a start has already started again stays running, its
     * start doing nothing the second time. It stays running when this start fails too: a failure with anything but a
     * critical error stops only the consumers this start started, and leaves the queue not started
     * ({@link #startEachOrUndo}). It used to stop every consumer, the subscription started on its own and already
     * consuming included, and nothing started it again.
     * <p>
     * A {@link #stop()} that gave up waiting for this start stops the queue without it. This start then starts none of
     * the consumers it has not begun yet, stops every consumer again once the one it is starting returns, and returns
     * with the queue not started.
     * <p>
     * Called on the thread of a {@code stop()}, or of a {@code consume()} or start owing a stop or doing the stop it owes
     * - by a {@link QueueObserver} told of a release, say - this does nothing but log a WARN saying so: that stop is
     * stopping every consumer and leaves the queue not started. So it does on the thread of a {@code consume()} or start
     * stopping the consumers its failed start started ({@link #startEachOrUndo}). It used to mark the queue started and
     * start every consumer while that stop was still stopping them, which left the queue marked started, the consumers
     * before the observer's running and the ones after it stopped. One case returns there with no WARN: a queue marked
     * started, on the thread of a {@code consume()} or {@link Subscription#start()} undoing its failed start, which leaves
     * a started queue started - this returns at once, as from any thread. On the thread of a {@code start()} undoing its
     * own failed start the queue reads started too, marked by that start, but that start unmarks it as it fails, so this
     * logs a WARN saying so.
     * <p>
     * Called while an earlier {@link #consume} or {@code start()} is still starting its consumers - after such a stop
     * returned, say - this waits for it, as {@code consume()} does: as long as that start's statements take, which
     * nothing in this queue bounds. Once it has waited {@link ShardOwnedQueue#SHUTDOWN_BUDGET}, it logs one WARN naming
     * the queue and the instance, and goes on waiting.
     */
    @Override
    public void start() {
        // The flag flips INSIDE the lock, with consume() and stop(). Flipping it first left a window
        // in which stop() had already declared the queue stopped but had not stopped anything, so a
        // concurrent consume() could observe the flag, set it back, and leave the two states
        // disagreeing about which consumers were running.
        holdLifecycle(Holder.STARTING, "start");
        try {
            lifecycleHook.afterLockTaken("start");
            // Under handOver, and not while the holder lets nothing start, as consume() sets it
            // (markStartedUnlessHolderLetsNothingStart). A stop that did not get the lock, moving this start to STOP_OWED, has
            // already marked the queue not started and may have returned; setting the flag after it made isStarted() read
            // true until letGoOfLifecycle, which does that stop's work, cleared it again. And a start called on the thread
            // of a holder stopping every consumer (FINISHING_OWED_STOP, STOPPING), or the ones its failed start started
            // (UNDOING_A_START) - by an observer told of a release - set it and started every consumer while that holder
            // was still stopping them: the queue ended marked started, with the consumers before the observer's running
            // and the ones after it stopped.
            //
            // On the thread of a holder that lets nothing start, this starts nothing and says so at WARN, as nothing else
            // tells the caller, with one exception: a queue marked started inside the undo of a consume() or a
            // Subscription.start(), which leaves it started - returned at once, as from any thread. Every other such holder
            // has marked the queue not started (a stop, an owed stop), but a start() undoing its own failed start marked it
            // started itself and unmarks it as that failure escapes: that queue ends not started, so it is warned about.
            Holder  startsNothingInside;
            boolean insideAFailingQueueStart = false;
            synchronized (handOver) {
                startsNothingInside = outerHolderThatLetsNothingStart();
                if (startsNothingInside == null) {
                    if (holder.letsNothingStart() || !started.compareAndSet(false, true)) {
                        return;
                    }
                } else if (started.get()) {
                    if (startsNothingInside != Holder.UNDOING_A_START || !queueStartRunsTheStarts) {
                        return;
                    }
                    insideAFailingQueueStart = true;
                }
            }
            if (insideAFailingQueueStart) {
                log.warn("Instance {} did not start queue {}: start() was called on the thread of a start() of the queue stopping the "
                                 + "consumers its failed start started - by a QueueObserver, from inside that call - where nothing "
                                 + "starts. The queue reads started until that start() fails, which marks it not started unless it "
                                 + "fails with a critical error. Call it again once that call has returned",
                         instanceId, queueId);
                return;
            }
            if (startsNothingInside != null) {
                log.warn("Instance {} did not start queue {}: start() was called on the thread of {} - by a QueueObserver, from "
                                 + "inside that call - where nothing starts, and the queue stays not started. Call it again once "
                                 + "that call has returned",
                         instanceId, queueId, startsNothingInside.describeAsAnOuterHolder());
                return;
            }
            // Set for this start's own starts, and put back after them: an observer's call on this thread nests this start
            // inside a consume() or Subscription.start(), or a consume() or Subscription.start() inside it, and a start()
            // called inside an undo must read the call undoing its start (queueStartRunsTheStarts).
            var queueStartRanTheStarts = queueStartRunsTheStarts;
            queueStartRunsTheStarts = true;
            try {
                startEachOrUndo(consumers);
            } catch (RuntimeException | Error e) {
                // A non-critical failure has stopped every consumer this start started, and left running the ones already
                // started before it (a Subscription.start(), a later consume()). A critical error - the start's, or a stop's
                // while undoing it - may have left some running, so the queue stays started; a stop() reaches them either way.
                if (!Exceptions.isCriticalError(e)) {
                    started.set(false);
                }
                throw e;
            } finally {
                queueStartRunsTheStarts = queueStartRanTheStarts;
            }
        } finally {
            letGoOfLifecycle();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The consumers stay registered so {@link #start()} can bring them back — that is what
     * {@code Lifecycle} means by restartable, and clearing them here made a restarted queue silently
     * consume nothing. Only {@link Subscription#unsubscribe()} takes a subscription's consumers out.
     * Each consumer releases its shard leases on the way down, so a successor picks
     * them up immediately rather than waiting out the lease.
     * <p>
     * Stops every registered consumer whether the queue is marked started or not - one a {@link Subscription#start()}
     * or a later {@link #consume} started after an earlier stop, with the queue left not started, included - each one
     * already stopped doing nothing. It used to stop them only when the queue was marked started, which is why such a
     * start had to mark the queue started; that made the queue's {@link #start()} return at once with every other
     * subscription stopped.
     * <p>
     * Every consumer is stopped even when an earlier one's stop throws - a {@link QueueObserver} failing for a release,
     * say, with a {@code RuntimeException} or a non-critical {@code Error} - and the first such failure is thrown as it
     * was thrown once all of them have stopped, the others added to it as suppressed. A critical error
     * ({@link Exceptions#isCriticalError}) propagates at once, leaving the consumers after it unstopped.
     * <p>
     * Called while a {@link #consume}, a {@link #start()} or a {@link Subscription#start()} on another thread is still
     * starting its consumers - which registers the instance and leases units on the database, and nothing in this queue
     * bounds that - this waits for it up to the 2 s {@link ShardOwnedQueue#SHUTDOWN_BUDGET}, and no longer; on an
     * interrupted thread not at all, returning with the interrupt flag still set. Past that, the queue is not started from
     * then on, and this stops every registered consumer and the pair a {@code consume()} is starting without waiting for
     * it: the consumer still leasing waits for its start up to its own 2 s budget and then hands this stop's work to it
     * ({@link ShardOwnedQueue#stop()}), the others stop as usual, and one not started yet has nothing to stop. That
     * {@code consume()} or start then starts none of the consumers it had not begun, stops every consumer again once its
     * starts have returned - logging at WARN rather than throwing what a {@link QueueObserver} throws meanwhile, since this
     * stop's caller has already returned - and leaves the queue not started; a {@code consume()} still registers its pair
     * and returns its {@link Subscription}. A stop called while another stop holds the lock past the budget returns at
     * once: that stop is stopping every consumer, and nothing can start one before it is done.
     * <p>
     * A stop that gave up waiting so returns <b>before</b> the consumer still leasing has released its units and
     * deregistered the instance ({@link ShardOwnedQueue#stop()}), though {@link #isStarted()} is false from then on. A
     * {@link #start()} or {@code consume()} called afterwards waits for the {@code consume()} or start still
     * running. And closing the {@code DataSource} before that call returns leaves the release and the deregistration to
     * fail against a closed pool: the units stay unserved until this instance's membership row is older than the lease
     * TTL, as after a crash. Stopping a {@code ShardRuntime} passed to {@code setRuntime} before then costs the drain
     * instead: the release and the deregistration go through the queue's {@code DataSource} and still succeed, but
     * handlers still running on units that consumer adopted before the stop are no longer waited for, so their
     * acknowledgements may be lost and those messages redelivered.
     * <p>
     * Called on the thread of a {@code consume()}, a {@link #start()} or a {@link Subscription#start()} of this queue
     * still starting its consumers - a {@link QueueObserver} told of an acquisition, say - this marks the queue not
     * started and returns without stopping anything itself: that start then starts none of the consumers it had not
     * begun, and stops every consumer before it returns, leaving the queue not started, as after a stop that gave up
     * waiting for it. Called on the thread of a stop, from an observer told of a release, it does nothing: that stop is
     * stopping every consumer.
     */
    @Override
    public void stop() {
        if (lifecycleLock.isHeldByCurrentThread()) {
            stopFromInsideTheHolder();
            return;
        }
        var     interrupted = false;
        boolean locked;
        try {
            locked = lifecycleLock.tryLock(ShardOwnedQueue.SHUTDOWN_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Restored, so the caller still sees it; each consumer's stop below then does not wait for its start either.
            Thread.currentThread().interrupt();
            interrupted = true;
            locked = false;
        }
        while (!locked) {
            Holder                heldBy;
            List<ShardOwnedQueue> toStop = List.of();
            synchronized (handOver) {
                heldBy = holder;
                if (heldBy.isAStart()) {
                    moveHolder(Holder.STOP_OWED);
                    stopsWithoutLock++;
                    started.set(false);
                    toStop = registeredAndBeingStarted();
                }
            }
            if (heldBy.isAStart()) {
                stopWhileStarting(toStop, interrupted);
                return;
            }
            if (heldBy == Holder.STOPPING) {
                return;
            }
            // Holder.NONE: whoever holds the lock has just taken it, or is just letting go of it. The queue may still be
            // started, so take the lock and look, a few milliseconds at a time, parked; returning here, as on STOPPING,
            // could leave it running.
            locked = tryLockAfterAHandOver();
        }
        markHeld(Holder.STOPPING);
        try {
            // Every consumer, not only when the queue is marked started: a Subscription.start() or a later consume() after an
            // earlier stop runs its lanes with the queue left not started.
            started.set(false);
            stopEach(consumers);
        } finally {
            letGoOfLifecycle();
        }
    }

    /**
     * A {@link #stop()} on the thread that holds {@link #lifecycleLock} - a {@link QueueObserver} told of an acquisition
     * while a {@code consume()} or a start starts its consumers, or of a release while a stop stops them. Inside a start
     * it is owed exactly as a stop that did not get the lock is ({@link #stopWhileStarting}), less the stopping, which
     * that start does before it returns ({@link #letGoOfLifecycle}); {@link #stopsWithoutLock} is left alone, as the
     * holder would wait for itself. Inside a stop there is nothing left to do. It used to take the lock again, reentrantly,
     * and do what a stop holding it does: nothing inside a {@code consume()}, which then marked the queue started; and
     * inside a start, stop every consumer, after which the start went on to start the ones after the observer's, with the
     * queue marked not started, so no later {@code stop()} reached them.
     */
    private void stopFromInsideTheHolder() {
        synchronized (handOver) {
            if (holder.isAStart()) {
                moveHolder(Holder.STOP_OWED);
                started.set(false);
            }
        }
    }

    /**
     * How long a {@link #stop()} that read {@link Holder#NONE} waits for {@link #lifecycleLock} before it reads
     * {@link #holder} again. Short, because the lock may meanwhile have passed to a start, which the stop must stop without
     * the lock rather than wait for; a lock let go of meanwhile is taken without waiting out the rest.
     */
    private static final Duration HAND_OVER_WAIT = Duration.ofMillis(10);

    /**
     * Wait up to {@link #HAND_OVER_WAIT} for {@link #lifecycleLock}, parked rather than spinning, through
     * {@link Locks#tryLockDeferringPendingInterrupt}: an interrupt pending on entry does not cut the wait short, one
     * arriving during it ends the wait without the lock ({@code false}) and the caller reads {@link #holder} again. Either
     * way the flag is set again afterwards, so the stop leaves it set as it found or got it, and each consumer's stop still
     * sees it.
     */
    private boolean tryLockAfterAHandOver() {
        return Locks.tryLockDeferringPendingInterrupt(lifecycleLock, HAND_OVER_WAIT);
    }

    /**
     * Move {@link #holder} to {@code target} if {@link Holder}'s diagram has an edge from where it stands to there
     * ({@link Holder#canMoveTo}), else leave it where it is. Under {@link #handOver}, which this takes; a caller that reads
     * the holder and moves it as one step holds it already. Every move of the holder goes through here, so the rule lives
     * once, as {@link ShardOwnedQueue}'s {@code Run.advanceTo} keeps it for a run's phase.
     * <p>
     * A move to where the holder stands is no move, and says nothing: a second stop that did not get the lock, or a second
     * on the holder's own thread, asks for the {@link Holder#STOP_OWED} the first left. Any other move off the diagram is
     * a bug in this queue's lifecycle - the caller goes on as though the holder had moved, so a stop it owed may never be
     * done - and is logged at ERROR, naming the queue, both holders and, through the stack trace, the caller; the holder
     * stays where it is, as {@code Run.advanceTo} leaves a phase. A caller whose move may rightly not apply (the end of an
     * undo after a stop arrived, {@link #startEachOrUndo}) reads the holder first and asks only for a move it can make.
     */
    void moveHolder(Holder target) {
        requireNonNull(target, "No target holder provided");
        synchronized (handOver) {
            var previous = holder;
            if (previous.canMoveTo(target)) {
                holder = target;
            } else if (previous != target) {
                log.error("Instance {}: the holder of the lifecycle lock of queue {} was asked to move from {} to {}, which Holder's "
                                  + "diagram has no edge for, and stays at {}. This is a bug in the queue's lifecycle: the caller "
                                  + "goes on as though the holder had moved, and a stop it owes may never be done",
                          instanceId, queueId, previous, target, previous,
                          new IllegalStateException("Holder of queue " + queueId + " asked to move from " + previous + " to " + target));
            }
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
     * the lock, a stop on the holder's own thread, the holder's starts ({@link #holderLetsNothingStart},
     * {@link #markStartedUnlessHolderLetsNothingStart}) and its letting go ({@link #letGoOfLifecycle}) each decide from it.
     * {@link #stopsWithoutLock} and {@link #beingStarted} are data beside it, not part of the decision.
     * <pre>
     *   NONE ----------------&gt; STARTING | STOPPING
     *   STARTING ------------&gt; UNDOING_A_START | STOP_OWED | NONE
     *   UNDOING_A_START -----&gt; STARTING | STOP_OWED         (STOP_OWED: a stop arriving meanwhile)
     *   STOP_OWED -----------&gt; FINISHING_OWED_STOP | NONE   (NONE: a critical error escaping the holder)
     *   FINISHING_OWED_STOP -&gt; STOP_OWED | NONE             (STOP_OWED: a stop arriving meanwhile)
     *   STOPPING ------------&gt; NONE
     * </pre>
     * A stop that did not get the lock stops every consumer itself, counted in {@link #stopsWithoutLock}; the holder waits
     * for those stops, then stops every consumer again ({@link #FINISHING_OWED_STOP}), and is owed again by a stop arriving
     * meanwhile, so it repeats the stopping. The adapter's {@code ShardOwnedDurableQueueConsumer}, whose stop without the
     * lock stops nothing and which undoes no start of its own, has five of these values, without {@link #UNDOING_A_START}
     * and without that last edge.
     * <p>
     * Not forward-only, as {@code ShardOwnedQueue}'s {@code Run.Phase} is: that phase belongs to one run, a fresh object
     * per start, while this queue has no per-start object to hang a phase on - this describes whoever holds the lock now,
     * and goes back to {@link #NONE} as each holder lets go. A reentrant hold (an observer starting the queue from inside a
     * {@code consume()}) does not move it ({@link #markHeld}); inside a holder that lets nothing start
     * ({@link #letsNothingStart}), such a hold starts nothing and does not mark the queue started.
     */
    enum Holder {
        /**
         * Nobody holds the lock, or a holder has just taken it or is about to let go of it: the stop tries the lock again,
         * a few milliseconds at a time, parked; a start waiting for it says nothing yet, and reads it again one
         * {@link ShardOwnedQueue#SHUTDOWN_BUDGET} later ({@link #holdLifecycle}).
         */
        NONE,
        /**
         * A {@code consume()}, a {@code start()} or a {@link Subscription#start()} starting its consumers, owing no stop. A
         * stop that did not get the lock, or one on this holder's thread, moves it to {@link #STOP_OWED}.
         */
        STARTING,
        /**
         * A holder of the {@link #STARTING} kind whose start failed, stopping the consumers it started
         * ({@link #startEachOrUndo}), and back to {@code STARTING} once they are stopped. Nothing may start a consumer or
         * mark the queue started meanwhile ({@link #letsNothingStart}): a start on the holder's own thread - from a
         * {@link QueueObserver} told of the release of a unit the undo gives up - started every consumer, the ones being
         * stopped included, and could mark the queue started for a start that then failed. A stop that did not get the
         * lock, or one on this holder's thread, moves it to {@link #STOP_OWED}, as from {@code STARTING}.
         */
        UNDOING_A_START,
        /**
         * A holder of the {@link #STARTING} kind owes a stop: the queue is marked not started, and the holder starts none of
         * the consumers it has not begun, never marks the queue started, and once its starts have returned waits for the
         * stops still stopping without the lock and stops every consumer again ({@link #FINISHING_OWED_STOP}). Also reached
         * from {@link #FINISHING_OWED_STOP}, by a stop arriving while the holder does an owed stop: it does one more.
         */
        STOP_OWED,
        /**
         * The holder doing the stop it owed ({@link #letGoOfLifecycle}): stopping every consumer again, the queue marked not
         * started. A stop arriving meanwhile is owed again, as while {@link #STARTING}, and the holder repeats the stopping.
         */
        FINISHING_OWED_STOP,
        /** A {@code stop()}, which stops every consumer: the stop has nothing left to do. */
        STOPPING;

        /**
         * Whether the holder is a {@code consume()}, a {@code start()} or a {@link Subscription#start()} - starting,
         * undoing a failed start, owing a stop, or doing it - so a stop that does not take the lock is owed by it.
         */
        boolean isAStart() {
            return this == STARTING || this == UNDOING_A_START || this == STOP_OWED || this == FINISHING_OWED_STOP;
        }

        /**
         * Whether the diagram above has an edge from this holder to {@code target}: from {@link #NONE} to
         * {@link #STARTING} and {@link #STOPPING}; from {@code STARTING} to {@link #UNDOING_A_START}, {@link #STOP_OWED}
         * and {@code NONE}; from {@code UNDOING_A_START} to {@code STARTING} and {@code STOP_OWED}; from
         * {@code STOP_OWED} to {@link #FINISHING_OWED_STOP} and {@code NONE}; from {@code FINISHING_OWED_STOP} to
         * {@code STOP_OWED} and {@code NONE}; from {@code STOPPING} to {@code NONE} alone. Staying where it is is not an
         * edge.
         */
        boolean canMoveTo(Holder target) {
            return switch (this) {
                case NONE -> target == STARTING || target == STOPPING;
                case STARTING -> target == UNDOING_A_START || target == STOP_OWED || target == NONE;
                case UNDOING_A_START -> target == STARTING || target == STOP_OWED;
                case STOP_OWED -> target == FINISHING_OWED_STOP || target == NONE;
                case FINISHING_OWED_STOP -> target == STOP_OWED || target == NONE;
                case STOPPING -> target == NONE;
            };
        }

        /**
         * Whether the holder leaves the queue not started when it lets go: it owes a stop ({@link #STOP_OWED}), is doing
         * the stop it owed ({@link #FINISHING_OWED_STOP}), or is a stop ({@link #STOPPING}). Nothing may start a consumer
         * or mark the queue started meanwhile - a start on the holder's own thread, from a {@link QueueObserver} told of
         * an acquisition or a release, included: that holder stops, or is stopping, every consumer, so what such a start
         * began would be stopped again, or left running past the consumers the holder stops after it with the queue
         * marked started.
         */
        boolean stopsTheQueue() {
            return this == STOP_OWED || this == FINISHING_OWED_STOP || this == STOPPING;
        }

        /**
         * Whether nothing may start a consumer or mark the queue started while this holder holds the lock: it leaves the
         * queue not started ({@link #stopsTheQueue}), or is stopping the consumers a failed start of its own started
         * ({@link #UNDOING_A_START}). A start on the holder's own thread - a {@link QueueObserver} told of an acquisition
         * or a release calling {@code start()}, {@code consume()} or {@link Subscription#start()} - then starts nothing,
         * and a {@code consume()} there registers its pair unstarted; each logs a WARN saying so, but for a queue
         * {@code start()} on a queue a {@code consume()} or {@link Subscription#start()} undoing its failed start leaves
         * marked started ({@link #outerHolderThatLetsNothingStart}).
         */
        boolean letsNothingStart() {
            return stopsTheQueue() || this == UNDOING_A_START;
        }

        /**
         * This holder, as the WARN of a start made on its thread that it lets nothing start names it
         * ({@link #outerHolderThatLetsNothingStart}): what holds the lock, and why nothing starts there.
         */
        String describeAsAnOuterHolder() {
            return switch (this) {
                case STOPPING -> "a stop() of the queue, stopping every consumer";
                case STOP_OWED -> "a consume() or start() of the queue, or a start() of one of its subscriptions, owing a stop: it "
                                  + "stops every consumer before it returns";
                case FINISHING_OWED_STOP -> "a consume() or start() of the queue, or a start() of one of its subscriptions, doing a "
                                            + "stop it owed: stopping every consumer";
                case UNDOING_A_START -> "a consume() or start() of the queue, or a start() of one of its subscriptions, stopping "
                                        + "the consumers its failed start started";
                case NONE, STARTING -> "a holder of the queue's lifecycle lock (" + this + ")";
            };
        }
    }

    /**
     * What a test does once a start has taken {@link #lifecycleLock}, to reach a moment no real start can be made to hold:
     * a {@code start()} that holds the lock and has not yet looked at the {@link #holder}, which a {@link #stop()} from
     * another thread, giving up waiting for the lock, moves to {@link Holder#STOP_OWED}. Passed at construction, through
     * the builder's package-private {@link PostgresqlMessageQueueBuilder#setLifecycleHook}, and fixed for the queue's
     * life; every queue built otherwise has {@link #NONE}, which does nothing.
     */
    interface LifecycleHook {
        LifecycleHook NONE = new LifecycleHook() { };

        /**
         * Told which call has just taken {@link #lifecycleLock} - {@code "consume"}, {@code "start"} or
         * {@code "start a subscription of"}, as in the WARN of a call waiting for the lock - before it does anything
         * under it; also for a call holding it reentrantly, on the thread of the holder. Blocking here holds that call
         * with the lock held and the holder {@link Holder#STARTING} (or whatever the outer holder is), so a {@code stop()}
         * on another thread waits its {@link ShardOwnedQueue#SHUTDOWN_BUDGET} and then stops without the lock
         * ({@link #stopWhileStarting}). What it throws escapes the call, which lets go of the lock as on any failure.
         */
        default void afterLockTaken(String operation) {
        }
    }

    /**
     * Take {@link #lifecycleLock}, waiting as long as it takes, for a {@code consume()}, a {@code start()} or a
     * {@link Subscription#start()}: a stop holding it is bounded by each consumer's own stop, and a start holding it is
     * what this start has to wait for. That start is not bounded: its consumers' database work blocks as long as a
     * statement does, and after a {@link #stop()} that gave up waiting for it, it stops every consumer again before it
     * lets go ({@link #letGoOfLifecycle}). So the wait, like a plain {@code lock()}, goes on through the thread's
     * interrupt, but it is not silent: once it has waited {@link ShardOwnedQueue#SHUTDOWN_BUDGET}, it logs one WARN
     * saying what holds the lock.
     *
     * @param operation {@code "consume"}, {@code "start"} or {@code "start a subscription of"}, the call waiting, for
     *                  the WARN
     */
    private void holdLifecycle(Holder what, String operation) {
        Locks.lockWarningOnce(lifecycleLock, ShardOwnedQueue.SHUTDOWN_BUDGET, () -> {
            Holder heldBy;
            synchronized (handOver) {
                heldBy = holder;
            }
            if (heldBy == Holder.NONE) {
                // The holder has just taken the lock, or is just letting go of it - nothing to say yet: asked again one budget later.
                return false;
            }
            if (heldBy.isAStart()) {
                log.warn("Instance {} has waited at least {} and is still waiting to {} queue {}: an earlier consume() or start() of "
                                 + "the queue, or start() of one of its subscriptions, is still starting its consumers - "
                                 + "registering the instance and leasing its units, and, if a stop gave up waiting for "
                                 + "it, stopping every consumer again once those statements return. Nothing in this "
                                 + "queue bounds that wait: a statement that hangs ends at the driver's socketTimeout, "
                                 + "and without one never",
                         instanceId, ShardOwnedQueue.SHUTDOWN_BUDGET, operation, queueId);
            } else {
                log.warn("Instance {} has waited at least {} and is still waiting to {} queue {}: a stop of the queue is still "
                                 + "stopping its consumers",
                         instanceId, ShardOwnedQueue.SHUTDOWN_BUDGET, operation, queueId);
            }
            return true;
        });
        markHeld(what);
    }

    private void markHeld(Holder what) {
        // A reentrant hold (an observer starting the queue from inside a consume(), say) leaves the outer holder's word. A
        // stop never holds the lock reentrantly: on the holder's thread it is owed instead (stopFromInsideTheHolder).
        if (lifecycleLock.getHoldCount() == 1) {
            moveHolder(what);
        }
    }

    /**
     * Let go of {@link #lifecycleLock}, first doing what a {@link #stop()} that did not get it left to this holder: wait
     * for that stop to finish stopping consumers, so it cannot stop one a later start begins, then stop every consumer
     * again - what this holder started after the stop, and a {@code consume()}'s pair, registered since - and leave the
     * queue not started. Repeated while another such stop arrives meanwhile. And what a
     * {@link Subscription#unsubscribe()} that did not get the lock left to it ({@link #deregister}): take that
     * subscription's lanes out of {@link #consumers}, and stop them again - this holder starts neither from the moment
     * that unsubscribe retired them ({@link #startEachOrUndo}), but a lane it started before then is stopped by this
     * stop or by the unsubscribe's own, whichever gets there first - repeated while another such unsubscribe arrives
     * meanwhile.
     */
    private void letGoOfLifecycle() {
        if (lifecycleLock.getHoldCount() > 1) {
            lifecycleLock.unlock();
            return;
        }
        var done = false;
        try {
            while (!done) {
                List<Deregistration> deregistered = List.of();
                var                  owesAStop    = false;
                // The last check and saying the lock is free are one step: a stop finding the holder still a start in
                // between would owe a stop nobody does, and an unsubscribe handing its lanes over would leave them
                // registered.
                synchronized (handOver) {
                    awaitStopsWithoutLock();
                    if (holder == Holder.STOP_OWED) {
                        moveHolder(Holder.FINISHING_OWED_STOP);
                        started.set(false);
                        owesAStop = true;
                    } else if (!toDeregister.isEmpty()) {
                        deregistered = List.copyOf(toDeregister);
                        toDeregister.clear();
                        for (var deregistration : deregistered) {
                            consumers.removeAll(deregistration.lanes);
                        }
                    } else {
                        moveHolder(Holder.NONE);
                        beingStarted.clear();
                        done = true;
                    }
                }
                if (owesAStop) {
                    stopForAStopThatDidNotWait();
                } else if (!deregistered.isEmpty()) {
                    stopForAnUnsubscribeThatDidNotWait(deregistered);
                }
            }
        } finally {
            if (!done) {
                // Only a critical error from the stopping gets here. The lanes of an unsubscribed subscription still leave
                // the queue, or a later start() starts them with nothing holding them.
                synchronized (handOver) {
                    moveHolder(Holder.NONE);
                    beingStarted.clear();
                    for (var deregistration : toDeregister) {
                        consumers.removeAll(deregistration.lanes);
                    }
                    toDeregister.clear();
                }
            }
            lifecycleLock.unlock();
        }
    }

    /**
     * Stop the lanes of subscriptions unsubscribed while this holder had the lock, now taken out of {@link #consumers},
     * again. This holder started neither of them from the moment their unsubscribe retired them
     * ({@link ShardOwnedQueue#retire()}); a lane it started before then is stopped by whichever of this stop and the
     * unsubscribe's own gets there first, and the other does nothing - or waits for the stop still under way. Either way
     * this counts as one of the stops the subscription index waits for ({@link Deregistration#stopped}). Logged
     * at WARN rather than thrown, as the holder's caller did not ask for it; that unsubscribe stops both lanes itself and
     * throws its own failure.
     */
    private void stopForAnUnsubscribeThatDidNotWait(List<Deregistration> deregistered) {
        for (var deregistration : deregistered) {
            try {
                stopEach(deregistration.lanes);
            } catch (RuntimeException | Error e) {
                Exceptions.rethrowIfCriticalError(e);
                log.warn("Stopping a lane of queue {} (instance {}) failed while taking out a subscription whose unsubscribe() left "
                                 + "taking out its lanes to this holder of the queue's lifecycle lock. That unsubscribe stops the "
                                 + "lanes itself, so this is logged rather than thrown",
                         queueId, instanceId, e);
            }
            deregistration.stopped();
        }
    }

    /**
     * Under {@link #handOver}. Bounded by those stops: each consumer's {@link ShardOwnedQueue#stop()} waits at most its
     * 2 s budget for a start, and its drain and release have budgets of their own.
     */
    private void awaitStopsWithoutLock() {
        var interrupted = false;
        while (stopsWithoutLock > 0) {
            try {
                handOver.wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * How many {@link #stop()}s that did not get {@link #lifecycleLock} are still stopping consumers
     * ({@link #stopsWithoutLock}), read under {@link #handOver}.
     */
    int stopsWithoutLock() {
        synchronized (handOver) {
            return stopsWithoutLock;
        }
    }

    private void stopForAStopThatDidNotWait() {
        try {
            stopEach(consumers);
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Stopping a consumer of queue {} (instance {}) failed while finishing a stop() that did not wait for its start. "
                             + "That stop has returned, so this is logged rather than thrown; every consumer has still been stopped",
                     queueId, instanceId, e);
        }
    }

    /**
     * Under {@link #handOver}: every registered consumer, then the pair of every {@code consume()} still starting
     * ({@link #beingStarted}) - an observer's {@code consume()} on the thread of one still starting, and that one.
     */
    private List<ShardOwnedQueue> registeredAndBeingStarted() {
        var all = new ArrayList<ShardOwnedQueue>(consumers);
        for (var consumer : beingStarted) {
            if (!all.contains(consumer)) {
                all.add(consumer);
            }
        }
        return all;
    }

    /**
     * Whether the holder of {@link #lifecycleLock} lets nothing start ({@link Holder#letsNothingStart}): it owes a stop,
     * does the stop it owed, is a stop, or is stopping what its failed start started. Read under {@link #handOver}.
     */
    private boolean holderLetsNothingStart() {
        synchronized (handOver) {
            return holder.letsNothingStart();
        }
    }

    /**
     * For a {@code consume()}, a {@code start()} or a {@link Subscription#start()} that has just taken
     * {@link #lifecycleLock}: the holder, when this call holds the lock reentrantly - made on the holder's own thread, by
     * a {@link QueueObserver} told of an acquisition or a release - and that holder lets nothing start
     * ({@link Holder#letsNothingStart}); else {@code null}. Such a call starts nothing, and its caller - the observer -
     * has no other way to learn it, so the call logs a WARN, bar one case: a queue {@code start()} finding the queue
     * marked started inside the undo of a {@code consume()} or {@link Subscription#start()}, which leaves it started
     * ({@link #queueStartRunsTheStarts}). A release
     * that a lane's own failed start tells, before it escapes to {@link #startEachOrUndo}, comes while the holder is still
     * {@link Holder#STARTING}: a call made from it gets {@code null} and starts as from an acquisition. The holder cannot
     * move to a holder that lets a start through before this call returns: only its own thread, which is in this call,
     * moves it back. A call that is itself the holder is not asked about - a stop on another thread that gave up waiting
     * for it says so itself.
     */
    private Holder outerHolderThatLetsNothingStart() {
        if (lifecycleLock.getHoldCount() < 2) {
            return null;
        }
        synchronized (handOver) {
            return holder.letsNothingStart() ? holder : null;
        }
    }

    /**
     * Mark the queue started, for a {@code consume()} made while no subscription was registered, holding
     * {@link #lifecycleLock}, unless
     * the holder lets nothing start ({@link Holder#letsNothingStart}). A stop owed ({@link Holder#STOP_OWED}) has
     * already marked the queue not started, and may have returned; {@link #letGoOfLifecycle} marks it not started again
     * only once it has waited for that stop's stopping, so marking it here made {@link #isStarted()} read true in between.
     * Called on the thread of a holder that is stopping every consumer ({@link Holder#FINISHING_OWED_STOP},
     * {@link Holder#STOPPING}), or the ones its failed start started ({@link Holder#UNDOING_A_START}) - from a
     * {@link QueueObserver} told of a release - marking it left the queue started once that holder had stopped them.
     * Under {@link #handOver}, where a stop that did not get the lock marks it, so such a stop arriving after this check
     * still finds the flag set and clears it.
     */
    private void markStartedUnlessHolderLetsNothingStart() {
        synchronized (handOver) {
            if (!holder.letsNothingStart()) {
                started.set(true);
            }
        }
    }

    /**
     * A {@link #stop()} that did not get {@link #lifecycleLock} from a {@code consume()}, a {@code start()} or a
     * {@link Subscription#start()} still starting its consumers: it has marked the stop owed and the queue not started,
     * and now stops {@code toStop} without waiting for the holder.
     */
    private void stopWhileStarting(List<ShardOwnedQueue> toStop, boolean interrupted) {
        try {
            if (interrupted) {
                log.warn("Instance {} was interrupted while its stop of queue {} waited for a consume() or start() of "
                                 + "the queue, or a start() of one of its subscriptions, still starting its consumers, and "
                                 + "stopped the queue without it. Each consumer still leasing does the stop's work itself once "
                                 + "its statements return, and that consume() or start() stops every consumer again",
                         instanceId, queueId);
            } else {
                log.warn("Instance {} gave up waiting {} for a consume() or start() of queue {}, or a start() of one "
                                 + "of its subscriptions, still starting its consumers, and stopped the queue without it. Each "
                                 + "consumer still leasing does the stop's work itself once its statements return, and that "
                                 + "consume() or start() stops every consumer again",
                         instanceId, ShardOwnedQueue.SHUTDOWN_BUDGET, queueId);
            }
            stopEach(toStop);
        } finally {
            synchronized (handOver) {
                stopsWithoutLock--;
                handOver.notifyAll();
            }
        }
    }

    /**
     * Stop every one of {@code consumers}, each one even when an earlier one's stop throws, and then throw the first
     * failure as it was thrown, the others added to it as suppressed ({@link Exceptions#forEachThenThrowFirst}). A
     * consumer's stop throws only after it has done all of its stopping, when the {@link QueueObserver} it tells of a
     * release, last, throws - a {@code RuntimeException} or a non-critical {@code Error} such as a test double's
     * {@code AssertionError}; only a critical error ({@link Exceptions#isCriticalError}) ends the loop at once. The fan-out
     * observer is shared by every consumer of this queue, so stopping at the first throw left the consumers after it
     * started: still leasing, heartbeating and dispatching after the queue reported itself stopped, and a second
     * {@code stop()} found the queue already stopped.
     */
    private static void stopEach(List<ShardOwnedQueue> consumers) {
        Exceptions.forEachThenThrowFirst(consumers, ShardOwnedQueue::stop);
    }

    /**
     * Start every one of {@code consumers}, in order, or start none of them: a start that fails with anything but a
     * critical error ({@link Exceptions#isCriticalError}) stops every consumer this call started - those it started
     * before the failure, and the one that failed - each one even when an earlier one's stop throws, and then escapes as
     * it was thrown, with every stop's failure added to it as suppressed, never thrown in its place
     * ({@link Exceptions#forEachCollectingFailures}). A {@code RuntimeException} and a non-critical {@code Error} count
     * alike: a {@link QueueObserver} that throws either for an acquisition escapes a consumer's start with that consumer
     * running, and its fan-out observer is shared by every consumer of this queue. A start's critical error escapes
     * untouched, with nothing stopped, as a consumer's own start leaves it; a stop's critical error ends the stopping at
     * once and escapes, with the start's failure added to it as suppressed.
     * <p>
     * A consumer already started when this reaches it - a subscription started on its own ({@link Subscription#start()},
     * or a later {@code consume()}) before the queue's {@code start()} - is left running on such a failure, as are those
     * after the failed one, which this has not reached: this call started none of them, and its failure is not theirs.
     * It used to stop every one of {@code consumers}, so a queue {@code start()} failing on one subscription stopped
     * another the caller had started and was consuming, which then stayed stopped, nothing restarting it.
     * <p>
     * While it stops them, the holder - the caller - reads {@link Holder#UNDOING_A_START}, so a start on this thread,
     * from a {@link QueueObserver} told of the release of a unit one of those stops gives up, starts nothing and marks
     * nothing ({@link Holder#letsNothingStart}). It read {@link Holder#STARTING}, and such a {@code start()} marked the
     * queue started and started every consumer, those being stopped included, for a start that then failed. A stop
     * arriving meanwhile moves the holder to {@link Holder#STOP_OWED} as during the starts, and the holder stays there.
     * <p>
     * Starts none of {@code consumers} after the moment the holder of the lifecycle lock lets nothing start
     * ({@link #holderLetsNothingStart}). That holder is the caller - a {@code consume()}, a {@code start()} or a
     * {@link Subscription#start()} - owing a stop ({@link Holder#STOP_OWED}) to a stop that did not get the lock
     * ({@link #stopWhileStarting}) or to one on the caller's own thread, from a {@link QueueObserver} told of an
     * acquisition ({@link #stopFromInsideTheHolder}): it stops every consumer again before it lets go of the lock
     * ({@link #letGoOfLifecycle}). Or, for a caller holding the lock reentrantly - called by an observer told of a
     * release - a holder stopping every consumer ({@link Holder#FINISHING_OWED_STOP}, {@link Holder#STOPPING}) or the
     * ones its failed start started ({@link Holder#UNDOING_A_START}): this then starts nothing at all.
     * <p>
     * Starts none of {@code consumers} that a {@link Subscription#unsubscribe()} has retired
     * ({@link ShardOwnedQueue#retire()}) - one that did not get the lock, or was called on the caller's thread, left
     * taking them out to the caller ({@link #toDeregister}) - and a retired lane's own start does nothing either, should
     * the unsubscribe retire it after the check here. This used to start them, so after that {@code unsubscribe()} had
     * returned they leased units and delivered to the handler its caller had let go of, until the caller let go of the
     * lock.
     */
    private void startEachOrUndo(List<ShardOwnedQueue> consumers) {
        // Iterated as a snapshot (consumers is copy-on-write): a consume() an observer makes on this thread meanwhile starts
        // and registers its own pair, which this did not start.
        var startedHere = new ArrayList<ShardOwnedQueue>(consumers.size());
        try {
            for (var consumer : consumers) {
                if (holderLetsNothingStart()) {
                    return;
                }
                if (consumer.isRetired()) {
                    // A lane of a subscription unsubscribed while this holder had the lock, which takes it out before it
                    // lets go: its start would do nothing, after waiting for the unsubscribe's stop of it.
                    continue;
                }
                if (!consumer.isStarted()) {
                    // Before its start, which may leave it running when it throws (an observer failing for an acquisition).
                    startedHere.add(consumer);
                }
                consumer.start();
            }
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            boolean undoing;
            synchronized (handOver) {
                // Only from STARTING: a holder a stop has already reached (STOP_OWED, on this thread or another, during the
                // starts) stops every consumer once its starts return, which is the undo and more.
                undoing = holder == Holder.STARTING;
                if (undoing) {
                    moveHolder(Holder.UNDOING_A_START);
                }
            }
            try {
                Exceptions.forEachCollectingFailures(startedHere, ShardOwnedQueue::stop, e);
            } finally {
                if (undoing) {
                    synchronized (handOver) {
                        // Back to STARTING, unless a stop arriving meanwhile has moved it to STOP_OWED, which it then stays at.
                        if (holder == Holder.UNDOING_A_START) {
                            moveHolder(Holder.STARTING);
                        }
                    }
                }
            }
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Whether the queue is marked started ({@link #started}), not whether any consumer runs: after a {@link #stop()}, a
     * {@link Subscription#start()} or a later {@link #consume} runs its own lanes with this reading false. A
     * {@code Lifecycle} manager that stops only a bean reporting itself started therefore leaves those lanes running;
     * stop the queue whatever this reads - see {@link MessageQueue}'s class javadoc.
     */
    @Override
    public boolean isStarted() {
        return started.get();
    }
}
