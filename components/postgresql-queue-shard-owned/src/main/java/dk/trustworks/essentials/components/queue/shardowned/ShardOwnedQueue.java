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

import org.slf4j.*;

import dk.trustworks.essentials.shared.Lifecycle;

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
 */
public final class ShardOwnedQueue implements Lifecycle, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedQueue.class);

    private final ShardOwnedStorage storage;
    private final DataSource     dataSource;
    private final short          queueId;
    /**
     * Mutable, and read on the enqueue path as well as by the heartbeat, because a queue picks up a
     * grown shard count at runtime rather than needing the process restarted. See
     * {@link #refreshShardCount()}.
     */
    private volatile int         shardCount;
    private final String         instanceId;

    private final AtomicBoolean       running = new AtomicBoolean();
    private final AtomicBoolean       flushOnExit = new AtomicBoolean(true);
    private final AtomicInteger       enqueueShardCursor = new AtomicInteger();
    private final List<LeasedOwner>   owners = new CopyOnWriteArrayList<>();
    /** Shards this instance owns, to their owner — the lookup that makes local hand-off possible. */
    private final Map<Integer, ShardOwner> ownedShards = new ConcurrentHashMap<>();
    private String activeLane = "unordered";
    private final ShardOwnerMetrics   metrics;
    /**
     * The threads and connections this queue borrows. Shared with every other queue in the process,
     * which is what stops database contact scaling with the number of queues.
     */
    private ShardRuntime              runtime;
    /** True when this queue borrowed the shared runtime and must hand it back on stop. */
    private boolean                   borrowedShared;
    /** This queue's slot on the shared heartbeat scheduler. */
    private ScheduledFuture<?> heartbeat;
    private long                            leaseTtlMillis;
    private PayloadHandler                  activeHandler;
    private OrderedPayloadHandler           activeOrderedHandler;
    private ShardOwnerSettings              activeSettings;
    private RedeliveryPolicy                activePolicy;
    private int                             maxShardsHeld;
    /**
     * Tier 2 can be turned off so its effect can be measured rather than assumed. It is a latency
     * mechanism, and the cost decomposition showed it is not free: the pre-claim stamp widens the
     * row and local hand-offs wake the owner more often.
     */
    private volatile boolean localHandoffEnabled = true;
    /** How many handler invocations this consumer may have in flight. See {@code ConsumerOptions}. */
    private int              parallelConsumers = 8;
    private HandlerDispatch  dispatch;

    /** Set before {@link #start()}. Per consumer, not per process — see {@code ConsumerOptions}. */
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

    public ShardOwnedQueue(DataSource dataSource, short queueId, int shardCount, String instanceId) {
        this(dataSource, queueId, shardCount, instanceId, new ShardOwnerMetrics());
    }

    /** Join a runtime shared with the other queues in this process. Set before {@link #start()}. */
    ShardOwnedQueue useRuntime(ShardRuntime runtime) {
        this.runtime = requireNonNull(runtime, "No runtime provided");
        this.borrowedShared = false;
        return this;
    }

    /**
     * Join a runtime shared with the other queues in this process. The form to use when there is more
     * than one queue: connections and threads then belong to the process rather than to each queue.
     *
     * @deprecated since 0.51.0 — use {@link #builder()} and
     *             {@link ShardOwnedQueueBuilder#setRuntime(ShardRuntime)}, which names its arguments
     *             rather than relying on the order of a {@code short}, an {@code int} and a
     *             {@code String}.
     */
    @Deprecated(forRemoval = true, since = "0.51.0")
    public ShardOwnedQueue(DataSource dataSource, short queueId, int shardCount, String instanceId,
                           ShardRuntime runtime) {
        this(dataSource, queueId, shardCount, instanceId, new ShardOwnerMetrics());
        useRuntime(runtime);
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
     * Enqueue ordered messages, routing each to the shard its key hashes to.
     * <p>
     * The routing is the whole ordering mechanism: same key, same shard, one owner. Nothing further
     * is needed at enqueue time, and nothing at all is needed at read time.
     */
    public void enqueueOrdered(List<ShardOwnedStorage.OrderedPayload> messages) throws SQLException {
        requireNonNull(messages, "No messages provided");
        var byShard = new HashMap<Integer, List<ShardOwnedStorage.OrderedPayload>>();
        for (var message : messages) {
            byShard.computeIfAbsent(ShardOwnedSchema.unitForKey(message.key()), s -> new ArrayList<>())
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
     */
    public ShardOwnedQueue configureOrdered(OrderedPayloadHandler handler, ShardOwnerSettings settings,
                                         int maxShards, RedeliveryPolicy redeliveryPolicy) {
        activeLane = "ordered";
        activeOrderedHandler = requireNonNull(handler, "No handler provided");
        activeSettings = requireNonNull(settings, "No settings provided");
        activePolicy = requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");
        maxShardsHeld = maxShards;
        return this;
    }

    public ShardOwnedQueue configureUnordered(PayloadHandler handler, ShardOwnerSettings settings,
                                           int maxShards, RedeliveryPolicy redeliveryPolicy) {
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
     */
    @Override
    public void start() {
        requireTrue(activeSettings != null, "Nothing configured — call configureUnordered or configureOrdered first");
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if ("ordered".equals(activeLane)) {
                startOrdered();
            } else {
                startUnordered();
            }
        } catch (SQLException e) {
            running.set(false);
            throw new IllegalStateException("Failed to start consuming shards for instance " + instanceId, e);
        }
    }

    @Override
    public boolean isStarted() {
        return running.get();
    }

    private void startOrdered() throws SQLException {
        var handler = activeOrderedHandler;
        var settings = activeSettings;
        var redeliveryPolicy = activePolicy;

        // The caller's maxShards does NOT apply to this lane, and honouring it would strand messages.
        //
        // It used to mean "how many of the queue's shards will I serve", and callers passed the shard
        // count. The ordered lane's routing space is now fixed at ORDERED_UNITS and is no longer the
        // caller's number, so a caller still passing eight would hold eight of sixty-four units and
        // leave fifty-six owned by nobody — every key hashing to one of them silently undelivered.
        // How the units are split across instances is what fairShare decides; how many one instance
        // may hold is not a thing a caller can usefully know.
        maxShardsHeld = ShardOwnedSchema.ORDERED_UNITS;
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

        var leased = new ArrayList<int[]>();
        for (var shard = 0; shard < ShardOwnedSchema.ORDERED_UNITS && leased.size() < maxShards; shard++) {
            var fence = storage.acquireLease("ordered", shard, instanceId, leaseTtlMillis);
            if (fence.isPresent()) {
                leased.add(new int[]{shard, fence.get().intValue()});
            }
        }

        ensureRuntime(settings);
        for (var entry : leased) {
            var owner = new OrderedShardOwner(storage, entry[0], entry[1], settings, handler, metrics,
                                              redeliveryPolicy, instanceId,
                                              runtime.wakeupFor(queueId, "ordered", entry[0]),
                                              dispatch);
            owners.add(owner);
            runtime.register(queueId, "ordered", entry[0], owner);
        }
        // The ordered lane had no heartbeat at all: its leases were taken once and left to expire,
        // after which a second node could take the same shard while this one kept delivering.
        startHeartbeat();
        // The shards taken at start-up are ownership changes like any other. Reporting only the ones
        // rebalancing moves would leave an observer unable to tell which shards this instance serves.
        leased.forEach(entry -> metrics.observer().shardOwnershipChanged(entry[0], true));
        log.info("Instance {} owns ordered shards {}", instanceId, leased.stream().map(e -> e[0]).toList());
    }

    public long orderedRemaining() throws SQLException {
        var remaining = 0L;
        for (var shard = 0; shard < ShardOwnedSchema.ORDERED_UNITS; shard++) {
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
        var handler = activeHandler;
        var settings = activeSettings;
        var redeliveryPolicy = activePolicy;
        var maxShards = maxShardsHeld;

        var leased = new ArrayList<int[]>();
        leaseTtlMillis = Math.max(1_000L, settings.leaseTtlMillis());
        for (var shard = 0; shard < shardCount && leased.size() < maxShards; shard++) {
            var fence = storage.acquireLease("unordered", shard, instanceId, leaseTtlMillis);
            if (fence.isPresent()) {
                leased.add(new int[]{shard, fence.get().intValue()});
            }
        }
        if (leased.isEmpty()) {
            log.warn("Instance {} leased no shards — another instance holds them all", instanceId);
        }

        ensureRuntime(settings);

        for (var entry : leased) {
            var owner = new ShardOwner(storage, entry[0], entry[1], settings, handler, metrics,
                                       redeliveryPolicy, runtime.wakeupFor(queueId, "unordered", entry[0]),
                                       instanceId, dispatch);
            owners.add(owner);
            ownedShards.put(entry[0], owner);
            runtime.register(queueId, "unordered", entry[0], owner);
        }
        startHeartbeat();
        leased.forEach(entry -> metrics.observer().shardOwnershipChanged(entry[0], true));
        log.info("Instance {} owns shards {}", instanceId, leased.stream().map(e -> e[0]).toList());
    }

    /**
     * Stop owning, without draining. Used by the crash-recovery tests, where the point is that
     * another instance picks up whatever was left.
     */
    public void stopAbruptly() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        // Abandon without acknowledging, so the takeover path is exercised for real rather than
        // handed a queue the outgoing owner had already emptied.
        flushOnExit.set(false);
        running.set(false);
        releaseRuntime();
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
                storage.heartbeatInstance(instanceId);
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
            for (var owner : owners) {
                if (!owner.leaseHeld()) {
                    continue;
                }
                try {
                    var renewed = storage.acquireLease(owner.lane(), owner.shard(), instanceId, leaseTtlMillis);
                    if (renewed.isPresent() && renewed.get() == owner.fence()) {
                        metrics.leaseRenewals.increment();
                    } else {
                        // Either refused, or granted under a NEW fence — which means the shard was
                        // taken and handed back. Both mean this owner's fence is stale.
                        metrics.leasesLost.increment();
                        owner.onLeaseLost();
                    }
                } catch (Exception e) {
                    log.warn("Lease renewal failed for shard {}", owner.shard(), e);
                }
            }
            try {
                rebalance();
            } catch (Exception e) {
                log.warn("Rebalance failed", e);
            }
        }, interval);
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
     * <b>What this does not remove is the two-moduli window.</b> Instances pick the new count up
     * independently, so for up to one heartbeat interval some are routing keys by the old modulus and
     * some by the new. For the unordered lane that is harmless — routing is round-robin, every shard
     * has an owner either way. For the ordered lane it is the same hazard that makes growth require
     * an empty lane in the first place, so the operator must still not be producing ordered messages
     * across the window. The window is now seconds rather than a deployment.
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

    private void rebalance() throws SQLException {
        var liveInstances = storage.countLiveInstances(leaseTtlMillis);
        // Per lane: the ordered lane's unit space is fixed and the unordered lane's is configurable,
        // so one fair share for both would hand this instance a quota computed against the wrong
        // number and leave units permanently unowned.
        var units = "ordered".equals(activeLane) ? ShardOwnedSchema.ORDERED_UNITS : shardCount;
        var fairShare = Math.min(maxShardsHeld, (units + liveInstances - 1) / liveInstances);

        // Drop owners that have lost their lease — through fencing, or through a renewal refused
        // while this node was paused — BEFORE deciding what to acquire.
        //
        // Without this, `ownedShards` still maps every shard to a dead owner, the acquire loop skips
        // them all as already-held, and the node never takes anything again: one long stop-the-world
        // pause and it is permanently degraded until someone restarts it. It survived every
        // same-JVM test and only appeared once a process was actually frozen with SIGSTOP.
        owners.removeIf(owner -> {
            if (owner.leaseHeld()) {
                return false;
            }
            if (owner instanceof ShardOwner unordered) {
                ownedShards.remove(owner.shard(), unordered);
            }
            return true;
        });

        if ("ordered".equals(activeLane)) {
            shedOrAcquireOrderedShards(fairShare);
            return;
        }

        var held = owners.stream().filter(LeasedOwner::leaseHeld).toList();
        if (held.size() > fairShare) {
            for (var owner : held.subList(fairShare, held.size())) {
                owner.onLeaseLost();
                storage.releaseLease("unordered", owner.shard(), instanceId);
                ownedShards.remove(owner.shard(), owner);
                metrics.shardsReleased.increment();
                metrics.observer().shardOwnershipChanged(owner.shard(), false);
                log.info("Instance {} released shard {} — fair share is {} of {} shards across {} instances",
                         instanceId, owner.shard(), fairShare, shardCount, liveInstances);
            }
            return;
        }

        for (var shard = 0; shard < shardCount && countHeld() < fairShare; shard++) {
            if (ownedShards.containsKey(shard)) {
                continue;
            }
            var fence = storage.acquireLease("unordered", shard, instanceId, leaseTtlMillis);
            if (fence.isEmpty()) {
                continue;
            }
            var owner = new ShardOwner(storage, shard, fence.get(), activeSettings, activeHandler, metrics,
                                       activePolicy, runtime.wakeupFor(queueId, "unordered", shard),
                                       instanceId, dispatch);
            owners.add(owner);
            ownedShards.put(shard, owner);
            runtime.register(queueId, "unordered", shard, owner);
            metrics.shardsAcquired.increment();
            metrics.observer().shardOwnershipChanged(shard, true);
            log.info("Instance {} acquired shard {} under fence {}", instanceId, shard, fence.get());
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
    private void shedOrAcquireOrderedShards(int fairShare) throws SQLException {
        // Release the drained ones first, so a shard this instance has finished with is free before
        // it goes on to decide whether it wants more.
        for (var owner : List.copyOf(owners)) {
            if (owner instanceof OrderedShardOwner ordered && ordered.shedComplete()) {
                ordered.onLeaseLost();
                storage.releaseLease("ordered", ordered.shard(), instanceId);
                owners.remove(ordered);
                metrics.shardsReleased.increment();
                metrics.observer().shardOwnershipChanged(ordered.shard(), false);
                log.info("Instance {} released ordered shard {} after draining", instanceId, ordered.shard());
            }
        }

        var held = owners.stream().filter(LeasedOwner::leaseHeld).toList();
        if (held.size() > fairShare) {
            for (var owner : held.subList(fairShare, held.size())) {
                if (owner instanceof OrderedShardOwner ordered) {
                    // Idempotent, and re-attempted each tick: a shed abandoned because a handler
                    // outran its grace should be tried again, not given up on permanently.
                    ordered.beginShedding();
                }
            }
            return;
        }
        acquireFreeOrderedShards(fairShare);
    }

    /**
     * Take ordered shards nobody holds — which happens when an owner dies, and now also when another
     * instance has finished draining one.
     */
    private void acquireFreeOrderedShards(int fairShare) throws SQLException {
        var limit = Math.min(fairShare, maxShardsHeld);
        var heldShards = owners.stream().filter(LeasedOwner::leaseHeld).map(LeasedOwner::shard).collect(java.util.stream.Collectors.toSet());
        for (var shard = 0; shard < ShardOwnedSchema.ORDERED_UNITS && heldShards.size() < limit; shard++) {
            if (heldShards.contains(shard)) {
                continue;
            }
            var fence = storage.acquireLease("ordered", shard, instanceId, leaseTtlMillis);
            if (fence.isEmpty()) {
                continue;
            }
            var owner = new OrderedShardOwner(storage, shard, fence.get(), activeSettings, activeOrderedHandler,
                                              metrics, activePolicy, instanceId,
                                              runtime.wakeupFor(queueId, "ordered", shard),
                                              dispatch);
            owners.add(owner);
            heldShards.add(shard);
            runtime.register(queueId, "ordered", shard, owner);
            metrics.shardsAcquired.increment();
            metrics.observer().shardOwnershipChanged(shard, true);
            log.info("Instance {} acquired ordered shard {} under fence {}", instanceId, shard, fence.get());
        }
    }

    private long countHeld() {
        return owners.stream().filter(LeasedOwner::leaseHeld).count();
    }

    public int shardsHeld() {
        return (int) countHeld();
    }

    /** Wake-up hints the process received — a property of the shared listener, not of this queue. */
    public long notificationsReceived() {
        return runtime == null ? 0L : runtime.notificationsReceived();
    }

    /** LISTEN establishments — one at startup, plus one per recovery from a lost connection. */
    public long listenerReconnects() {
        return runtime == null ? 0L : runtime.listenerReconnects();
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
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        // Snapshot before releaseRuntime clears anything: the leases still have to be handed back
        // afterwards, and the owner list is what says which ones this instance holds.
        var held = List.copyOf(owners);
        // Hand the borrowed threads and connections back. A shared runtime keeps running for the
        // other queues; one this queue created is closed with it — and closing it is what flushes
        // the pumps' outstanding acknowledgements, so it has to happen before the leases go.
        releaseRuntime();
        releaseHeldLeases(held);
        // Stop counting towards everyone else's fair share. Releasing the leases without this frees
        // the shards and simultaneously forbids any survivor from taking them, for the whole
        // staleness window — which makes a graceful scale-in worse for the cluster than a crash.
        try {
            storage.deregisterInstance(instanceId);
        } catch (SQLException e) {
            log.warn("Instance {} could not deregister on stop; it will age out of the membership "
                     + "table instead, and survivors will be held at a stale fair share until it does",
                     instanceId, e);
        }
        owners.clear();
        ownedShards.clear();
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
    private void releaseHeldLeases(List<LeasedOwner> held) {
        for (var owner : held) {
            if (!owner.leaseHeld()) {
                continue;
            }
            try {
                storage.releaseLease(owner.lane(), owner.shard(), instanceId);
                metrics.shardsReleased.increment();
                metrics.observer().shardOwnershipChanged(owner.shard(), false);
            } catch (SQLException e) {
                // Best effort. A lease that cannot be released still expires on its own, so this
                // costs the successor its TTL rather than correctness — and a database that is
                // unreachable during shutdown is not a reason to fail the shutdown.
                log.warn("Instance {} could not release {} shard {} on stop; it will expire instead",
                         instanceId, owner.lane(), owner.shard(), e);
            }
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

    /** The interned id this queue addresses. */
    public short queueId() {
        return queueId;
    }

    public int shardCount() {
        return shardCount;
    }

    /**
     * Messages still in the table across every shard — zero once everything has been acknowledged.
     */
    public long remaining() throws SQLException {
        var remaining = 0L;
        for (var shard = 0; shard < shardCount; shard++) {
            remaining += storage.countRemaining(shard);
        }
        return remaining;
    }
}
