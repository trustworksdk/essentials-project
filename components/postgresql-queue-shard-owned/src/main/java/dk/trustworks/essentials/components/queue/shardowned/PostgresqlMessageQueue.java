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
     * Added to only under {@link #lifecycleLock}, and copy-on-write so a {@link #stop()} that did not get the lock, and
     * {@link #statistics()}, can read it while a {@code consume()} adds to it.
     */
    private final List<ShardOwnedQueue>         consumers          = new CopyOnWriteArrayList<>();
    /**
     * False until something is actually running. It used to be seeded {@code true}, so a queue that
     * had never consumed reported itself started and the first {@link #start()} was a no-op.
     */
    private final AtomicBoolean                 started            = new AtomicBoolean();
    /**
     * Held across the whole of {@link #consume}'s registration and starts, {@link #start()} and {@link #stop()}, so they
     * run one at a time. A lock rather than {@code consumers}' monitor, which it used to be, because a stop must be able to
     * stop waiting for it: a {@code consume()} or {@code start()} holds it across its consumers' starts, which register
     * the instance and lease units on the database, and nothing in this queue bounds those - a statement caught by a
     * network partition blocks until the driver's {@code socketTimeout}, and without one indefinitely. A {@code stop()}
     * waiting on the monitor never reached the 2 s budget each {@link ShardOwnedQueue#stop()} keeps to. It now waits up to
     * {@link ShardOwnedQueue#SHUTDOWN_BUDGET} for this lock, on an interrupted thread not at all, and then stops without
     * it ({@link #stopWhileStarting}); what the holder still owes the stop, it does itself before it lets go
     * ({@link #letGoOfLifecycle}).
     */
    private final ReentrantLock                 lifecycleLock      = new ReentrantLock();
    /**
     * Guards {@link #holder}, {@link #beingStarted}, {@link #stopOwed} and {@link #stopsWithoutLock}. Held for a few
     * reads and writes of them only, never across a start or a stop.
     */
    private final Object                        handOver           = new Object();
    /**
     * What the holder of {@link #lifecycleLock} is doing: {@link Holder#NONE} while nobody holds it, and just after a
     * holder took it or just before it lets go.
     */
    private       Holder                        holder             = Holder.NONE;
    /**
     * The lane consumers a {@code consume()} holding {@link #lifecycleLock} is starting, not registered in
     * {@link #consumers} until their starts return: a stop that did not get the lock stops them too, so the one still
     * leasing hands the stop's work to its start, which then adopts nothing it leases.
     */
    private       List<ShardOwnedQueue>         beingStarted       = List.of();
    /**
     * Whether a stop that did not get {@link #lifecycleLock} has stopped the queue while a {@code consume()} or
     * {@code start()} held it, so the holder must stop every consumer again once its own starts have returned: it may
     * have started some after the stop stopped them, and it registers its new pair after.
     */
    private       boolean                       stopOwed;
    /**
     * Stops that did not get {@link #lifecycleLock} and are still stopping consumers. The holder waits for them before it
     * lets go, so none of them stops a consumer a later {@code start()} or {@code consume()} has just started.
     */
    private       int                           stopsWithoutLock;
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
    /**
     * Distinguishes subscriptions of one process from each other in the membership table. The first
     * uses the caller's instance id verbatim, so the ordinary single-consumer case has the id the
     * caller chose.
     */
    private final AtomicInteger                 subscriptionCount  = new AtomicInteger();

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
        // one does get its own identity.
        var subscription       = subscriptionCount.getAndIncrement();
        var consumerInstanceId = subscription == 0 ? instanceId : instanceId + "-" + subscription;

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
        //
        // Calling consume() starts the queue if it was not already started, which is what every
        // caller of this contract expects and what the tests rely on.
        //
        // The one exception to the critical section is a stop() that waited its budget for it, or
        // was interrupted: it stops the queue without the lock (stopWhileStarting), this pair
        // included. The starts below then stop at the first one not yet begun, the pair is still
        // registered, and letGoOfLifecycle stops every consumer again and leaves the queue not
        // started - as if the stop had come right after this consume().
        var pair = List.of(unorderedConsumer, orderedConsumer);
        holdLifecycle(Holder.STARTING, "consume");
        try {
            synchronized (handOver) {
                beingStarted = pair;
            }
            startEachOrStopAll(pair, this::stopIsOwed);
            // A competing consumer is legal and occasionally wanted, but the common reason for a
            // second consume() is the belief that one call serves the ordered lane and another the
            // unordered one. It does not — every consume() serves both, and the lane reaches the
            // handler as the nullable ordering key. Both symptoms of that mistake are silent: this
            // handler receives the messages the caller meant for the other one, and the process
            // counts as two instances everywhere ownership is reported, so each of them owns half
            // of what a single subscription would. This is the only place that can tell.
            //
            // Reported on what is actually registered rather than on the subscription index: a
            // consume() whose start() threw leaves nothing running but has already taken an index,
            // and warning about a competing consumer that does not exist sends the reader after the
            // wrong failure.
            if (!consumers.isEmpty()) {
                log.warn("Queue {} already has {} consumer(s) registered in this process; instance '{}' is being added as "
                                 + "another COMPETING consumer, so each of them owns a share of the units and counts as a separate "
                                 + "live instance. One consume() already serves BOTH lanes — the ordering key is null for an "
                                 + "unordered message — so a second one is not 'the other lane'",
                         queueId, consumers.size() / 2, consumerInstanceId);
            }
            consumers.add(unorderedConsumer);
            consumers.add(orderedConsumer);
            // Not when a stop that did not get the lock is owed: that stop has already marked the queue not started and
            // may have returned, and letGoOfLifecycle only marks it not started again after it has waited for that stop's
            // stopping, so isStarted() read true in between. Under handOver, where that stop marks it, so a stop arriving
            // after this check still finds the flag set and clears it.
            synchronized (handOver) {
                if (!stopOwed) {
                    started.set(true);
                }
            }
        } finally {
            letGoOfLifecycle();
        }

        return new Subscription() {
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
             * Starts both lanes, or neither: a start of either lane that fails with anything but a critical error
             * ({@link Exceptions#isCriticalError}) stops both and escapes as it was thrown, with any failure of those
             * stops added to it as suppressed, so the subscription is left not started and a later {@code start()} runs
             * again ({@link #startEachOrStopAll}). It used to start them one after the other and stop at the first throw:
             * a {@link QueueObserver} throwing for the unordered lane's acquisition left that lane running and the
             * ordered lane never started.
             */
            @Override
            public void start() {
                startEachOrStopAll(List.of(unorderedConsumer, orderedConsumer), () -> false);
            }

            @Override
            public void stop() {
                stopEach(List.of(unorderedConsumer, orderedConsumer));
            }

            @Override
            public boolean isStarted() {
                return unorderedConsumer.isStarted() || orderedConsumer.isStarted();
            }
        };
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
     * of it.
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
     * consumer's start with the consumer running - stops every consumer of this queue and escapes as it was thrown, with
     * any failure of those stops added to it as suppressed; the queue is then not started, so a later {@code start()}
     * runs again. It used to start them one after the other and stop at the first throw, which left the consumers
     * before it running, the ones after it never started, and the queue reporting itself started, so a second
     * {@code start()} did nothing. A critical error escapes untouched and the queue stays started, so a {@link #stop()}
     * still stops what it left running: a start's critical error stops nothing, and a stop's while undoing a failed
     * start leaves the consumers after it unstopped.
     * <p>
     * A {@link #stop()} that gave up waiting for this start stops the queue without it. This start then starts none of
     * the consumers it has not begun yet, stops every consumer again once the one it is starting returns, and returns
     * with the queue not started.
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
            if (!started.compareAndSet(false, true)) {
                return;
            }
            try {
                startEachOrStopAll(consumers, this::stopIsOwed);
            } catch (RuntimeException | Error e) {
                // A non-critical failure has stopped every consumer. A critical error - the start's, or a stop's while
                // undoing it - may have left some running, so the queue stays started and a stop() still reaches them.
                if (!Exceptions.isCriticalError(e)) {
                    started.set(false);
                }
                throw e;
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
     * consume nothing. Each consumer releases its shard leases on the way down, so a successor picks
     * them up immediately rather than waiting out the lease.
     * <p>
     * Every consumer is stopped even when an earlier one's stop throws - a {@link QueueObserver} failing for a release,
     * say, with a {@code RuntimeException} or a non-critical {@code Error} - and the first such failure is thrown as it
     * was thrown once all of them have stopped, the others added to it as suppressed. A critical error
     * ({@link Exceptions#isCriticalError}) propagates at once, leaving the consumers after it unstopped.
     * <p>
     * Called while a {@link #consume} or {@link #start()} on another thread is still starting its consumers - which
     * registers the instance and leases units on the database, and nothing in this queue bounds that - this waits for it
     * up to the 2 s {@link ShardOwnedQueue#SHUTDOWN_BUDGET}, and no longer; on an interrupted thread not at all, returning
     * with the interrupt flag still set. Past that, the queue is not started from then on, and this stops every
     * registered consumer and the pair a {@code consume()} is starting without waiting for it: the consumer still leasing
     * waits for its start up to its own 2 s budget and then hands this stop's work to it ({@link ShardOwnedQueue#stop()}),
     * the others stop as usual, and one not started yet has nothing to stop. The {@code consume()} or {@code start()}
     * then starts none of the consumers it had not begun, stops every consumer again once its starts have returned -
     * logging at WARN rather than throwing what a {@link QueueObserver} throws meanwhile, since this stop's caller has
     * already returned - and leaves the queue not started; a {@code consume()} still registers its pair and returns its
     * {@link Subscription}. A stop called while another stop holds the lock past the budget returns at once: that stop is
     * stopping every consumer, and nothing can start one before it is done.
     * <p>
     * A stop that gave up waiting so returns <b>before</b> the consumer still leasing has released its units and
     * deregistered the instance ({@link ShardOwnedQueue#stop()}), though {@link #isStarted()} is false from then on. A
     * {@link #start()} or {@code consume()} called afterwards waits for the {@code consume()} or {@code start()} still
     * running. And closing the {@code DataSource} before that call returns leaves the release and the deregistration to
     * fail against a closed pool: the units stay unserved until this instance's membership row is older than the lease
     * TTL, as after a crash. Stopping a {@code ShardRuntime} passed to {@code setRuntime} before then costs the drain
     * instead: the release and the deregistration go through the queue's {@code DataSource} and still succeed, but
     * handlers still running on units that consumer adopted before the stop are no longer waited for, so their
     * acknowledgements may be lost and those messages redelivered.
     */
    @Override
    public void stop() {
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
                if (heldBy == Holder.STARTING) {
                    stopOwed = true;
                    stopsWithoutLock++;
                    started.set(false);
                    toStop = registeredAndBeingStarted();
                }
            }
            if (heldBy == Holder.STARTING) {
                stopWhileStarting(toStop, interrupted);
                return;
            }
            if (heldBy == Holder.STOPPING) {
                return;
            }
            // Holder.NONE: whoever holds the lock has just taken it, or is just letting go of it.
            Thread.onSpinWait();
            locked = lifecycleLock.tryLock();
        }
        markHeld(Holder.STOPPING);
        try {
            if (started.compareAndSet(true, false)) {
                stopEach(consumers);
            }
        } finally {
            letGoOfLifecycle();
        }
    }

    /**
     * What the holder of {@link #lifecycleLock} is doing, for a {@link #stop()} that did not get it.
     */
    private enum Holder {
        /** Just taken, or about to be let go of: the stop tries the lock again. */
        NONE,
        /** A {@code consume()} or {@code start()}: the stop stops the queue without the lock, and the holder owes it. */
        STARTING,
        /** A {@code stop()}, which stops every consumer: the stop has nothing left to do. */
        STOPPING
    }

    /**
     * Take {@link #lifecycleLock}, waiting as long as it takes, for a {@code consume()} or {@code start()}: a stop holding
     * it is bounded by each consumer's own stop, and a start holding it is what this start has to wait for. That start
     * is not bounded: its consumers' database work blocks as long as a statement does, and after a {@link #stop()} that
     * gave up waiting for it, it stops every consumer again before it lets go ({@link #letGoOfLifecycle}). So the wait,
     * like a plain {@code lock()}, goes on through the thread's interrupt, but it is not silent: once it has waited
     * {@link ShardOwnedQueue#SHUTDOWN_BUDGET}, it logs one WARN saying what holds the lock.
     *
     * @param operation {@code "consume"} or {@code "start"}, the call waiting, for the WARN
     */
    private void holdLifecycle(Holder what, String operation) {
        var interrupted = false;
        var warned      = false;
        try {
            while (true) {
                try {
                    if (lifecycleLock.tryLock(ShardOwnedQueue.SHUTDOWN_BUDGET.toMillis(), TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    // Waited on regardless, as lock() would; the flag is set again once the lock is held.
                    interrupted = true;
                    continue;
                }
                Holder heldBy;
                synchronized (handOver) {
                    heldBy = holder;
                }
                // NONE: the holder has just taken the lock, or is just letting go of it - nothing to say yet.
                if (!warned && heldBy != Holder.NONE) {
                    warned = true;
                    if (heldBy == Holder.STARTING) {
                        log.warn("Instance {} has waited {} and is still waiting to {} queue {}: an earlier consume() or start() of "
                                         + "the queue is still starting its consumers - registering the instance and leasing its "
                                         + "units, and, if a stop gave up waiting for it, stopping every consumer again once those "
                                         + "statements return. Nothing in this queue bounds that wait: a statement that hangs ends at "
                                         + "the driver's socketTimeout, and without one never",
                                 instanceId, ShardOwnedQueue.SHUTDOWN_BUDGET, operation, queueId);
                    } else {
                        log.warn("Instance {} has waited {} and is still waiting to {} queue {}: a stop of the queue is still "
                                         + "stopping its consumers",
                                 instanceId, ShardOwnedQueue.SHUTDOWN_BUDGET, operation, queueId);
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        markHeld(what);
    }

    private void markHeld(Holder what) {
        // A reentrant hold (an observer stopping the queue from inside a start, say) leaves the outer holder's word.
        if (lifecycleLock.getHoldCount() == 1) {
            synchronized (handOver) {
                holder = what;
            }
        }
    }

    /**
     * Let go of {@link #lifecycleLock}, first doing what a {@link #stop()} that did not get it left to this holder: wait
     * for that stop to finish stopping consumers, so it cannot stop one a later start begins, then stop every consumer
     * again - what this holder started after the stop, and a {@code consume()}'s pair, registered since - and leave the
     * queue not started. Repeated while another such stop arrives meanwhile.
     */
    private void letGoOfLifecycle() {
        if (lifecycleLock.getHoldCount() > 1) {
            lifecycleLock.unlock();
            return;
        }
        var done = false;
        try {
            while (!done) {
                // The last check and saying the lock is free are one step: a stop finding the holder still STARTING in
                // between would owe a stop nobody does.
                synchronized (handOver) {
                    awaitStopsWithoutLock();
                    if (stopOwed) {
                        stopOwed = false;
                        started.set(false);
                    } else {
                        holder = Holder.NONE;
                        beingStarted = List.of();
                        done = true;
                    }
                }
                if (!done) {
                    stopForAStopThatDidNotWait();
                }
            }
        } finally {
            if (!done) {
                // Only a critical error from the stopping gets here.
                synchronized (handOver) {
                    holder = Holder.NONE;
                    beingStarted = List.of();
                    stopOwed = false;
                }
            }
            lifecycleLock.unlock();
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
     * Under {@link #handOver}: every registered consumer, then the pair a {@code consume()} is starting.
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

    private boolean stopIsOwed() {
        synchronized (handOver) {
            return stopOwed;
        }
    }

    /**
     * A {@link #stop()} that did not get {@link #lifecycleLock} from a {@code consume()} or {@code start()} still starting
     * its consumers: it has marked the stop owed and the queue not started, and now stops {@code toStop} without waiting
     * for the holder.
     */
    private void stopWhileStarting(List<ShardOwnedQueue> toStop, boolean interrupted) {
        try {
            if (interrupted) {
                log.warn("Instance {} was interrupted while its stop of queue {} waited for a consume() or start() still starting "
                                 + "its consumers, and stopped the queue without it. Each consumer still leasing does the stop's work "
                                 + "itself once its statements return, and the consume() or start() stops every consumer again",
                         instanceId, queueId);
            } else {
                log.warn("Instance {} gave up waiting {} for a consume() or start() of queue {} still starting its consumers, and "
                                 + "stopped the queue without it. Each consumer still leasing does the stop's work itself once its "
                                 + "statements return, and the consume() or start() stops every consumer again",
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
     * Start every one of {@code consumers}, in order, or leave none of them started. A start that fails with anything
     * but a critical error ({@link Exceptions#isCriticalError}) stops every one of them - those started before it, the
     * one that failed, and those after it, for which a stop does nothing - each one even when an earlier one's stop
     * throws, and then escapes as it was thrown, with every stop's failure added to it as suppressed, never thrown in
     * its place ({@link Exceptions#forEachCollectingFailures}). A {@code RuntimeException} and a non-critical
     * {@code Error} count alike: a {@link QueueObserver} that throws either for an acquisition escapes a consumer's
     * start with that consumer running, and its fan-out observer is shared by every consumer of this queue. A start's
     * critical error escapes untouched, with nothing stopped, as a consumer's own start leaves it; a stop's critical error
     * ends the stopping at once and escapes, with the start's failure added to it as suppressed.
     * <p>
     * Starts none of those after the moment {@code stopOwed} reports a stop that did not wait for this one
     * ({@link #stopWhileStarting}): its caller stops every consumer again before it lets go of the lock.
     */
    private static void startEachOrStopAll(List<ShardOwnedQueue> consumers, BooleanSupplier stopOwed) {
        try {
            for (var consumer : consumers) {
                if (stopOwed.getAsBoolean()) {
                    return;
                }
                consumer.start();
            }
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            Exceptions.forEachCollectingFailures(consumers, ShardOwnedQueue::stop, e);
            throw e;
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }
}
