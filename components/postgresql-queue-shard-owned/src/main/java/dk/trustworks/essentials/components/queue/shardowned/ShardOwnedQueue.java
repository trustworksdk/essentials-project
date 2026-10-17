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

import dk.trustworks.essentials.shared.*;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The shard-owned queue engine.
 * <p>
 * Its own API rather than an adaptation of an existing one. The design arrived at batch-oriented
 * enqueue and shard-scoped consumption on its own terms, and expressing it that way first keeps the
 * shape of an older interface from leaking back into it — an adapter onto a public contract is a
 * later, separable job.
 * <p>
 * A consumer here leases shards and owns them. Competing consumers are several instances leasing
 * disjoint subsets; an exclusive consumer is one instance leasing all of them. Two modes, one
 * mechanism, no special case.
 *
 * <h2>One instance serves ONE lane</h2>
 * This is the thing to know before reading anything else here, because several members only make
 * sense once it is clear. {@link #configureUnordered} and {@link #configureOrdered} are mutually
 * exclusive: whichever is called fixes {@code activeLane} for the life of the instance, and calling
 * the other afterwards is refused rather than silently switching.
 * <p>
 * A queue with traffic on both lanes therefore needs <em>two</em> of these, which is exactly what
 * {@code PostgresqlMessageQueue.consume} builds — one unordered, one ordered, sharing an instance id
 * so the pair counts as a single member of the cluster. The two lanes have separate owners, separate
 * leases and separate unit spaces, so there is no single object that could serve both without
 * becoming two internally.
 * <p>
 * What follows from it, and would otherwise look arbitrary:
 * <ul>
 *   <li>{@link #remaining()} counts the configured lane only — the unordered table over
 *       {@code shardCount}, or the ordered table over the queue's routing space.</li>
 *   <li>{@link #shardsHeld()} likewise. The per-subscription total across both lanes is
 *       {@code Subscription.shardsHeld()}, which sums a pair of these.</li>
 *   <li>{@code shardCount} is the <b>unordered</b> lane's number. On an instance configured for the
 *       ordered lane it is inert: that lane routes over the fixed space recorded on the queue's
 *       registry row and never reads it. The builder still requires a positive value because it
 *       cannot know the lane — {@code configureX} comes after {@code build()}.</li>
 *   <li>{@link #enqueue} round-robins over {@code shardCount}; ordered messages go through
 *       {@link #enqueueOrdered}, which routes by key instead.</li>
 * </ul>
 */
public final class ShardOwnedQueue implements Lifecycle, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedQueue.class);

    private final    ShardOwnedStorage storage;
    private final    DataSource        dataSource;
    private final    short             queueId;
    /**
     * Mutable, and read on the enqueue path as well as by the heartbeat, because a queue picks up a
     * grown shard count at runtime rather than needing the process restarted. See
     * {@link #refreshShardCount()}.
     */
    private volatile int               shardCount;
    /**
     * The ordered routing space THIS queue was created with, 0 until first resolved from the
     * registry. Not {@code ShardOwnedSchema.ORDERED_UNITS}: that constant is the default for a queue
     * being created, and a queue that already holds data keeps the space its keys were routed under.
     * Resolved once, because nothing changes it while the queue exists.
     */
    private volatile int               orderedUnits;
    private final    String            instanceId;

    private final    AtomicBoolean            running             = new AtomicBoolean();
    /**
     * Which run of this queue is current: {@link #stop()} and {@link #stopAbruptly()} bump it, under {@link #runLock}, as they
     * end one. A heartbeat tick reads it before anything else, and an owner it acquires joins only the run it read
     * ({@link #adoptIntoRun}). {@code running} alone cannot say that: {@link #start()} sets it again, so a tick still inside
     * an acquire when the queue was stopped and started again under the same instance id found it set, and its owner joined
     * the new run.
     */
    private final    AtomicLong               runGeneration       = new AtomicLong();
    /**
     * Held while a heartbeat tick adopts an owner it acquired into the run ({@link #adoptIntoRun}), and while
     * {@link #stop()} ends the run - clears {@code running}, bumps {@link #runGeneration} and reads {@code owners} - so the
     * two never interleave: an owner is either adopted before the stop reads {@code owners}, and the stop ends and
     * releases it, or not adopted at all, and the tick releases it. Never both, and never neither.
     * <p>
     * Every write of {@code running} is under it: {@link #start()}'s CAS and the clear on its failure path, and
     * {@link #stopAbruptly()} as it ends the run, as well as {@code stop()}.
     * <p>
     * It orders a run's end against a heartbeat tick, not against another {@code start()} or {@code stop()}: those are
     * serialised by {@link #lifecycleLock}. Held only for short sections, never across a stop's drain or its database
     * work, because a tick takes it in {@link #adoptIntoRun} and must not wait behind them.
     */
    private final    Object                   runLock             = new Object();
    /**
     * Held across the whole of {@link #start()}, {@link #stop()} and {@link #stopAbruptly()}, so one runs at a time: a
     * {@code start()} called while a stop is still draining and releasing waits for it to finish.
     * <p>
     * And the other way round: a {@code stop()}, {@link #close()} or {@code stopAbruptly()} called while a
     * {@code start()} is still running waits for it to finish, its database work included - registering the instance
     * and leasing its units. That wait comes before, and outside, the stop's 2 s {@link #SHUTDOWN_BUDGET}, and nothing in
     * this queue bounds it. A start waiting for a connection the pool cannot make gives up at the pool's connection
     * timeout (30 s at Hikari's default). A start whose statement is already running on a pooled connection when the
     * network partitions is not covered by that timeout: the statement blocks until the driver's {@code socketTimeout},
     * and without one indefinitely, and the stop waits as long.
     * <p>
     * {@code stop()} clears {@code running} at its top, then spends up to {@code shedGrace} draining and up to its
     * shutdown budget releasing before it drops its owners. A {@code start()} on another thread used to set
     * {@code running} again in that window and list its new owners, and the stop then dropped those from
     * {@code owners}, {@code ownedShards} and {@link #acquisitionReported} while they were on pumps and serving, and
     * deregistered the instance the start had just registered. Not {@link #runLock}: a heartbeat tick takes that one,
     * and would then wait behind a stop's drain.
     */
    private final    Object                   lifecycleLock       = new Object();
    /**
     * The owners whose acquisition this queue has counted ({@link ShardOwnerMetrics#shardsAcquired}, by
     * {@link #countAcquisition}) and whose release it has not. A release is counted, and told to the {@code QueueObserver},
     * only by whoever removes the owner from here ({@link #countRelease}, {@link #reportReleased}), so it is reported once,
     * and only for an owner whose acquisition was counted: a heartbeat tick's fair-share shed or dead-pump hand-back and
     * {@link #stop()} can release the same owner at once, and both used to report it.
     * <p>
     * Membership does not mean the observer has been told of the acquisition. The count is taken under {@link #runLock};
     * the observer is told by the caller afterwards, outside it - at start-up once the heartbeat is scheduled, on a
     * heartbeat tick in a {@code finally} around its acquire loop. So a {@link #stop()} can tell the observer of a release
     * just before the tick tells it of the acquisition: every acquisition is still told, but not always first
     * ({@link #adoptIntoRun} says why the observer is kept out of the lock).
     */
    private final    Set<LeasedOwner>         acquisitionReported = ConcurrentHashMap.newKeySet();
    /**
     * Lease rows a heartbeat tick leased as its run ended and then could not release again ({@link #abandonAcquisition}):
     * unit to the fence it leased the unit under. No owner of this queue's counts such a row - the tick built none - so
     * while it names this instance nothing serves the unit: not this queue's lease check or fair share, which go by
     * {@code owners}, and not another instance, for as long as this instance's membership row is live, which it is again
     * once the queue is started under the same instance id. Each heartbeat retries the release
     * ({@link #retryUnreleasedAcquisitions}) until it has run, whether or not it found the row still under that fence.
     * <p>
     * The queue's, not a run's: {@link #stop()} leaves it as it is, so a later run's heartbeat retries what a tick of an
     * earlier run could not release. Safe against that run, because the release is fenced: a unit it has taken again holds
     * a newer fence, and is left alone.
     */
    private final    Map<Integer, Long>       unreleasedAcquisitions = new ConcurrentHashMap<>();
    private final    AtomicBoolean            flushOnExit         = new AtomicBoolean(true);
    private final    AtomicInteger            enqueueShardCursor  = new AtomicInteger();
    private final    List<LeasedOwner>        owners              = new CopyOnWriteArrayList<>();
    /**
     * Shards this instance owns, to their owner — the lookup that makes local hand-off possible.
     */
    private final    Map<Integer, ShardOwner> ownedShards         = new ConcurrentHashMap<>();
    private          String                   activeLane          = "unordered";
    private final    ShardOwnerMetrics        metrics;
    /**
     * The threads and connections this queue borrows. Shared with every other queue in the process,
     * which is what stops database contact scaling with the number of queues.
     * <p>
     * Volatile, and read once into a local by whoever is not the thread that writes it: {@link #stop()} nulls it
     * ({@link #releaseRuntime()}) for a borrowed shared runtime, and cancelling the heartbeat does not stop a tick already
     * running, so the heartbeat thread may meet it null part-way through a tick.
     */
    private volatile ShardRuntime             runtime;
    /**
     * True when this queue borrowed the shared runtime and must hand it back on stop.
     */
    private          boolean                  borrowedShared;
    /**
     * This queue's slot on the shared heartbeat scheduler.
     */
    private          ScheduledFuture<?>       heartbeat;
    private          long                     leaseTtlMillis;
    private          PayloadHandler           activeHandler;
    private          OrderedPayloadHandler    activeOrderedHandler;
    private          ShardOwnerSettings       activeSettings;
    private          RedeliveryPolicy         activePolicy;
    private          int                      maxShardsHeld;
    /**
     * Tier 2 can be turned off so its effect can be measured rather than assumed. It is a latency
     * mechanism, and the cost decomposition showed it is not free: the pre-claim stamp widens the
     * row and local hand-offs wake the owner more often.
     */
    private volatile boolean                  localHandoffEnabled = true;
    /**
     * How many handler invocations this consumer may have in flight. See {@code ConsumerOptions}.
     */
    private          int                      parallelConsumers   = 8;
    private          HandlerDispatch          dispatch;

    /**
     * Set before {@link #start()}. Per consumer, not per process — see {@code ConsumerOptions}.
     */
    public ShardOwnedQueue setParallelConsumers(int parallelConsumers) {
        this.parallelConsumers = parallelConsumers;
        return this;
    }

    public void setLocalHandoffEnabled(boolean enabled) {
        this.localHandoffEnabled = enabled;
    }

    public static ShardOwnedQueueBuilder builder() {
        return new ShardOwnedQueueBuilder();
    }

    private volatile long lastSharedInstanceIdWarningNanos;

    public ShardOwnedQueue(DataSource dataSource, short queueId, int shardCount, String instanceId) {
        this(dataSource, queueId, shardCount, instanceId, new ShardOwnerMetrics());
    }

    /**
     * Join a runtime shared with the other queues in this process. Set before {@link #start()}.
     */
    ShardOwnedQueue useRuntime(ShardRuntime runtime) {
        this.runtime = requireNonNull(runtime, "No runtime provided");
        this.borrowedShared = false;
        return this;
    }

    /**
     * @param metrics the engine's observability wiring — its own counters, and the consumer-supplied
     *                {@link dk.trustworks.essentials.components.queue.shardowned.spi.QueueObserver}
     */
    ShardOwnedQueue(DataSource dataSource, short queueId, int shardCount, String instanceId,
                    ShardOwnerMetrics metrics) {
        this.metrics = requireNonNull(metrics, "No metrics provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.queueId = queueId;
        requireTrue(shardCount > 0, "shardCount must be positive");
        this.shardCount = shardCount;
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.storage = new ShardOwnedStorage(dataSource, queueId);
    }

    /**
     * Enqueue a batch as one multi-row insert to a single shard.
     * <p>
     * Unordered messages are assigned round-robin rather than by key hash, so they spread evenly and
     * never inherit another key's head-of-line blocking.
     */
    public void enqueue(List<byte[]> payloads, int payloadType) throws SQLException {
        requireNonNull(payloads, "No payloads provided");
        if (payloads.isEmpty()) {
            return;
        }
        var shard = Math.floorMod(enqueueShardCursor.getAndIncrement(), shardCount);
        var owner = localHandoffEnabled ? ownedShards.get(shard) : null;
        if (owner == null) {
            try (var connection = dataSource.getConnection()) {
                storage.enqueueBatch(connection, shard, payloads, payloadType);
            }
            return;
        }

        // Tier 2: this instance owns the target shard, so the message never has to be read back.
        // The row is stamped with the owner's fence (invisible to its own cursor read) and handed
        // over in memory once the insert has committed.
        List<Long> seqs;
        try (var connection = dataSource.getConnection()) {
            seqs = storage.enqueuePreClaimed(connection, shard, payloads, payloadType, owner.fence());
        }
        // Strictly after commit. Handing over first would deliver a message that a rollback then
        // removed — a duplicate the design cannot detect, because nothing else knows it existed.
        for (var index = 0; index < seqs.size(); index++) {
            owner.handOffLocally(new ShardOwnedStorage.Row(seqs.get(index), payloads.get(index), payloadType));
        }
    }

    /**
     * The routing space this queue's ordered keys live in. Resolved once from the registry.
     */
    private int orderedUnits() throws SQLException {
        var units = orderedUnits;
        if (units == 0) {
            units = storage.orderedUnits();
            orderedUnits = units;
        }
        return units;
    }

    /**
     * Enqueue ordered messages, routing each to the shard its key hashes to.
     * <p>
     * The routing is the whole ordering mechanism: same key, same shard, one owner. Nothing further
     * is needed at enqueue time, and nothing at all is needed at read time.
     */
    public void enqueueOrdered(List<ShardOwnedStorage.OrderedPayload> messages) throws SQLException {
        requireNonNull(messages, "No messages provided");
        var units   = orderedUnits();
        var byShard = new HashMap<Integer, List<ShardOwnedStorage.OrderedPayload>>();
        for (var message : messages) {
            byShard.computeIfAbsent(ShardOwnedSchema.unitForKey(message.key(), units), s -> new ArrayList<>())
                   .add(message);
        }
        try (var connection = dataSource.getConnection()) {
            for (var entry : byShard.entrySet()) {
                storage.enqueueOrderedBatch(connection, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Own the ordered lane's shards, enforcing per-key FIFO in memory.
     */
    public void startConsumingOrdered(OrderedPayloadHandler handler, ShardOwnerSettings settings, int maxShards) throws SQLException {
        startConsumingOrdered(handler, settings, maxShards, RedeliveryPolicy.fixed(Duration.ofMillis(50), 3));
    }

    public void startConsumingOrdered(OrderedPayloadHandler handler, ShardOwnerSettings settings, int maxShards, RedeliveryPolicy redeliveryPolicy) throws SQLException {
        configureOrdered(handler, settings, maxShards, redeliveryPolicy);
        start();
    }

    /**
     * Say what this instance will consume, without starting it. Splitting configuration from
     * {@link #start()} is what lets this class honour {@link Lifecycle}: a container needs to
     * construct a resource first and start it later, and it has nowhere to pass a handler at
     * start time.
     * <p>
     * <b>Mutually exclusive with {@link #configureUnordered}.</b> One instance serves one lane; a
     * queue with traffic on both needs two instances. Calling both used to flip {@code activeLane}
     * and leave the first handler populated but never invoked — which reads like "configure both
     * lanes" and silently consumes only the second. Re-configuring the same lane is allowed.
     */
    public ShardOwnedQueue configureOrdered(OrderedPayloadHandler handler, ShardOwnerSettings settings,
                                            int maxShards, RedeliveryPolicy redeliveryPolicy) {
        requireTrue(activeHandler == null,
                    "This queue is already configured for the unordered lane. One instance serves one "
                            + "lane — build a second ShardOwnedQueue for the ordered lane, sharing this "
                            + "instance id so the pair counts as one member of the cluster.");
        activeLane = "ordered";
        activeOrderedHandler = requireNonNull(handler, "No handler provided");
        activeSettings = requireNonNull(settings, "No settings provided");
        activePolicy = requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");
        maxShardsHeld = maxShards;
        return this;
    }

    /**
     * The unordered-lane counterpart of {@link #configureOrdered}, and mutually exclusive with it —
     * see that method for why.
     */
    public ShardOwnedQueue configureUnordered(PayloadHandler handler, ShardOwnerSettings settings,
                                              int maxShards, RedeliveryPolicy redeliveryPolicy) {
        requireTrue(activeOrderedHandler == null,
                    "This queue is already configured for the ordered lane. One instance serves one "
                            + "lane — build a second ShardOwnedQueue for the unordered lane, sharing this "
                            + "instance id so the pair counts as one member of the cluster.");
        activeLane = "unordered";
        activeHandler = requireNonNull(handler, "No handler provided");
        activeSettings = requireNonNull(settings, "No settings provided");
        activePolicy = requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");
        maxShardsHeld = maxShards;
        return this;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Idempotent, per the contract: a second call while running does nothing rather than leasing a
     * second set of owners onto the same shards.
     * <p>
     * Called while a {@link #stop()} is still running, on another thread, it waits for that stop to finish
     * ({@link #lifecycleLock}) and then starts a new run.
     */
    @Override
    public void start() {
        requireTrue(activeSettings != null, "Nothing configured — call configureUnordered or configureOrdered first");
        synchronized (lifecycleLock) {
            // Under runLock, as every write of `running` is, so a tick adopting a unit (adoptIntoRun) sees either this run or
            // none.
            synchronized (runLock) {
                if (!running.compareAndSet(false, true)) {
                    return;
                }
            }
            try {
                if ("ordered".equals(activeLane)) {
                    startOrdered();
                } else {
                    startUnordered();
                }
            } catch (SQLException e) {
                synchronized (runLock) {
                    running.set(false);
                }
                // The runtime is borrowed before the acquire loop, which is what can throw here: hand it back, or a shared
                // runtime keeps a borrower that never stops. No owner exists yet - every statement precedes the placement.
                releaseRuntime();
                throw new IllegalStateException("Failed to start consuming shards for instance " + instanceId, e);
            }
        }
    }

    @Override
    public boolean isStarted() {
        return running.get();
    }

    private void startOrdered() throws SQLException {
        // FIRST, before any other database access. The ordered lane's cursor cannot be safe on a
        // database that hides one backend's transaction id from another, and a reader of the failure
        // should see that rather than whatever unrelated statement happened to run first.
        ShardOwnedSchema.verifyWatermarkPrerequisites(dataSource);

        var handler          = activeOrderedHandler;
        var settings         = activeSettings;
        var redeliveryPolicy = activePolicy;

        // The caller's maxShards does NOT apply to this lane, and honouring it would strand messages.
        //
        // It used to mean "how many of the queue's shards will I serve", and callers passed the shard
        // count. The ordered lane's routing space is now fixed at ORDERED_UNITS and is no longer the
        // caller's number, so a caller still passing eight would hold eight of sixty-four units and
        // leave fifty-six owned by nobody — every key hashing to one of them silently undelivered.
        // How the units are split across instances is what fairShare decides; how many one instance
        // may hold is not a thing a caller can usefully know.
        maxShardsHeld = orderedUnits();
        var maxShards = maxShardsHeld;

        // BEFORE the acquire loop, and from leaseTtl rather than from holeExpiry. This lane still
        // derived its first lease as holeExpiry x 3 — the derivation that was removed when leaseTtl
        // became its own setting, fixed on the unordered path and missed here. The two are bounded by
        // unrelated things, and the consequence is not a slow failover but a dead lease: with a
        // holeExpiry of 200 ms the lane leases its shards for 600 ms, while the heartbeat that renews
        // them runs on leaseTtl / 3. Every owner is then fenced out of its own acknowledgements long
        // before the first renewal, and stops. No existing test saw it because they all either finish
        // inside 3 x holeExpiry or set holeExpiry high enough that it happens to exceed their runtime.
        leaseTtlMillis = Math.max(1_000L, settings.leaseTtlMillis());
        // BEFORE acquiring anything. A unit owned by an instance carries no expiry — its liveness is
        // this row — so an owner that has not registered yet looks DEAD to everyone else, and its
        // units are takeable the moment it acquires them. Registration used to happen on the first
        // heartbeat, ten seconds in, which left every instance stealing every other instance's units
        // for the first ten seconds of its life.
        storage.heartbeatInstance(instanceId);

        ensureRuntime(settings);
        var leased = leaseAtStart("ordered", orderedUnits(), maxShards);
        for (var entry : leased) {
            var placement = runtime.place(queueId, "ordered", entry.shard());
            var owner = new OrderedShardOwner(storage, entry.shard(), entry.grant().fence(), settings, handler, metrics,
                                              redeliveryPolicy, instanceId, placement.wakeup(),
                                              dispatch, entry.grant().previousOwnerReleased());
            owner.setDeliveryGate(this::deliveryPermitted);
            owners.add(owner);
            placement.register(owner);
            countAcquisition(owner);
        }
        // The ordered lane had no heartbeat at all: its leases were taken once and left to expire,
        // after which a second node could take the same shard while this one kept delivering.
        startHeartbeat();
        // The shards taken at start-up are ownership changes like any other. Reporting only the ones
        // rebalancing moves would leave an observer unable to tell which shards this instance serves.
        leased.forEach(entry -> metrics.observer().shardOwnershipChanged(entry.shard(), true));
        log.info("Instance {} owns ordered shards {}", instanceId, leased.stream().map(LeasedUnit::shard).toList());
    }

    public long orderedRemaining() throws SQLException {
        var remaining = 0L;
        for (var shard = 0; shard < orderedUnits(); shard++) {
            remaining += storage.countOrderedRemaining(shard);
        }
        return remaining;
    }

    /**
     * Lease every shard this instance can take and start owning them — {@code configureUnordered}
     * followed by {@code start()}.
     *
     * @param maxShards upper bound on shards to hold, so several instances in one JVM can be made to
     *                  share rather than one taking everything
     */
    public void startConsuming(PayloadHandler handler, ShardOwnerSettings settings, int maxShards) throws SQLException {
        startConsuming(handler, settings, maxShards, RedeliveryPolicy.fixed(Duration.ofMillis(50), 3));
    }

    public void startConsuming(PayloadHandler handler, ShardOwnerSettings settings, int maxShards, RedeliveryPolicy redeliveryPolicy) throws SQLException {
        configureUnordered(handler, settings, maxShards, redeliveryPolicy);
        start();
    }

    private void startUnordered() throws SQLException {
        var handler          = activeHandler;
        var settings         = activeSettings;
        var redeliveryPolicy = activePolicy;
        var maxShards        = maxShardsHeld;

        leaseTtlMillis = Math.max(1_000L, settings.leaseTtlMillis());
        // BEFORE acquiring anything. A unit owned by an instance carries no expiry — its liveness is
        // this row — so an owner that has not registered yet looks DEAD to everyone else, and its
        // units are takeable the moment it acquires them. Registration used to happen on the first
        // heartbeat, ten seconds in, which left every instance stealing every other instance's units
        // for the first ten seconds of its life.
        storage.heartbeatInstance(instanceId);

        ensureRuntime(settings);
        var leased = leaseAtStart("unordered", shardCount, maxShards);
        if (leased.isEmpty() && runtime.hasLivePump()) {
            log.warn("Instance {} leased no shards — another instance holds them all", instanceId);
        }

        for (var entry : leased) {
            var placement = runtime.place(queueId, "unordered", entry.shard());
            var owner = new ShardOwner(storage, entry.shard(), entry.grant().fence(), settings, handler, metrics,
                                       redeliveryPolicy, placement.wakeup(),
                                       instanceId, dispatch, entry.grant().previousOwnerReleased());
            owner.setDeliveryGate(this::deliveryPermitted);
            owners.add(owner);
            ownedShards.put(entry.shard(), owner);
            placement.register(owner);
            countAcquisition(owner);
        }
        startHeartbeat();
        leased.forEach(entry -> metrics.observer().shardOwnershipChanged(entry.shard(), true));
        log.info("Instance {} owns shards {}", instanceId, leased.stream().map(LeasedUnit::shard).toList());
    }

    /**
     * Lease up to {@code maxShards} of {@code lane}'s free units for a starting queue: nothing while every pump of its
     * runtime has died, as in the heartbeat's acquire loops ({@link ShardRuntime#hasLivePump()}).
     * <p>
     * Called after {@link #ensureRuntime}, which is where a borrowed runtime becomes known. A unit taken with no live pump
     * would be placed on a dead pump, ended there on arrival ({@code ShardPump.add}), reported as owned and handed back a
     * heartbeat later — and for that third of a lease no other instance could take it. Left free instead, it shows in
     * {@code QueueHealth.unownedShards}, and the dead pump's ERROR says to restart the runtime.
     */
    private List<LeasedUnit> leaseAtStart(String lane, int units, int maxShards) throws SQLException {
        var leased = new ArrayList<LeasedUnit>();
        if (!runtime.hasLivePump()) {
            log.warn("Instance {} acquires no {} unit on queue {}: every pump of its ShardRuntime has died, so nothing would "
                             + "serve them. They stay free for other instances; restart the ShardRuntime to serve them here",
                     instanceId, lane, queueId);
            return leased;
        }
        // Checked per unit too, as in rebalance: what is placed after the last live pump died is ended on arrival.
        for (var shard = 0; shard < units && leased.size() < maxShards && runtime.hasLivePump(); shard++) {
            var grant = storage.acquireLeaseGrant(lane, shard, instanceId, leaseTtlMillis);
            if (grant.isPresent()) {
                leased.add(new LeasedUnit(shard, grant.get()));
            }
        }
        return leased;
    }

    /**
     * Stop owning, without draining. Used by the crash-recovery tests, where the point is that
     * another instance picks up whatever was left.
     */
    public void stopAbruptly() {
        // One lifecycle operation at a time, as start() and stop() (lifecycleLock).
        synchronized (lifecycleLock) {
            if (heartbeat != null) {
                heartbeat.cancel(false);
                heartbeat = null;
            }
            // Abandon without acknowledging, so the takeover path is exercised for real rather than
            // handed a queue the outgoing owner had already emptied.
            flushOnExit.set(false);
            // Ends the run as stop() does, so a tick still acquiring cannot hand its owner to a later start() (runGeneration).
            synchronized (runLock) {
                running.set(false);
                runGeneration.incrementAndGet();
            }
            releaseRuntime();
        }
    }

    /**
     * Hand back what this queue borrowed. A runtime passed in by the caller is theirs to close; the
     * shared one is reference counted and closes when its last borrower stops.
     */
    private void releaseRuntime() {
        if (runtime == null) {
            return;
        }
        owners.forEach(owner -> runtime.forget(queueId, owner.lane(), owner.shard()));
        if (borrowedShared) {
            ShardRuntime.releaseShared(dataSource);
            runtime = null;
            borrowedShared = false;
        }
    }

    /**
     * Use the shared runtime if one was supplied, otherwise stand one up for this queue alone. The
     * private case exists so a single-queue caller and a test need no extra ceremony; anything with
     * more than one queue should pass a shared runtime, or pay for its threads and connections again
     * per queue.
     */
    private void ensureRuntime(ShardOwnerSettings settings) {
        if (runtime == null) {
            runtime = ShardRuntime.acquireShared(dataSource, settings);
            borrowedShared = true;
        }
        dispatch = runtime.dispatch(parallelConsumers);
    }

    /**
     * The last moment this instance successfully told the database it was alive.
     * <p>
     * Nanoseconds from {@link System#nanoTime()}, so it measures elapsed time and not wall-clock, and
     * is unaffected by the clock moving.
     */
    private volatile long lastLivenessConfirmedNanos = System.nanoTime();

    private void confirmLiveness() {
        lastLivenessConfirmedNanos = System.nanoTime();
        evaluateLiveness();
    }

    private final AtomicBoolean deliveryPaused = new AtomicBoolean();

    /**
     * Decide whether this instance may still dispatch, and say so once when the answer changes.
     * <p>
     * Called from the heartbeat as well as from the owners, and that is the important half: an
     * instance whose queue is quiet asks nothing of {@link #deliveryPermitted()} for as long as it has
     * no work, so a pause noticed only on the dispatch path would go unrecorded for exactly the
     * instances an operator is trying to find. The heartbeat runs whether there is work or not.
     */
    private boolean evaluateLiveness() {
        var staleForMillis = (System.nanoTime() - lastLivenessConfirmedNanos) / 1_000_000L;
        if (staleForMillis <= pauseAfterMillis()) {
            if (deliveryPaused.compareAndSet(true, false)) {
                log.info("Instance {} has confirmed its liveness again; delivery resumes", instanceId);
            }
            return true;
        }
        if (deliveryPaused.compareAndSet(false, true)) {
            metrics.deliveryPauses.increment();
            log.warn("Instance {} has not confirmed its liveness for {} ms, close to the {} ms the rest of the "
                             + "cluster waits before taking its units. Pausing delivery until it can: whatever it started "
                             + "from here on could be work a successor is doing too",
                     instanceId, staleForMillis, leaseTtlMillis);
        }
        return false;
    }

    /**
     * How stale this instance's own liveness may get before it stops starting work: four fifths of
     * {@code leaseTtl}, not all of it.
     * <p>
     * Pausing at the full lease was pausing at the moment a successor may already take the units, and
     * the two sides do not even measure the same thing. This instance counts from when its heartbeat
     * <em>returned</em>; everyone else compares the row's {@code heartbeat_at} - written when the
     * statement ran, earlier - with the database's {@code now()}. So the local view runs a round trip
     * late, and a message dispatched just inside "the lease" by this clock could start after a
     * successor already had the unit. The last fifth is the margin for that, and for the time between
     * a dispatch decision and the handler actually running.
     * <p>
     * Still above two thirds, so one missed heartbeat - they run every third of the lease - does not
     * pause anything; it takes two.
     */
    private long pauseAfterMillis() {
        return leaseTtlMillis * 4 / 5;
    }

    /**
     * Whether this instance may still dispatch work — that is, whether it has confirmed its own
     * liveness inside the window the rest of the cluster gives it.
     *
     * <h2>Why an owner has to ask this at all</h2>
     * A unit's liveness is its instance's row in {@code shard_queue_instance}, and the fair share
     * everyone else computes treats a row older than {@code leaseTtl} as dead. So {@code leaseTtl}
     * after this instance last managed to write that row, its units are <b>already takeable</b> and
     * may already have been taken — while nothing has happened in this JVM to say so. It still holds
     * the owners, still has the messages it read, and goes on delivering them.
     * <p>
     * Correctness survives that: the successor takes the unit under a new fence and this instance's
     * acknowledgements are refused. What does not survive is the useful work — every message it
     * dispatches in that window is one the successor is also dispatching, so it is manufacturing
     * duplicates that the contract permits but nobody wants.
     * <p>
     * Two failures put an instance here and neither is exotic. A network partition: measured, a node
     * without a {@code socketTimeout} had not noticed after ninety seconds. A slow disk: commits take
     * longer than the lease, so every instance on that database crosses this line at once, which is
     * the case a per-owner lease check cannot see because the check is itself a query that is now slow.
     * <p>
     * Pausing is not a fence and does not pretend to be one — the fence is what makes this safe. It
     * stops an instance from spending the window acting on a belief it has no way to check.
     */
    private boolean deliveryPermitted() {
        return evaluateLiveness();
    }

    /**
     * Another live process heartbeats under this instance id. Both then count as one instance in the rebalance and
     * keep taking the same shards from each other - safe, because the fence refuses the loser's writes, but the queue
     * churns. Typically an instance id configured explicitly to the same value in two processes, or derived from
     * something they share, such as the hostname. Logged at most once a minute.
     */
    private void reportSharedInstanceId() {
        metrics.sharedInstanceIdHeartbeats.increment();
        var now  = System.nanoTime();
        var last = lastSharedInstanceIdWarningNanos;
        if (last == 0 || now - last >= TimeUnit.MINUTES.toNanos(1)) {
            lastSharedInstanceIdWarningNanos = now;
            log.warn("Another process is running queue {} under the same instance id '{}' as this one. The rebalance counts "
                             + "the two as one instance, and they keep taking the same shards from each other. Set the instance "
                             + "id to a value unique per process (Spring Boot: essentials.shard-owned-queue.instance-id)",
                     queueId, instanceId);
        }
    }

    /**
     * Renew the leases this instance holds, at a third of their lifetime.
     * <p>
     * A third rather than a half so that a single missed renewal — a GC pause, a slow query, a blip
     * on the connection — does not cost the shard. An owner whose renewal is refused has been
     * superseded and stops immediately; it does not wait to find out at acknowledgement time,
     * because by then it may have dispatched work its successor is also dispatching.
     */
    private void startHeartbeat() {
        var interval = Math.max(200L, leaseTtlMillis / 3);
        heartbeat = runtime.scheduleHeartbeat(() -> {
            try {
                if (storage.heartbeat(instanceId)) {
                    reportSharedInstanceId();
                }
                confirmLiveness();
                // Garbage collection, not liveness: ten lease lifetimes is far past anything
                // fairShare looks at, so this can never remove a row that still counts.
                storage.pruneDepartedInstances(leaseTtlMillis * 10);
            } catch (Exception e) {
                log.warn("Instance heartbeat failed", e);
            }
            try {
                refreshShardCount();
            } catch (Exception e) {
                log.warn("Could not re-read the shard count for queue {}", queueId, e);
            }
            // ONE READ per lane, not one write per owned unit. There is nothing to renew: a unit's
            // liveness is its owner's instance row, which heartbeatInstance above already refreshed —
            // one row per queue rather than one per unit held. All that remains is to notice a unit
            // that was taken away and stop its owner at once, rather than let it discover that at
            // acknowledgement time with work already dispatched.
            //
            // Every owner whose lease row is still this instance's (holdsLeaseRow), one its pump's death ended included: the
            // question is whether the ROW is still held under its fence, and a dead pump's unit found taken here is no
            // longer this instance's to release (handBackUnitsOnDeadPumps then leaves it out).
            for (var lane : new String[]{"unordered", "ordered"}) {
                var held = owners.stream()
                                 .filter(LeasedOwner::holdsLeaseRow)
                                 .filter(owner -> lane.equals(owner.lane()))
                                 .toList();
                if (held.isEmpty()) {
                    continue;
                }
                try {
                    var shards = new int[held.size()];
                    for (var index = 0; index < held.size(); index++) {
                        shards[index] = held.get(index).shard();
                    }
                    var fences = storage.heldFences(lane, shards, instanceId);
                    var taken  = new ArrayList<Integer>();
                    for (var owner : held) {
                        var fence = fences.get(owner.shard());
                        if (fence != null && fence == owner.fence()) {
                            metrics.leaseRenewals.increment();
                        } else {
                            // Absent, or back under a NEW fence — the unit was taken and handed on.
                            // Both mean this owner's fence is stale.
                            metrics.leasesLost.increment();
                            owner.onLeaseEnded(LeasedOwner.LeaseEnd.TAKEN);
                            taken.add(owner.shard());
                        }
                    }
                    if (!taken.isEmpty()) {
                        // Still a warning — the fence makes this safe, but something else believed
                        // this instance was gone, and that is worth knowing. One line for the tick
                        // rather than one per unit, because a handover takes them in a batch.
                        log.warn("Instance {} lost {} {} unit(s) [{}] on queue {} to another instance; "
                                         + "their owners stop, if they had not already, and will not acknowledge under the old fence",
                                 instanceId, taken.size(), lane, summarise(taken), queueId);
                    }
                } catch (Exception e) {
                    log.warn("Lease check failed for {} shards on the {} lane", held.size(), lane, e);
                }
            }
            // BEFORE the rebalance, as the hand-back below: a unit released here is free for its acquire loop to take again.
            try {
                retryUnreleasedAcquisitions();
            } catch (Exception e) {
                log.warn("Instance {} could not release {} {} unit(s) [{}] on queue {} that a heartbeat tick leased as the queue "
                                 + "stopped; retried on the next heartbeat", instanceId, unreleasedAcquisitions.size(), activeLane,
                         summarise(unreleasedAcquisitions.keySet()), queueId, e);
            }
            // BEFORE the rebalance, so a unit handed back here is free for its acquire loop to take again on a live pump, and
            // so its removeIf, which drops owners whose lease has ended, never drops one whose lease is still to be released.
            try {
                handBackUnitsOnDeadPumps();
            } catch (Exception e) {
                log.warn("Handing back the units on a dead pump failed for queue {}; retried on the next heartbeat", queueId, e);
            }
            try {
                rebalance();
            } catch (Exception e) {
                log.warn("Rebalance failed", e);
            }
            // Last, and outside every catch above: whether this instance is still entitled to
            // dispatch does not depend on which of those steps failed, only on how long it has been
            // since one of them succeeded.
            evaluateLiveness();
        }, interval);
    }

    /**
     * Release again the lease rows an earlier heartbeat tick could not release when it undid an acquisition
     * ({@link #unreleasedAcquisitions}, {@link #abandonAcquisition}). One fenced statement for all of them; each entry is
     * dropped once it has run, whether it released the row or found it taken meanwhile - by another instance, or by this
     * one again under a newer fence - and kept for the next heartbeat when it throws. An entry added while this runs, or
     * one for the same unit under a newer fence, stays.
     */
    private void retryUnreleasedAcquisitions() throws SQLException {
        if (unreleasedAcquisitions.isEmpty()) {
            return;
        }
        var retrying = Map.copyOf(unreleasedAcquisitions);
        var released = storage.releaseFencedLeases(activeLane, retrying, instanceId);
        retrying.forEach(unreleasedAcquisitions::remove);
        if (released > 0) {
            log.info("Instance {} released {} of {} {} unit(s) [{}] on queue {} that a heartbeat tick leased as the queue stopped and "
                             + "could not release then; any not released had been taken meanwhile, by another instance or by this "
                             + "one under a newer fence",
                     instanceId, released, retrying.size(), activeLane, summarise(retrying.keySet()), queueId);
        } else {
            log.debug("Instance {} found the {} unit(s) [{}] on queue {} that a heartbeat tick could not release as the queue stopped "
                              + "taken meanwhile, by another instance or by this one under a newer fence; nothing left to release",
                      instanceId, activeLane, summarise(retrying.keySet()), queueId);
        }
    }

    /**
     * Release the units whose pump died, so another instance - or this one, on a live pump - serves them.
     * <p>
     * The pump ended these owners as it died ({@link ShardPump#run()} says why they are handed back rather than kept),
     * moving each to {@link LeasedOwner.LeaseRow#ENDED_ROW_HELD}; an ordered owner also began draining. What is left is the
     * database's half, which the dying thread does not do: the lease row still names this instance, and an instance-owned
     * lease has no expiry, so until it is released no other instance can take the unit, and this one's fair share counts it
     * as taken. Released through {@link #releaseLeaseRows}, which moves each owner to {@code RELEASED}. An unordered unit is
     * released at once - whatever is still in one of its handlers is redelivered by the next owner, as on any fair-share
     * release. An ordered unit waits until no key is in a handler (its handlers run on the shared executor, not on the
     * pump), because a successor would otherwise start that key beside it; it is retried on every heartbeat until then.
     * Nothing bounds that wait, so a handler that never returns keeps the unit leased and unserved until the runtime is
     * restarted. Each tick's wait is logged at DEBUG, and once it outlasts {@code shedGrace} a WARN names the unit's keys
     * still in a handler, once per unit ({@link OrderedShardOwner#stuckOnDeadPump()}), as an abandoned ordered shed does.
     * <p>
     * With no live pump left in the runtime this instance acquires nothing, so the units are released for other instances
     * only, and stay unowned if none runs; the WARN reporting the hand-back says which of the two it is.
     * <p>
     * No acknowledgement is flushed first, unlike {@link #stop()}: the owner's state was kept by the thread a critical
     * error just ended, and is not trusted to say what was handled. What was handled and not acknowledged is redelivered
     * (at-least-once).
     */
    private void handBackUnitsOnDeadPumps() throws SQLException {
        var shardRuntime = runtime;
        if (shardRuntime == null) {
            return;
        }
        var ready   = new ArrayList<LeasedOwner>();
        var waiting = new ArrayList<Integer>();
        for (var owner : owners) {
            // The runtime says which pump, the owner where its row stands. Normally ENDED_ROW_HELD, which the death moved it
            // to; HELD if ending it threw (ShardPump.endOnDeadPump), its row held all the same. Not RELEASED: a unit found
            // taken has no row here to release, and the rebalance's removeIf drops it and tells the runtime.
            if (!owner.holdsLeaseRow() || !shardRuntime.onDeadPump(owner)) {
                continue;
            }
            if ("ordered".equals(owner.lane()) && owner.inFlight() > 0) {
                waiting.add(owner.shard());
                if (owner instanceof OrderedShardOwner ordered) {
                    // Past shedGrace, one WARN per unit naming its keys, as an abandoned shed does: nothing bounds this
                    // wait, and the pump's ERROR cannot say which key to look at. Every tick's DEBUG below covers the rest.
                    ordered.stuckOnDeadPump().ifPresent(
                            stuck -> log.warn("Instance {} has waited {} ms to hand back ordered unit {} on queue {} from its dead pump: "
                                                      + "{} key(s) still in a handler {}. A unit is not handed back while one of its "
                                                      + "keys is in a handler, so it stays leased and unserved until the handler "
                                                      + "returns or the runtime is restarted. Logged once per unit",
                                              instanceId, stuck.waited().toMillis(), owner.shard(), queueId, stuck.keys().size(),
                                              stuck.describeKeys()));
                }
            } else {
                ready.add(owner);
            }
        }
        if (!waiting.isEmpty()) {
            log.debug("Instance {} is waiting for {} ordered unit(s) [{}] on queue {} to finish the keys in their handlers before "
                              + "handing them back from a dead pump", instanceId, waiting.size(), summarise(waiting), queueId);
        }
        if (ready.isEmpty()) {
            return;
        }
        // If it throws, nothing below has run: every owner is still in `owners` with its row held (holdsLeaseRow), and the
        // next heartbeat retries. Neither acquire loop takes the unit again meanwhile: both count it towards the fair share
        // through holdsLeaseRow, and skip it (acquireFreeOrderedShards through its held set, the unordered loop through
        // ownedShards).
        releaseLeaseRows(ready);
        var released = new ArrayList<Integer>();
        for (var owner : ready) {
            shardRuntime.handedBack(owner);
            owners.remove(owner);
            if (owner instanceof ShardOwner unordered) {
                ownedShards.remove(owner.shard(), unordered);
            }
        }
        // The observer last, after the bookkeeping for every owner: one that throws part-way leaves no owner released in
        // the database and still listed here. Only the releases this call is the first to report: a stop() running
        // meanwhile releases these owners too (countRelease).
        for (var owner : ready) {
            if (countRelease(owner)) {
                released.add(owner.shard());
            }
        }
        // Who takes them over depends on whether this instance has a live pump left: with none it acquires nothing (rebalance
        // and acquireFreeOrderedShards are guarded on hasLivePump), so promising a live pump here would be false.
        if (shardRuntime.hasLivePump()) {
            log.warn("Instance {} handed back {} {} unit(s) [{}] on queue {} whose pump had died; another instance, or a live pump "
                             + "here, takes them over under a new fence, and redelivers what they had handled but not acknowledged",
                     instanceId, ready.size(), activeLane, summarise(ready.stream().map(LeasedOwner::shard).toList()), queueId);
        } else {
            log.warn("Instance {} handed back {} {} unit(s) [{}] on queue {} whose pump had died, for other instances only: no pump "
                             + "of this instance's ShardRuntime is alive, so it acquires nothing until the runtime (or the application) "
                             + "is restarted. Another instance takes them over under a new fence and redelivers what they had handled "
                             + "but not acknowledged; with none running they stay unowned (QueueHealth.unownedShards)",
                     instanceId, ready.size(), activeLane, summarise(ready.stream().map(LeasedOwner::shard).toList()), queueId);
        }
        for (var shard : released) {
            metrics.observer().shardOwnershipChanged(shard, false);
        }
    }

    /**
     * Move shards towards an even split, with no coordinator.
     * <p>
     * Each instance independently computes the same fair share — {@code ceil(shards / liveInstances)}
     * — and holds no more than that. An instance over its share releases the excess; an instance under
     * it takes whatever is free. Because every instance computes the same number from the same
     * membership table, the split converges without anybody deciding it.
     * <p>
     * Releasing before acquiring, and only ever releasing what this instance itself holds, is what
     * keeps two instances from tugging the same shard back and forth.
     */
    /**
     * Pick up a shard count that has grown in the registry, without restarting the process.
     * <p>
     * The count used to be fixed at construction, so growing a queue meant redeploying every
     * instance — the operationally expensive half of resharding, and an artefact of where the number
     * was stored rather than anything the design required. The heartbeat already reads the database
     * every tick and already rebalances; noticing one more column is the whole change, and the
     * acquire loop in {@link #rebalance()} then takes the new shards on its own because it has always
     * iterated to {@code shardCount}.
     * <p>
     * <b>There is no two-moduli window left.</b> Instances pick the new count up independently, so for
     * up to one heartbeat interval some route by the old modulus and some by the new — and neither
     * lane cares. Unordered routing is round-robin, so every shard has an owner either way; the
     * ordered lane does not read this number at all, routing instead over the fixed space recorded on
     * the queue's registry row. An earlier revision of this javadoc said an operator must stop
     * producing ordered messages across the window. That was true when ordered routing read
     * {@code shardCount}; growth now moves no key, and there is nothing to avoid.
     * <p>
     * Only ever upward. A registry that reported a smaller count would strand the messages in the
     * shards this instance stopped looking at, so it is refused and logged rather than obeyed.
     */
    private void refreshShardCount() throws SQLException {
        var registered = storage.currentShardCount();
        if (registered.isEmpty() || registered.getAsInt() == shardCount) {
            return;
        }
        var updated = registered.getAsInt();
        if (updated < shardCount) {
            log.warn("Queue {} reports {} shards in the registry but this instance holds {}; ignoring, "
                             + "because dropping shards at runtime would strand whatever is in them",
                     queueId, updated, shardCount);
            return;
        }
        log.info("Queue {} grew from {} to {} shards; picking it up without a restart",
                 queueId, shardCount, updated);
        shardCount = updated;
    }

    /**
     * Records how many instances the lane's routing space cannot give a unit to, and says so once
     * when that starts and once when it stops.
     * <p>
     * This is the only place both numbers are known, and the condition is otherwise invisible: a
     * surplus instance holds nothing, delivers nothing and reports no error, which looks exactly like
     * an instance with a quiet queue. On the ordered lane it is the one thing that would justify
     * growing a queue's routing space — fixed for the life of the queue, and deliberately not
     * growable — so an operator who cannot see it can only guess whether the space was sized wrongly.
     * <p>
     * Logged on transition rather than per tick: the heartbeat runs every {@code leaseTtl / 3}, and a
     * deployment that is one instance over would otherwise produce a warning every few seconds for as
     * long as it stayed that way.
     */
    private void reportSurplusInstances(int liveInstances, int units) {
        var surplus  = Math.max(0, liveInstances - units);
        var previous = metrics.surplusInstances.getAndSet(surplus);
        if (surplus == previous) {
            return;
        }
        if (surplus > 0) {
            log.warn("Queue {} has {} live instance(s) against {} {} unit(s), so {} can hold nothing on that lane. "
                             + "Nothing is lost, duplicated or reordered and it recovers when the instance count drops — but "
                             + "the {} lane's space is fixed for the life of the queue, so this is the deployment that would "
                             + "need a larger one at creation",
                     queueId, liveInstances, units, activeLane, surplus, activeLane);
        } else {
            log.info("Queue {} no longer has more instances than {} units; every instance can hold something again",
                     queueId, activeLane);
        }
    }

    private void rebalance() throws SQLException {
        // Read once, as handBackUnitsOnDeadPumps does. stop() cancels the heartbeat without interrupting a tick already
        // running and then nulls the field for a borrowed shared runtime, so a tick overlapping a stop met it null here
        // and logged a NullPointerException as "Rebalance failed" during an ordinary shutdown. Every use below goes through
        // the local, so the rest of this tick cannot meet it null, whenever stop() lets it go.
        //
        // The running check spares a tick that starts after stop() the whole rebalance; on a runtime the caller owns the
        // field stays set, and the tick would otherwise lease units for a stopped queue. It is check-then-act, so it is not
        // what keeps a tick already past it from leaving a unit behind when stop() begins during the acquire loops: both
        // loops adopt each unit they lease into the run read here, the generation, which a stop ends (adoptIntoRun says
        // why that closes the race, a stop followed by a start included). Read first: a tick that reads it after a stop
        // and a start has begun in the new run, and is one of its ticks.
        var generation   = runGeneration.get();
        var shardRuntime = runtime;
        if (shardRuntime == null || !running.get()) {
            return;
        }
        var liveInstances = storage.countLiveInstances(leaseTtlMillis);
        // Per lane: the ordered lane's unit space is fixed and the unordered lane's is configurable,
        // so one fair share for both would hand this instance a quota computed against the wrong
        // number and leave units permanently unowned.
        var units     = "ordered".equals(activeLane) ? orderedUnits() : shardCount;
        var fairShare = Math.min(maxShardsHeld, (units + liveInstances - 1) / liveInstances);
        reportSurplusInstances(liveInstances, units);

        // Drop owners that have lost their lease — through fencing, or through a renewal refused
        // while this node was paused — BEFORE deciding what to acquire.
        //
        // Without this, `ownedShards` still maps every shard to a dead owner, the acquire loop skips
        // them all as already-held, and the node never takes anything again: one long stop-the-world
        // pause and it is permanently degraded until someone restarts it. It survived every
        // same-JVM test and only appeared once a process was actually frozen with SIGSTOP.
        owners.removeIf(owner -> {
            // Only RELEASED. Not one whose row is still to be released (ENDED_ROW_HELD: its pump died, and
            // handBackUnitsOnDeadPumps releases it): dropping it here would leave the lease row naming this instance for good.
            if (owner.holdsLeaseRow()) {
                return false;
            }
            if (owner instanceof ShardOwner unordered) {
                ownedShards.remove(owner.shard(), unordered);
            }
            // A unit on a dead pump found taken is dropped here, not handed back, so stop counting it in
            // shardsOnDeadPumps. A no-op for any other owner.
            shardRuntime.handedBack(owner);
            // A unit found taken is not reported as released, as before; one this queue released was reported already.
            acquisitionReported.remove(owner);
            return true;
        });

        if ("ordered".equals(activeLane)) {
            shedOrAcquireOrderedShards(fairShare, shardRuntime, generation);
            return;
        }

        // leaseHeld (HELD): only an owner that serves its unit is shed. One whose pump died (ENDED_ROW_HELD) is released by
        // handBackUnitsOnDeadPumps.
        var held = owners.stream().filter(LeasedOwner::leaseHeld).toList();
        if (held.size() > fairShare) {
            var released = new ArrayList<Integer>();
            for (var owner : held.subList(fairShare, held.size())) {
                // One unit at a time: a failed release leaves this owner serving (HELD) and the next tick sheds it again.
                // The owner may dispatch for the moment between the release and its end; a successor's acquire bumps the
                // fence, so that is at worst a redelivery, which this lane allows.
                releaseLeaseRows(List.of(owner));
                ownedShards.remove(owner.shard(), owner);
                reportReleased(owner);
                released.add(owner.shard());
                log.debug("Instance {} released unordered shard {}", instanceId, owner.shard());
            }
            if (!released.isEmpty()) {
                log.info("Instance {} released {} unordered shard(s) [{}] on queue {} — fair share is {} of {} "
                                 + "across {} instance(s); now holding {}",
                         instanceId, released.size(), summarise(released), queueId, fairShare, shardCount,
                         liveInstances, countHeld());
            }
            return;
        }

        if (!shardRuntime.hasLivePump()) {
            // Whatever is acquired now lands on a dead pump, is ended there and handed back on the next heartbeat, every
            // heartbeat. Left unowned instead, it shows in QueueHealth.unownedShards; the pump's ERROR says to restart.
            log.debug("Instance {} acquires no unordered shard on queue {}: every pump of its runtime has died", instanceId, queueId);
            return;
        }
        var acquired = new ArrayList<Integer>();
        // Bounded by the lease rows this instance holds (holdsLeaseRow), a dead pump's not yet handed back included, not by
        // countHeld(): those owners ended in memory at the death (ENDED_ROW_HELD), so while their hand-back kept failing a
        // count of served units took one free shard more per dead row, on top of the fair share. Counted once and kept up
        // to date below: a pump dying during the loop changes nothing, as an owner its death ends still holds its row.
        var rowsHeld = owners.stream().filter(LeasedOwner::holdsLeaseRow).count();
        try {
            for (var shard = 0; shard < shardCount && rowsHeld < fairShare; shard++) {
                if (!shardRuntime.hasLivePump()) {
                    // The last live pump died during this loop. What it placed from here on would be ended on arrival
                    // (ShardPump.add) and handed back next tick, every tick.
                    break;
                }
                if (ownedShards.containsKey(shard)) {
                    continue;
                }
                var grant = storage.acquireLeaseGrant("unordered", shard, instanceId, leaseTtlMillis);
                if (grant.isEmpty()) {
                    continue;
                }
                var unit                  = shard;
                var fence                 = grant.get().fence();
                var previousOwnerReleased = grant.get().previousOwnerReleased();
                var adopted = adoptIntoRun(shardRuntime, "unordered", unit, generation,
                                           wakeup -> {
                                               var owner = new ShardOwner(storage, unit, fence, activeSettings, activeHandler, metrics,
                                                                          activePolicy, wakeup,
                                                                          instanceId, dispatch, previousOwnerReleased);
                                               owner.setDeliveryGate(this::deliveryPermitted);
                                               return owner;
                                           });
                if (!adopted) {
                    abandonAcquisition(unit, fence);
                    break;
                }
                rowsHeld++;
                acquired.add(shard);
                log.debug("Instance {} acquired unordered shard {} under fence {}", instanceId, shard, fence);
            }
        } finally {
            // However the loop ended - a later shard's acquire throwing included - what it adopted is listed and counted,
            // so it is logged and reported too. The observer last, outside runLock (adoptIntoRun says why).
            if (!acquired.isEmpty()) {
                log.info("Instance {} acquired {} unordered shard(s) [{}] on queue {}; now holding {} of {}",
                         instanceId, acquired.size(), summarise(acquired), queueId, countHeld(), shardCount);
            }
            reportAcquired(acquired);
        }
    }

    /**
     * Move ordered shards towards the fair share, the same target the unordered lane uses — but
     * reached differently, because the two lanes are not allowed to give a shard up the same way.
     * <p>
     * The unordered lane drops a lease and lets the new owner redeliver whatever was in flight;
     * at-least-once permits that. Here it would hand the new owner a key this instance is still
     * running, and two concurrent messages of one key is reordering, which is the guarantee the lane
     * exists to provide. So an over-loaded instance <em>asks</em> its excess shards to drain
     * ({@link OrderedShardOwner#beginShedding()}) and releases each only once it reports quiesced.
     * That takes at least one further heartbeat tick, so ordered rebalancing converges more slowly
     * than unordered rebalancing. It is the price of the guarantee, not a defect.
     */
    private void shedOrAcquireOrderedShards(int fairShare, ShardRuntime shardRuntime, long generation) throws SQLException {
        // Release the drained ones first, so a shard this instance has finished with is free before
        // it goes on to decide whether it wants more.
        var drained = new ArrayList<Integer>();
        for (var owner : List.copyOf(owners)) {
            if (owner instanceof OrderedShardOwner ordered && ordered.shedComplete()) {
                // One unit at a time, as the unordered shed. A shed-complete owner dispatches nothing, so ending it after
                // the release costs nothing.
                releaseLeaseRows(List.of(ordered));
                // A unit that finished its shed before its pump died, and whose hand-back failed this tick, is released
                // here instead. Dropped from `owners` below, it would never be handed back, and stay counted in
                // shardsOnDeadPumps (and shardsAwaitingTakeover) until the runtime stops. A no-op on a live pump.
                shardRuntime.handedBack(ordered);
                owners.remove(ordered);
                reportReleased(ordered);
                drained.add(ordered.shard());
                log.debug("Instance {} released ordered unit {} after draining", instanceId, ordered.shard());
            }
        }
        if (!drained.isEmpty()) {
            log.info("Instance {} released {} ordered unit(s) [{}] on queue {} after draining; now holding {}",
                     instanceId, drained.size(), summarise(drained), queueId, countHeld());
        }

        // leaseHeld (HELD), as on the unordered lane: only an owner that serves its unit is asked to shed. One whose pump died
        // (ENDED_ROW_HELD) drains until no key is in its handlers and is released by handBackUnitsOnDeadPumps.
        var held = owners.stream().filter(LeasedOwner::leaseHeld).toList();
        if (held.size() > fairShare) {
            var asked = new ArrayList<Integer>();
            for (var owner : held.subList(fairShare, held.size())) {
                if (owner instanceof OrderedShardOwner ordered) {
                    // Idempotent, and re-attempted each tick: a shed abandoned because a handler
                    // outran its grace should be tried again, not given up on permanently.
                    if (ordered.beginShedding()) {
                        asked.add(ordered.shard());
                    }
                }
            }
            // Only the units that started shedding on THIS tick, or the idempotent re-attempt would
            // report the same shed every heartbeat until it completes.
            if (!asked.isEmpty()) {
                log.info("Instance {} is draining {} ordered unit(s) [{}] on queue {} before releasing them "
                                 + "— fair share is {} of {}; a key in flight is finished, never handed over mid-key",
                         instanceId, asked.size(), summarise(asked), queueId, fairShare, orderedUnits());
            }
            return;
        }
        acquireFreeOrderedShards(fairShare, shardRuntime, generation);
    }

    /**
     * Take ordered shards nobody holds — which happens when an owner dies, and now also when another
     * instance has finished draining one.
     */
    private void acquireFreeOrderedShards(int fairShare, ShardRuntime shardRuntime, long generation) throws SQLException {
        if (!shardRuntime.hasLivePump()) {
            // See the unordered lane's acquire loop in rebalance().
            log.debug("Instance {} acquires no ordered unit on queue {}: every pump of its runtime has died", instanceId, queueId);
            return;
        }
        var limit      = Math.min(fairShare, maxShardsHeld);
        // An owner on a dead pump counts as held until handBackUnitsOnDeadPumps has released its row (holdsLeaseRow).
        // acquireLeaseGrant accepts a unit this instance already holds, so one not counted here would be taken again on a
        // live pump while its dead owner may still have a key in a handler - and the dead owner's hand-back would then
        // release the new owner's row.
        var heldShards = owners.stream()
                               .filter(LeasedOwner::holdsLeaseRow)
                               .map(LeasedOwner::shard)
                               .collect(java.util.stream.Collectors.toSet());
        var acquired   = new ArrayList<Integer>();
        try {
            for (var shard = 0; shard < orderedUnits() && heldShards.size() < limit; shard++) {
                if (heldShards.contains(shard)) {
                    continue;
                }
                var grant = storage.acquireLeaseGrant("ordered", shard, instanceId, leaseTtlMillis);
                if (grant.isEmpty()) {
                    continue;
                }
                var unit                  = shard;
                var fence                 = grant.get().fence();
                var previousOwnerReleased = grant.get().previousOwnerReleased();
                var adopted = adoptIntoRun(shardRuntime, "ordered", unit, generation,
                                           wakeup -> {
                                               var owner = new OrderedShardOwner(storage, unit, fence, activeSettings, activeOrderedHandler,
                                                                                 metrics, activePolicy, instanceId, wakeup,
                                                                                 dispatch, previousOwnerReleased);
                                               owner.setDeliveryGate(this::deliveryPermitted);
                                               return owner;
                                           });
                if (!adopted) {
                    abandonAcquisition(unit, fence);
                    break;
                }
                heldShards.add(shard);
                acquired.add(shard);
                log.debug("Instance {} acquired ordered unit {} under fence {}", instanceId, shard, fence);
            }
        } finally {
            // As in rebalance()'s unordered loop: what this loop adopted is logged and reported however it ended. Not
            // orderedUnits() here: the loop's first condition resolved it, so the field holds it whenever something was adopted.
            if (!acquired.isEmpty()) {
                log.info("Instance {} acquired {} ordered unit(s) [{}] on queue {}; now holding {} of {}",
                         instanceId, acquired.size(), summarise(acquired), queueId, heldShards.size(), orderedUnits);
            }
            reportAcquired(acquired);
        }
    }

    /**
     * Adopt a unit a heartbeat tick has leased into the run the tick belongs to, if that run is still the current one:
     * {@code running} set, and {@link #runGeneration} still the {@code generation} the tick read at the top of
     * {@link #rebalance()}. Adopting places the unit on its pump ({@link ShardRuntime#place}), builds its owner with the
     * wake-up that placement chose ({@code build}), lists it, registers it on the pump and counts the acquisition
     * ({@link #countAcquisition}). Called by both acquire loops for each unit they lease; when it refuses, the loop undoes
     * the acquisition ({@link #abandonAcquisition}) and stops acquiring.
     * <p>
     * <b>Why under {@link #runLock}.</b> {@link #stop()} ends the run and reads {@code owners} under the same lock, so the
     * two cannot interleave. Adopted first, the owner is in the list the stop reads, and the stop drains, ends and releases
     * it like the others; the stop first, this refuses, and only the tick undoes it. Without the lock the stop's list was
     * read concurrently with this check, and both could see the owner: the stop released it and reported a release whose
     * acquisition was never reported (the tick had not reported it yet), and the tick released it again. A check before
     * the owner is listed, like the one at the top of {@link #rebalance()}, cannot stand in for this one: a unit leased by a
     * tick already past it when the stop began was in no list the stop read. Nothing ended it, and on a runtime that keeps
     * running after this queue stops (one the caller passed with {@code setRuntime}, even to this queue alone, or the one
     * shared per {@code DataSource} while another queue still borrows it), it went on reading and dispatching for the
     * stopped queue, its lease row naming an instance the stop had just deregistered.
     * <p>
     * <b>Placed only once adopted.</b> The placement is under the lock too, after the check, so a refused unit was never
     * placed and leaves no wake-up behind in the runtime. Placed before the check, as it was, a refused unit's wake-up had
     * to be forgotten again, and whether that was safe depended on whether the run current by then had placed the same
     * unit and so reused that wake-up: forgetting it left the unit's notifications reaching no owner, keeping it left an
     * entry nothing forgot until the runtime stopped. Placing the unit and building its owner are both in-memory work.
     * <p>
     * <b>The observer is told by the caller, outside the lock, in a {@code finally} around its loop</b>
     * ({@link #reportAcquired}). It is the caller's code (a Micrometer binding, tracing, {@code PostgresqlMessageQueue}'s
     * fan-out): told under the lock, a slow one held up {@code stop()}, {@code start()} and {@link #stopAbruptly()}, which
     * all take it, and one that threw escaped with the owner already listed and on its pump, and ended the acquire loop.
     * In a {@code finally}, because a loop can end by throwing - a later unit's acquire statement failing - after it has
     * adopted units: each of those is listed and counted, its release will be told to the observer, and so its acquisition
     * must be too. The count stays under the lock, so a stop that reads the owner counts its release. The cost: a stop that
     * takes the lock right after this adoption can tell the observer of the release before the tick has told it of the
     * acquisition, should the stop's drain and release statement finish while the tick is still running the rest of its
     * loop.
     * <p>
     * A stop right after the adoption can end the owner before its pump has run its {@link LeasedOwner#onTakeover takeover}.
     * The pump never dispatches for an ended owner and drops it at the end of that pass, but it runs the takeover first.
     * Every write the takeover makes is fenced: while the stop has not yet released the row, an unclean takeover's attempt
     * charge lands, and it is the one charge that takeover owes, since the next instance takes the released unit cleanly
     * and charges nothing; once the row is released or taken, the charge and the exhaustion pass change nothing.
     * <p>
     * <b>Why the generation, not {@code running} alone.</b> A tick can still be inside its acquire UPDATE after the queue has
     * been stopped and started again in this process, under the same instance id. {@code start()} sets {@code running}
     * again, so that tick found it set and its owner - built with the earlier run's runtime, its stale settings, and a
     * fence the new run may already have superseded - joined the new run until the next lease check dropped it. The stop
     * bumped the generation, so the tick's no longer matches and it undoes its acquisition instead.
     *
     * @param build the unit's owner, built with the wake-up its placement chose
     * @return whether the unit was adopted; when not, nothing was placed or built, and the caller releases the row
     */
    private boolean adoptIntoRun(ShardRuntime shardRuntime, String lane, int unit, long generation,
                                 Function<ShardWakeup, LeasedOwner> build) {
        synchronized (runLock) {
            if (!running.get() || runGeneration.get() != generation) {
                return false;
            }
            var placement = shardRuntime.place(queueId, lane, unit);
            var owner     = build.apply(placement.wakeup());
            owners.add(owner);
            if (owner instanceof ShardOwner unordered) {
                ownedShards.put(owner.shard(), unordered);
            }
            placement.register(owner);
            countAcquisition(owner);
            return true;
        }
    }

    /**
     * Undo an acquisition {@link #adoptIntoRun} refused, because the run the heartbeat tick belongs to has ended: release
     * the unit's lease row on {@code activeLane}. No owner was built for it and nothing placed, so nothing has dispatched
     * for it here, and releasing its row cannot hand a successor an ordered key that is still in a handler here.
     * <p>
     * The release is fenced, as {@link #releaseLeaseRows}' is: when the queue has been started again under the same
     * instance id, the new run may hold this very unit, under a newer fence, and it keeps it.
     * <p>
     * When the release fails, the unit goes to {@link #unreleasedAcquisitions}, and every heartbeat of this queue retries
     * it, the heartbeats of a run started later included. Nothing else would: no owner counts the row, so a restarted run
     * at its fair share, or at its {@code maxShards}, never took the unit back, and no other instance could, as the row
     * names a live instance.
     */
    private void abandonAcquisition(int unit, long fence) {
        try {
            storage.releaseFencedLeases(activeLane, Map.of(unit, fence), instanceId);
            log.debug("Instance {} released {} unit {} on queue {} again: the queue stopped while it was being acquired",
                      instanceId, activeLane, unit, queueId);
        } catch (SQLException e) {
            // The newest fence wins: a later lease of the unit can only have bumped it, and the older one matches no row.
            unreleasedAcquisitions.merge(unit, fence, Math::max);
            if (running.get()) {
                // Started again under the same instance id, which keeps the membership row live, so no other instance
                // takes the unit. Usually the restarted run holds it already: its start blocked on the row lock this
                // tick's acquire UPDATE held, then took the row under a newer fence (acquireLeaseGrant accepts a row
                // naming this instance), and the fenced release would not have touched that row anyway. Otherwise - its
                // start stopped short of the unit at its maxShards - nothing of the new run counts the row, and only the
                // retry frees it.
                log.warn("Instance {} leased {} unit {} on queue {} as the queue stopped, and could not release it again. The "
                                 + "queue has been started again under the same instance id: unless it has already taken the "
                                 + "unit again under a newer fence, the release is retried on every heartbeat until it goes "
                                 + "through, and until then the unit stays leased to this instance and unserved",
                         instanceId, activeLane, unit, queueId, e);
            } else {
                // The stop deregisters this instance, or, when it kept an ordered unit or could not deregister, the
                // cancelled heartbeat stops refreshing the membership row: gone, or older than the lease TTL, either way.
                log.warn("Instance {} leased {} unit {} on queue {} as the queue stopped, and could not release it again; it is "
                                 + "free to another instance once this instance's membership row is gone or older than the "
                                 + "lease TTL ({} ms). Should the queue be started again under the same instance id first, the "
                                 + "release is retried on every heartbeat until it goes through",
                         instanceId, activeLane, unit, queueId, leaseTtlMillis, e);
            }
        }
    }

    /**
     * Count an owner's acquisition, and record it as counted ({@link #acquisitionReported}). The caller tells the observer,
     * outside {@link #runLock}: at start-up once the heartbeat is scheduled, on a heartbeat tick in a {@code finally} around
     * its acquire loop ({@link #reportAcquired}).
     */
    private void countAcquisition(LeasedOwner owner) {
        acquisitionReported.add(owner);
        metrics.shardsAcquired.increment();
    }

    /**
     * A copy of the owners this queue lists for the units it leases, in list order: the list {@link #stop()} ends and
     * releases, and the heartbeat's lease check and fair share go by. Package-private: it is for asserting which owner
     * serves a unit, not a figure to operate on.
     */
    List<LeasedOwner> owners() {
        return List.copyOf(owners);
    }

    /**
     * The very monitor this queue's {@link #runLock} is: the one a heartbeat tick decides under, in {@link #adoptIntoRun},
     * whether its acquisition joins the run or is refused, and that {@link #start()}, {@link #stop()} and
     * {@link #stopAbruptly()} write {@code running} under. Package-private: it is for a test that holds it to keep every
     * tick of this queue from adopting or refusing a unit until it lets go, not for synchronising on in production code.
     */
    Object runLock() {
        return runLock;
    }

    /**
     * List an owner the caller has already put on a pump, with the bookkeeping {@link #adoptIntoRun} does for an owner a
     * heartbeat tick acquired - listed, mapped in {@code ownedShards} when it is an unordered {@link ShardOwner}, and its
     * acquisition counted ({@link #countAcquisition}) - so {@link #stop()} ends, releases and reports it like any other.
     * Package-private: it is for staging an owner whose behaviour a test controls, which a real acquisition cannot build.
     * The observer is not told of the acquisition; whatever releases the owner ({@link #stop()}, or a dead-pump hand-back,
     * {@link #handBackUnitsOnDeadPumps}) tells it of the release.
     *
     * @param owner the owner to list
     * @param first list it ahead of every other owner, so it is the first one {@link #stop()} ends, rather than last
     * @throws IllegalStateException when the queue is not running: {@link #adoptIntoRun} adopts into no stopped run either
     */
    void adoptPlacedOwner(LeasedOwner owner, boolean first) {
        synchronized (runLock) {
            if (!running.get()) {
                throw new IllegalStateException("Queue " + queueId + " is not running, so it adopts no owner");
            }
            if (first) {
                owners.addFirst(owner);
            } else {
                owners.add(owner);
            }
            if (owner instanceof ShardOwner unordered) {
                ownedShards.put(owner.shard(), unordered);
            }
            countAcquisition(owner);
        }
    }

    /**
     * Tell the observer of every unit a heartbeat tick's acquire loop adopted ({@link #adoptIntoRun}), outside
     * {@link #runLock}. Each one is told even when the observer throws for an earlier one: its acquisition is counted and its
     * release will be told, so a unit skipped here would leave the observer's released count ahead of its acquired count.
     * The first throw is rethrown once every unit has been told, the others added to it as suppressed, so the heartbeat
     * still logs the observer's failure.
     */
    private void reportAcquired(List<Integer> units) {
        RuntimeException failure = null;
        for (var unit : units) {
            try {
                metrics.observer().shardOwnershipChanged(unit, true);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Count an owner's release, if its acquisition was reported and its release is not yet: the owner leaves
     * {@link #acquisitionReported}, atomically, so of a heartbeat tick and a {@link #stop()} releasing it at once, one
     * counts it. The caller tells the observer when this returns true, directly or through {@link #reportReleased}.
     *
     * @return whether this call counted it
     */
    private boolean countRelease(LeasedOwner owner) {
        if (!acquisitionReported.remove(owner)) {
            return false;
        }
        metrics.shardsReleased.increment();
        return true;
    }

    private void reportReleased(LeasedOwner owner) {
        if (countRelease(owner)) {
            metrics.observer().shardOwnershipChanged(owner.shard(), false);
        }
    }

    /**
     * Ownership moves a unit at a time and is REPORTED a pass at a time.
     * <p>
     * Every acquisition and release used to be its own INFO line. One instance taking up an ordered
     * lane is 64 of them, a two-instance rebalance is 32 more on each side, and an application
     * consuming six queues multiplies all of it — several hundred lines for one event, repeated on
     * every rebalance that moves anything. Nobody reads a unit id out of that; what an operator wants
     * to know is that a rebalance happened, which way it went, and where this instance ended up.
     * <p>
     * So the loops collect and the caller logs once. The per-unit line survives at DEBUG, where it
     * keeps the fence — the one part that is genuinely per unit and the thing to reach for when
     * ownership is actually in question.
     *
     * @param shards the units the pass moved, in the order it moved them
     */
    static String summarise(Collection<Integer> shards) {
        var sorted = shards.stream().sorted().toList();
        var out    = new StringBuilder();
        for (var index = 0; index < sorted.size(); ) {
            var runStart = index;
            while (index + 1 < sorted.size() && sorted.get(index + 1) == sorted.get(index) + 1) {
                index++;
            }
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(sorted.get(runStart));
            if (index > runStart) {
                out.append('-').append(sorted.get(index));
            }
            index++;
        }
        return out.toString();
    }

    /**
     * Give up the lease rows of {@code releasing}: release the rows, then move each owner to
     * {@link LeasedOwner.LeaseRow#RELEASED}, which ends it if it had not ended. The one way this instance gives a row up -
     * both lanes' fair-share sheds, the dead-pump hand-back ({@link #handBackUnitsOnDeadPumps}) and {@link #stop()} - so the
     * order lives here and nowhere else. An acquisition undone by a stop ({@link #abandonAcquisition}, and its retry,
     * {@link #retryUnreleasedAcquisitions}) releases a row no owner was built for, with the same fenced statement.
     * <p>
     * The row first. Ended first and then left holding its row by a failed release, an owner read as free to the next
     * tick's {@code removeIf}, which dropped it: the row still named this instance, so no other instance took the unit,
     * and nothing here served it. Released first, a failed release throws before any owner has moved, so each is still
     * {@code HELD} (and serving, so the next tick sheds it again) or {@code ENDED_ROW_HELD} (still counted towards the fair
     * share, and released again on the next heartbeat), and nothing drops it.
     * <p>
     * One statement for all of them, on {@code activeLane}: every owner is on it ({@link #releaseHeldLeases} says why).
     * Bounded by this instance id and by each owner's fence, so a unit something else has taken meanwhile is left alone,
     * and so is one this instance id has taken again under a newer fence: a run of this queue started again after a stop,
     * while a tick of the earlier run undoes the acquisition it made of the same unit ({@link #abandonAcquisition}). The
     * instance id alone released the newer owner's row while it still served the unit.
     */
    private void releaseLeaseRows(List<? extends LeasedOwner> releasing) throws SQLException {
        if (releasing.isEmpty()) {
            return;
        }
        var fenceByShard = new HashMap<Integer, Long>();
        releasing.forEach(owner -> fenceByShard.put(owner.shard(), owner.fence()));
        storage.releaseFencedLeases(activeLane, fenceByShard, instanceId);
        for (var owner : releasing) {
            owner.onLeaseEnded(LeasedOwner.LeaseEnd.RELEASED);
        }
    }

    /**
     * Owners that serve their unit ({@link LeasedOwner#leaseHeld()}, {@code HELD}). Not the lease rows this instance holds:
     * see {@link #shardsHeld()}.
     */
    private long countHeld() {
        return owners.stream().filter(LeasedOwner::leaseHeld).count();
    }

    /**
     * The units this instance serves on its configured lane: owners whose lease is held in memory
     * ({@link LeasedOwner.LeaseRow#HELD}).
     * <p>
     * Not every lease row that names this instance. A unit whose pump died keeps its row until the queue's heartbeat hands
     * it back, and until then it is counted in {@link ShardOwnerMetrics#shardsOnDeadPumps}, not here: on either lane its
     * owner ended at the death ({@code ENDED_ROW_HELD}), and the dead pump serves it no more.
     */
    public int shardsHeld() {
        return (int) countHeld();
    }

    /**
     * Wake-up hints the process received — a property of the shared listener, not of this queue.
     */
    public long notificationsReceived() {
        var shardRuntime = runtime;
        return shardRuntime == null ? 0L : shardRuntime.notificationsReceived();
    }

    /**
     * LISTEN establishments — one at startup, plus one per recovery from a lost connection.
     */
    public long listenerReconnects() {
        var shardRuntime = runtime;
        return shardRuntime == null ? 0L : shardRuntime.listenerReconnects();
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Idempotent, and leaves this instance able to {@link #start()} again: the owners, wake-ups and
     * shard map are cleared, so a restart leases afresh rather than resurrecting owners whose fences
     * are long stale.
     * <p>
     * A {@link #start()} called on another thread while this runs waits for it to finish ({@link #lifecycleLock}), so
     * what this stop drops is only ever the run it ended.
     * <p>
     * The other way round too: called while a {@code start()} is still running on another thread, this waits for that
     * start to finish, its database work included, before it begins. That wait is not part of the 2 s
     * {@link #SHUTDOWN_BUDGET}. Against a database the pool cannot connect to, it lasts until the start gives up waiting
     * for a connection, at the pool's connection timeout (30 s at Hikari's default). Against a network partition that
     * catches the start's statement on an already pooled connection, it lasts until the driver's {@code socketTimeout}
     * ends that statement, and without one indefinitely.
     */
    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            stopRun();
        }
    }

    private void stopRun() {
        // The run ends - `running` cleared, the generation bumped - and `owners` is read in one step under runLock, which
        // the heartbeat's acquire loops adopt an owner under (adoptIntoRun): a unit a tick leases while this stop runs is
        // either in the list read here, or refused and undone by the tick itself (abandonAcquisition). Never both. So
        // `ending` is every owner this run will ever have listed: no tick adopts into it after this, and no start() runs
        // beside this stop (lifecycleLock).
        //
        // Snapshot before releaseRuntime clears anything: the leases still have to be handed back
        // afterwards, and the owner list is what says which ones this instance holds - every owner
        // whose row is still this instance's (holdsLeaseRow), the dead pumps' included: an owner its
        // pump ended as it died (ENDED_ROW_HELD) still holds its row until it is handed back
        // (handBackUnitsOnDeadPumps), which a stop before the next heartbeat does here.
        List<LeasedOwner> ending;
        List<LeasedOwner> held;
        synchronized (runLock) {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            runGeneration.incrementAndGet();
            ending = List.copyOf(owners);
            held = ending.stream().filter(LeasedOwner::holdsLeaseRow).toList();
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        // `serving` is read now, before ending the owners below moves every one of them to ENDED_ROW_HELD: only
        // those still serve, and only their acknowledgements are flushed below. Not an owner on a dead pump, whatever its
        // state: its pump ended it as it died (ENDED_ROW_HELD), or left it HELD when ending it threw (ShardPump.endOnDeadPump),
        // and either way its state was kept by the thread a critical error ended. Asked before releaseRuntime: a
        // last borrower stops the shared runtime there, and a stopped runtime's pumps forget what they counted.
        var shardRuntime = runtime;
        var serving      = held.stream()
                               .filter(LeasedOwner::leaseHeld)
                               .filter(owner -> shardRuntime == null || !shardRuntime.onDeadPump(owner))
                               .toList();
        // Drain BEFORE releaseRuntime: the pumps are what flush a finishing handler's acknowledgement,
        // and a runtime this queue created stops them. Without a drain, whatever was in a handler at
        // stop lost its acknowledgement and was redelivered by the successor - and on the ordered lane
        // the successor could start a key while this instance was still running it.
        var stillInHandler = drain(held);
        // Hand the borrowed threads and connections back. A shared runtime keeps running for the
        // other queues; one this queue created is closed with it — and closing it is what flushes
        // the pumps' outstanding acknowledgements, so it has to happen before the leases go.
        releaseRuntime();
        // End the owners, or a shared runtime goes on serving them. Its pumps outlive this queue - in
        // Spring they run until the context destroys the runtime bean, after every graceful-shutdown
        // phase - and cancelling the heartbeat above did nothing to stop them: they kept reading and
        // dispatching for a queue that had been stopped, until the liveness gate noticed the missing
        // heartbeat a lease TTL later and every owner of every stopped queue logged that it was
        // pausing. An ended owner is skipped and dropped by its pump on the next pass. Ended keeping
        // its row (ENDED_ROW_HELD) until releaseHeldLeases below has released it: a heartbeat tick
        // still running counts it as this instance's, so its acquire loop cannot take the unit again.
        held.forEach(this::endOnStop);
        // An ordered unit with a key still in a handler is NOT handed back: a successor taking it now
        // could start that key while it runs here, which is reordering. It is kept until this
        // instance's liveness lapses - a lease TTL - which is also why the instance must stay
        // registered. An unordered one is released regardless; at-least-once covers its redelivery.
        var kept = stillInHandler.stream().filter(owner -> "ordered".equals(owner.lane())).toList();
        var toRelease = held.stream().filter(owner -> !kept.contains(owner)).toList();
        withinShutdownBudget(() -> {
            // What the pumps would have flushed had they gone on serving these owners. Valid still:
            // releasing a lease does not bump its fence, and the leases go only after this. Nothing to
            // do for a runtime this queue closed itself - its pumps flushed on the way out. Not for an
            // owner whose pump died: its state is not trusted (handBackUnitsOnDeadPumps).
            flushAcknowledgements(serving);
            releaseHeldLeases(toRelease);
            if (!kept.isEmpty()) {
                log.warn("Instance {} is keeping {} ordered unit(s) [{}] on queue {}: a key was still in a handler "
                                 + "{} after stop began. They become available to another instance when this one's "
                                 + "liveness lapses ({} ms), so no successor starts a key that may still be running here. "
                                 + "Raise shedGrace if handlers routinely outlast it",
                         instanceId, kept.size(), summarise(kept.stream().map(LeasedOwner::shard).toList()), queueId,
                         Duration.ofNanos(activeSettings.shedGraceNanos()), leaseTtlMillis);
                return;
            }
            // Stop counting towards everyone else's fair share. Releasing the leases without this
            // frees the shards and simultaneously forbids any survivor from taking them, for the
            // whole staleness window — which makes a graceful scale-in worse for the cluster than a
            // crash.
            try {
                storage.deregisterInstance(instanceId);
            } catch (SQLException e) {
                log.warn("Instance {} could not deregister on stop; it will age out of the membership "
                                 + "table instead, and survivors will be held at a stale fair share until it does",
                         instanceId, e);
            }
        });
        // This queue drops every owner the ended run listed, released, left to lapse, or found taken (RELEASED, so not in
        // `held`): a shared runtime keeps running, and would otherwise count those on a dead pump in shardsOnDeadPumps until
        // it stops. Every owner, not only `held`: a dead pump's owner the lease check found taken is uncounted only by the
        // rebalance's removeIf, which a stop or a failed rebalance can come before. A no-op for an owner no dead pump counts,
        // or one a heartbeat tick already handed back.
        if (shardRuntime != null) {
            ending.forEach(shardRuntime::handedBack);
        }
        // Only what this run listed, not clear(): the lists are this queue's, not this run's. No later run can have
        // listed anything yet (lifecycleLock), so this empties them all the same.
        owners.removeAll(ending);
        ownedShards.values().removeAll(ending);
        // A release not reported by now is not reported: the unit was kept, its release failed, or the budget ran out.
        // Every entry no listed owner has, which includes an owner found taken and dropped by a rebalance.
        acquisitionReported.retainAll(owners);
    }

    /**
     * End one of the owners {@link #stop()} is giving up, keeping its lease row ({@link LeasedOwner#endKeepingLeaseRow}),
     * and go on when that throws: one owner's failure stays its own, as in the pumps' per-owner guards. An owner on a dead
     * pump may be {@code HELD} still because ending it threw there ({@code ShardPump.endOnDeadPump}), and it can throw here
     * again; escaping, that skipped everything after it - the other owners went on being served by a shared runtime, and no
     * acknowledgement was flushed, no lease released and the instance not deregistered.
     * <p>
     * Where it threw decides the owner's state. Both owners move the state to {@code ENDED_ROW_HELD} first and only then
     * log and signal the wake-up, so a throw from those leaves the owner ended, and its pump drops it as usual. Only a
     * throw before the state moves leaves the owner {@code HELD}.
     * <p>
     * Either way the owner stays in the stop's {@code held}, so its lease row is released with the others. For an owner
     * still {@code HELD}, what becomes of it is up to that row. When the stop releases it ({@link #releaseLeaseRows}), the
     * owner moves to {@code RELEASED}, which ends it, and its pump drops it. When the row is not released - an ordered
     * unit kept because a key was still in its handler, or a release that fails or runs out of the shutdown budget - the
     * owner stays {@code HELD} on its pump: on a shared runtime's,
     * as a runtime this queue created has stopped its pumps already ({@link #releaseRuntime}). Nothing in this queue
     * moves it on: the lease check that would find the unit taken runs on the heartbeat this stop cancelled. It
     * dispatches nothing once the liveness gate ({@link #deliveryPermitted}) pauses delivery, four fifths of a lease TTL
     * after the last heartbeat, and it moves to {@code RELEASED} if one of its own fenced writes is refused after a
     * successor has taken the unit.
     */
    private void endOnStop(LeasedOwner owner) {
        try {
            owner.endKeepingLeaseRow();
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Instance {} could not end its owner of {} unit {} on queue {} as the queue stopped; the stop goes on. "
                             + "The owner may still be serving: if so, it ends when the stop releases the unit's lease row, and "
                             + "if the unit is kept or its release fails, it stays on its pump, with delivery paused once this "
                             + "instance's liveness lapses",
                     instanceId, owner.lane(), owner.shard(), queueId, e);
        }
    }

    /**
     * How long the courtesies at the end of {@link #stop()} may take before they are abandoned.
     * <p>
     * Everything inside the budget is best-effort by design — an unreleased lease expires and an
     * undeleted membership row ages out, so the cluster heals either way and the only cost of giving
     * up is the successor's TTL. What is NOT acceptable is waiting indefinitely to find that out,
     * and that is what an unreachable database produces: the work is one or two statements, but a
     * connection pool with nothing to connect to answers each request only when its
     * {@code connectionTimeout} expires — 30s at Hikari's default, per queue, per lane, and an
     * application consuming several queues therefore looked like it had hung on Ctrl-C.
     * <p>
     * A constant rather than a setting: it is not a tuning knob but the answer to "how long is a
     * graceful shutdown allowed to spend on things that do not have to happen", and exposing it
     * would widen a published contract for a number nobody should need to choose.
     */
    private static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(2);

    /**
     * Run the stop path's database work with a deadline, on a thread that can be abandoned.
     * <p>
     * Abandoning is safe precisely because the work is best-effort: whatever it had not done when the
     * budget ran out is something the cluster already recovers from on its own. The thread is a
     * daemon, so an attempt still blocked inside the pool cannot hold the JVM up either.
     */
    private void withinShutdownBudget(Runnable work) {
        var thread = new Thread(work, "shard-queue-stop-" + queueId);
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(SHUTDOWN_BUDGET.toMillis());
            if (thread.isAlive()) {
                log.warn("Instance {} gave up handing back queue {} after {} — the database did not "
                                 + "answer. Leases expire and the membership row ages out on their own; the "
                                 + "successor pays its lease TTL rather than taking over at once",
                         instanceId, queueId, SHUTDOWN_BUDGET);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stop the owners dispatching and wait - up to {@code shedGrace} - for what is already in a handler.
     * <p>
     * The wait is on the pumps: they go on serving draining owners, which read and dispatch nothing but
     * still flush the acknowledgement of every handler that finishes. The same grace the ordered lane
     * gives a rebalancing shed, for the same reason - it bounds how long a key may still be running
     * here - and on the stop path it also bounds how long shutdown waits for a slow handler.
     *
     * @return the owners with something still in a handler when the grace ran out
     */
    private List<LeasedOwner> drain(List<LeasedOwner> held) {
        if (held.isEmpty() || runtime == null || !runtime.isStarted()) {
            return List.of();
        }
        held.forEach(LeasedOwner::beginDraining);
        var deadline = System.nanoTime() + activeSettings.shedGraceNanos();
        while (true) {
            var busy = held.stream().filter(owner -> owner.inFlight() > 0).toList();
            if (busy.isEmpty() || System.nanoTime() >= deadline) {
                return busy;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return held.stream().filter(owner -> owner.inFlight() > 0).toList();
            }
        }
    }

    /**
     * Flush the acknowledgements the given owners are still holding, on a connection of this queue's
     * own. Best effort, like everything on the stop path: an acknowledgement that does not make it is
     * redelivered by the successor, which at-least-once permits.
     */
    private void flushAcknowledgements(List<LeasedOwner> ended) {
        if (ended.isEmpty()) {
            return;
        }
        try (var connection = storage.connection()) {
            for (var owner : ended) {
                try {
                    owner.flushOnStop(connection);
                } catch (SQLException | RuntimeException e) {
                    log.debug("Instance {} could not flush acknowledgements for {} unit {} on stop; they will be "
                                      + "redelivered", instanceId, owner.lane(), owner.shard(), e);
                }
            }
        } catch (SQLException e) {
            log.debug("Instance {} could not get a connection to flush acknowledgements on stop; they will be "
                              + "redelivered", instanceId, e);
        }
    }

    /**
     * Hand every shard back on the way out.
     * <p>
     * Without this a graceful stop left the leases to expire on their own, so every shard this
     * instance owned stayed unserved for the remainder of its TTL — thirty seconds at the defaults,
     * on every rolling restart, while {@code Subscription}'s own javadoc promised the opposite.
     * <p>
     * Releasing expires the lease without bumping the fence, so this instance's in-flight
     * acknowledgements stay valid until a successor takes the shard and bumps it — which is why the
     * release is safe to do after the pumps have flushed rather than before.
     */
    private void releaseHeldLeases(List<LeasedOwner> stillHeld) {
        if (stillHeld.isEmpty()) {
            return;
        }
        // One statement over one connection for the whole lane. Per-shard releases asked the pool for
        // a connection 68 times to perform one logical operation, which is how a stop against an
        // absent database turned into 68 consecutive connectionTimeouts — see
        // ShardOwnedStorage.releaseFencedLeases (via releaseLeaseRows).
        // Every owner is on activeLane: one instance serves one lane (configureOrdered and configureUnordered refuse
        // each other), and both the start and rebalance only acquire on it. Letting an instance hold both lanes would
        // need this split per lane, or the other lane's leases would be left to expire on stop.
        var lane = activeLane;
        try {
            releaseLeaseRows(stillHeld);
            // Only those whose release nobody has reported: a heartbeat tick still running may have shed or handed back
            // some of them meanwhile, and reported those itself.
            stillHeld.forEach(this::reportReleased);
        } catch (SQLException e) {
            // Best effort. A lease that cannot be released still expires on its own, so this
            // costs the successor its TTL rather than correctness — and a database that is
            // unreachable during shutdown is not a reason to fail the shutdown.
            log.warn("Instance {} could not release its {} shards on stop; they will expire instead",
                     instanceId, lane, e);
        }
    }

    public long deadLetterCount() throws SQLException {
        return storage.countDeadLetters();
    }

    public List<ShardOwnedStorage.DeadLetter> deadLetters() throws SQLException {
        return storage.deadLetters();
    }

    public ShardOwnerMetrics metrics() {
        return metrics;
    }

    public ShardOwnedStorage storage() {
        return storage;
    }

    /**
     * The interned id this queue addresses.
     */
    public short queueId() {
        return queueId;
    }

    public int shardCount() {
        return shardCount;
    }

    /**
     * Messages still in this queue's configured lane, across every unit it routes over — zero once
     * everything has been acknowledged.
     * <p>
     * <b>Per lane, because a queue is configured for one.</b> This used to iterate {@code shardCount}
     * and count the unordered table whichever lane was configured, so on an ordered queue it returned
     * zero unconditionally: wrong bound and wrong table. A drain assertion written against it would
     * have passed before anything was delivered. No test did, but only because the ordered tests
     * happened to assert on metrics instead.
     */
    public long remaining() throws SQLException {
        var remaining = 0L;
        if ("ordered".equals(activeLane)) {
            for (var unit = 0; unit < orderedUnits(); unit++) {
                remaining += storage.countOrderedRemaining(unit);
            }
        } else {
            for (var shard = 0; shard < shardCount; shard++) {
                remaining += storage.countRemaining(shard);
            }
        }
        return remaining;
    }

    /**
     * A unit leased at start-up, with how its previous owner left it
     */
    private record LeasedUnit(int shard, ShardOwnedStorage.LeaseGrant grant) {
    }
}
