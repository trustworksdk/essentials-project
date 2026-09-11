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
import dk.trustworks.essentials.shared.interceptor.*;

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The shard-owned engine behind the {@link MessageQueue} contract.
 * <p>
 * Written to check that the contract is implementable rather than merely well-argued. Two things it
 * turned up that the interface alone did not show: enqueue has to split a mixed batch by lane before
 * it can route anything, and a single handler signature has to serve both lanes, which is why
 * {@link MessageHandler} takes a nullable key rather than the two separate shapes the engine uses
 * internally.
 */
public final class PostgresqlMessageQueue implements MessageQueue {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlMessageQueue.class);

    private final ShardOwnedStorage storage;
    private final DataSource     dataSource;
    private final short          queueId;
    /**
     * Refreshed from the registry, because enqueue routing has to learn about a grown shard count
     * too. Wiring only the consumer side would leave a producer routing keys by the old modulus
     * while its own consumers leased the new shards — which is the divergence the registry exists to
     * prevent, reintroduced by fixing half of it.
     */
    private volatile int         shardCount;
    /** This queue's ordered routing space, from the registry. 0 until resolved; never changes after. */
    private volatile int         orderedUnits;
    private volatile long        shardCountCheckedAtNanos;
    private final String         instanceId;

    private final ShardOwnerSettings settings;

    private final List<ShardOwnedQueue> consumers = new ArrayList<>();
    /**
     * False until something is actually running. It used to be seeded {@code true}, so a queue that
     * had never consumed reported itself started and the first {@link #start()} was a no-op.
     */
    private final AtomicBoolean       started   = new AtomicBoolean();
    private final List<QueueObserver> observers = new CopyOnWriteArrayList<>();
    /**
     * Sorted by {@link InterceptorOrder} on registration rather than on every call: the sort is
     * reflection over annotations, and doing it per delivered message would put it on the hot path.
     */
    private final List<MessageQueueInterceptor> interceptors = new CopyOnWriteArrayList<>();
    /**
     * Round-robin across shards, one step per message.
     * <p>
     * Per message, not per call. Picking one shard for a whole batch — which is what a hash of the
     * instance id and {@code nanoTime} amounts to — puts every message of a five-thousand-row enqueue
     * on a single shard, so the other shards' owners sit idle and the batch is serialised behind one
     * of them. Shards are the unit of parallelism; the enqueue has to actually use them.
     */
    private final AtomicInteger enqueueShardCursor = new AtomicInteger();
    /**
     * Distinguishes subscriptions of one process from each other in the membership table. The first
     * uses the caller's instance id verbatim, so the ordinary single-consumer case has the id the
     * caller chose.
     */
    private final AtomicInteger subscriptionCount = new AtomicInteger();

    public static PostgresqlMessageQueueBuilder builder() {
        return new PostgresqlMessageQueueBuilder();
    }

    public PostgresqlMessageQueue(DataSource dataSource, short queueId, int shardCount, String instanceId) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.queueId = queueId;
        this.shardCount = shardCount;
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.settings = ShardOwnerSettings.defaults();
        this.storage = new ShardOwnedStorage(dataSource, queueId);
    }

    /**
     * @param settings engine tuning for this queue's consumers. The no-settings constructor uses
     *                 {@link ShardOwnerSettings#defaults()}; {@link #consume} used to hardcode that
     *                 call, which left the contract with no way to configure the engine at all.
     * @deprecated since 0.51.0 — use {@link #builder()} and
     *             {@link PostgresqlMessageQueueBuilder#setSettings(ShardOwnerSettings)}, which names
     *             its arguments instead of relying on the order of a {@code short}, an {@code int}
     *             and a {@code String}.
     */
    @Deprecated(forRemoval = true, since = "0.51.0")
    public PostgresqlMessageQueue(DataSource dataSource, short queueId, int shardCount, String instanceId,
                                  ShardOwnerSettings settings) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.queueId = queueId;
        this.shardCount = shardCount;
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.storage = new ShardOwnedStorage(dataSource, queueId);
    }

    /** The interned id this queue addresses. */
    public short queueId() {
        return queueId;
    }

    /** Fixed for the life of the queue; taken from the registry when built from a name. */
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
     * {@link #consume} still sees events. Handed to the engine, which has no notion of a list.
     */
    private final QueueObserver fanOut = new QueueObserver() {
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

    /** Carries a {@link SQLException} across the chain's unchecked functional boundary. */
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
        var routed = route(messages);
        var unorderedByShard = routed.unordered();
        var orderedByShard = routed.ordered();

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
        var ids = new MessageId[messages.size()];
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
    /** The routing space this queue's ordered keys live in — the registry's, not the constant's. */
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

    /** Decide the lane and shard of every message, keeping each one's position in the input. */
    private Routed route(List<Message> messages) throws SQLException {
        refreshShardCountIfDue();
        // Resolved once per queue, outside the loop: it is a property of the queue's stored data, not
        // of the batch, and it never changes while the queue exists.
        var units = orderedUnits();
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
        var orderedCount = orderedByShard.values().stream().mapToInt(List::size).sum();
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

        var policy = new RedeliveryPolicy(options.maxAttempts(), options.retryDelay(),
                                          options.retryMultiplier(), options.maxRetryDelay());

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
        var subscription = subscriptionCount.getAndIncrement();
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
                                               .build();
        unorderedConsumer.configureUnordered((payload, payloadType) -> invoke(handler, null, payload, payloadType),
                                             settings, options.maxShards(), policy);
        var orderedConsumer = ShardOwnedQueue.builder()
                                             .setDataSource(dataSource)
                                             .setQueueId(queueId)
                                             .setShardCount(shardCount)
                                             .setInstanceId(consumerInstanceId)
                                             .setMetrics(engineMetrics)
                                             .setParallelConsumers(options.parallelConsumers())
                                             .build();
        orderedConsumer.configureOrdered((key, payload, payloadType) -> invoke(handler, key, payload, payloadType),
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
        synchronized (consumers) {
            try {
                unorderedConsumer.start();
                orderedConsumer.start();
            } catch (RuntimeException e) {
                unorderedConsumer.stop();
                orderedConsumer.stop();
                throw e;
            }
            consumers.add(unorderedConsumer);
            consumers.add(orderedConsumer);
            started.set(true);
        }

        return new Subscription() {
            @Override
            public int shardsHeld() {
                return unorderedConsumer.shardsHeld();
            }

            @Override
            public void start() {
                unorderedConsumer.start();
                orderedConsumer.start();
            }

            @Override
            public void stop() {
                unorderedConsumer.stop();
                orderedConsumer.stop();
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
    private void invoke(MessageHandler handler, String key, byte[] payload, int payloadType) {
        if (interceptors.isEmpty()) {
            // The hot path, once per delivered message. No operation object, no chain, no lambda.
            deliver(handler, key, payload, payloadType);
            return;
        }
        var operation = new HandleMessage(key, payload, payloadType);
        InterceptorChain.<HandleMessage, Void, MessageQueueInterceptor>newInterceptorChainForOperation(
                operation,
                interceptors,
                (interceptor, chain) -> interceptor.intercept(operation, chain),
                () -> {
                    deliver(handler, key, payload, payloadType);
                    return null;
                }).proceed();
    }

    private void deliver(MessageHandler handler, String key, byte[] payload, int payloadType) {
        var startNanos = System.nanoTime();
        var handlerFailure = new Throwable[1];
        var handlerSucceeded = new boolean[1];
        Runnable delivery = () -> {
            try {
                handler.handle(key, payload, payloadType);
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

    @Override
    public QueueDepth depth() throws SQLException {
        var unordered = 0L;
        for (var shard = 0; shard < shardCount; shard++) {
            unordered += storage.countRemaining(shard);
        }
        // Counted against the ordered lane's own, fixed space rather than the unordered shard count.
        var ordered = 0L;
        for (var unit = 0; unit < orderedUnits(); unit++) {
            ordered += storage.countOrderedRemaining(unit);
        }
        return new QueueDepth(unordered, ordered, storage.countDeadLetters());
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
                      .map(stored -> new QueuedMessage(messageId, stored.key(), stored.payload(),
                                                       stored.payloadType(), stored.attempts(),
                                                       stored.enqueuedAt(), stored.visibleAt()));
    }

    @Override
    public boolean deleteMessage(MessageId messageId) throws SQLException {
        requireNonNull(messageId, "No messageId provided");
        return storage.deleteMessage(messageId.shard(), messageId.sequence(), isOrdered(messageId));
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
    public List<DeadLetter> deadLetters(int offset, int limit) throws SQLException {
        return storage.deadLetters(offset, limit).stream()
                      .map(row -> new DeadLetter(new MessageId("ordered".equals(row.lane())
                                                               ? MessageId.Lane.ORDERED
                                                               : MessageId.Lane.UNORDERED,
                                                               row.shard(), row.seq()),
                                                 row.key(), row.payload(), row.payloadType(),
                                                 row.attempts(), row.error()))
                      .toList();
    }

    @Override
    public boolean resurrect(MessageId messageId) throws SQLException {
        requireNonNull(messageId, "No messageId provided");
        return storage.resurrect(messageId.shard(), messageId.sequence(),
                                 messageId.lane() == MessageId.Lane.ORDERED ? "ordered" : "unordered");
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
     */
    @Override
    public void start() {
        // The flag flips INSIDE the lock, with consume() and stop(). Flipping it first left a window
        // in which stop() had already declared the queue stopped but had not stopped anything, so a
        // concurrent consume() could observe the flag, set it back, and leave the two states
        // disagreeing about which consumers were running.
        synchronized (consumers) {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            consumers.forEach(ShardOwnedQueue::start);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The consumers stay registered so {@link #start()} can bring them back — that is what
     * {@code Lifecycle} means by restartable, and clearing them here made a restarted queue silently
     * consume nothing. Each consumer releases its shard leases on the way down, so a successor picks
     * them up immediately rather than waiting out the lease.
     */
    @Override
    public void stop() {
        synchronized (consumers) {
            if (!started.compareAndSet(true, false)) {
                return;
            }
            consumers.forEach(ShardOwnedQueue::stop);
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }
}
