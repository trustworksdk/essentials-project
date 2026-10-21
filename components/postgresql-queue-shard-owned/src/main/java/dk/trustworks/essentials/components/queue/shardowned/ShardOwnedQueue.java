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

import dk.trustworks.essentials.components.queue.shardowned.spi.KeptUnitRelease;
import dk.trustworks.essentials.shared.*;
import dk.trustworks.essentials.shared.concurrent.Locks;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
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

    /**
     * The run of this queue that is current, or {@code null} while it is not started. {@link #start()} installs a fresh
     * {@link Run}; {@link #stop()} and {@link #stopAbruptly()} take it out again, and only they do - they and a
     * {@code start()} that fails, which takes out its own. Whatever a run owns -
     * its owners, its unordered shard map, the acquisitions it has counted and its heartbeat - lives on its {@code Run}, so
     * code working for one run reaches that run's state and no other's, and a run's identity is what tells it apart from
     * any run before or after it under the same instance id ({@link Run} states the protocol).
     */
    private final    AtomicReference<Run>     currentRun          = new AtomicReference<>();
    /**
     * Held across the whole of {@link #start()}, and of a {@link #stop()} or {@link #stopAbruptly()} that gets it, so
     * those run one at a time: a {@code start()} called while a stop is still draining and releasing waits for it to
     * finish. That wait is bounded by the stop: its drain by {@code shedGrace}, its database work by
     * {@link #SHUTDOWN_BUDGET}. A {@code start()} called while an earlier start still holds it waits for that start,
     * which nothing bounds (below); it logs one WARN once it has waited {@link #SHUTDOWN_BUDGET}, and goes on waiting
     * ({@link #lockForStart}).
     * <p>
     * The other way round, a stop waits only so long for a {@code start()} still doing its database work - registering
     * the instance and leasing its units, which nothing in this queue bounds: a statement caught by a network partition on
     * a pooled connection blocks until the driver's {@code socketTimeout}, and without one indefinitely. A
     * {@code stop()} or {@link #close()} waits up to {@link #SHUTDOWN_BUDGET} for this lock, {@code stopAbruptly()} not at
     * all. One that gets no further takes the starting run out of {@link #currentRun} without the lock, under the run's
     * monitor, and records on the run the stop it owes ({@link Run#phase}); {@link #isStarted()} is false from then on.
     * The start, still holding this lock, adopts nothing into the ended run, schedules it no heartbeat, and does the
     * stop's work itself before it lets go ({@link #endRunOfStart}). A stop that finds the start already past its database
     * work ({@link Phase#STARTED}) waits for the lock after all: all that start has left is telling the
     * {@code QueueObserver} of its acquisitions. So does one that finds a run no start is running for any more
     * ({@link Phase#START_ABORTED}): handed over, the stop's work would be done by nobody.
     * <p>
     * A run's own lists need no such guard - a stop works on the {@link Run} it took out, and a start fills the one it
     * installed - but the queue-wide state a stop ends with does: {@link #releaseRuntime} hands back the runtime a start
     * has just borrowed, and the stop's deregistration would remove the membership row a start has just written. That is
     * why a stop that gave up waiting leaves both to the start. Not a run's monitor: a heartbeat tick takes that one
     * ({@link #adoptIntoRun}), and would then wait behind a stop's drain.
     */
    private final    ReentrantLock            lifecycleLock       = new ReentrantLock();
    /**
     * Whether the holder of {@link #lifecycleLock} is a {@link #start()} working on the run it installed, rather than a
     * stop. Written by that start under the lock; read without it, only to say in a WARN what a later start is waiting
     * for ({@link #lockForStart}).
     */
    private volatile boolean                  startHoldsLifecycleLock;
    /**
     * Whether this queue has been retired ({@link #retire()}): from then on {@link #start()} starts nothing. Set once, never
     * cleared; read by a start under {@link #lifecycleLock}, and without it by {@link #isRetired()} - which the volatile
     * write makes safe - for {@link PostgresqlMessageQueue} to skip a retired lane before starting it.
     */
    private volatile boolean                  retired;
    /**
     * Lease rows a heartbeat tick leased as its run ended and then could not release again ({@link #abandonAcquisition}),
     * and those a start leased before its lease loop failed and could not release then ({@link #leaseAtStart}): unit to
     * the fence it leased the unit under. No owner of this queue's counts such a row - none was built for it - so
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
    private final    AtomicInteger            enqueueShardCursor  = new AtomicInteger();
    private          String                   activeLane          = "unordered";
    private final    ShardOwnerMetrics        metrics;
    /**
     * The threads and connections this queue borrows. Shared with every other queue in the process,
     * which is what stops database contact scaling with the number of queues.
     * <p>
     * Volatile, and read once into a local by whoever is not the thread that writes it: {@link #stop()} nulls it
     * ({@link #releaseRuntime}) for a borrowed shared runtime, and cancelling the heartbeat does not stop a tick already
     * running, so the heartbeat thread may meet it null part-way through a tick.
     */
    private volatile ShardRuntime             runtime;
    /**
     * True when this queue borrowed the shared runtime and must hand it back on stop.
     */
    private          boolean                  borrowedShared;
    /**
     * The ordered owners ended runs of this queue kept, because a key was still in their handler {@code shedGrace} after
     * the stop began, and that may not have been handed back yet: the owners the last run to end kept, and those an
     * earlier run kept whose lease row still named this instance when it ended. Each one's lease row names this instance
     * id, under the fence of the run that kept it, until the owner is handed back ({@link #handBackKeptUnit}). That waits
     * until no key of the owner is in a handler, flushes its acknowledgements under that fence - still valid, as releasing
     * does not bump it - and releases its row, so whoever takes the unit next finds it released: it charges no attempt
     * and delivers again nothing the handler acknowledged. It runs as the last key leaves its handler, whether or not the
     * queue runs by then ({@link OrderedShardOwner#whenNoKeyInHandler}), and is retried by every heartbeat tick, by a
     * start, by the stop of a later run, and by {@link PostgresqlMessageQueue} before it lets a {@code consume()} register
     * under the instance id ({@link #handBackKeptUnits()}), until it has released the row. Until then no run of this queue
     * acquires the unit ({@link #keptUnitsNotHandedBack()}), and the acquire loops count it towards their limit as a row
     * this instance holds: {@link ShardOwnedStorage#acquireLeaseGrant} accepts a row naming its own owner, so a restart would
     * take the unit at once, start that key beside the handler while it runs, and after it treat the unit as taken over
     * from an owner that never let go of it. That wait is bounded by {@code stuckHandlerTimeout} ({@link ShardOwnerSettings}):
     * once the key has been in its handler longer - or the unit has been kept that long with the stop that kept it still
     * flushing it - nothing waits for the unit any more ({@link #reviewStuckKeptUnits()}): with no key of it in a handler
     * it is handed back in that pass; with one, a run of this queue - the one running then, or the next - takes it over
     * under a new fence, once ({@link #keptUnitsGivenUp()}), the key's message delivered again beside the handler. Replaced by {@link #endRun} alone, with the owners not handed back then - those given up on included, while
     * their handler runs on - and those the run it ends keeps.
     * <p>
     * A queue-wide field a run writes, which the rule that everything a run owns lives on its {@link Run} otherwise
     * forbids. Exempt because it is not state of a run in progress but what the runs that ended left behind: written
     * once per run, as {@link #endRun} ends it under {@link #lifecycleLock}, never by a heartbeat tick, and read by the
     * acquire loops of the run after it. A heartbeat tick, a start, a key leaving its handler and a {@code consume()} of
     * the {@link PostgresqlMessageQueue} hand its owners back,
     * which moves each owner's own lease row state ({@link LeasedOwner#holdsLeaseRow()}) and its unit's phase
     * ({@link KeptUnit.Phase}), never this list. Read
     * after a {@code stop()} has returned, which does not always mean after the run has ended: a stop that gave up waiting
     * for a start still running returns before that start ends the run ({@link #endRunOfStart}) and writes this, so a read
     * then does not see what that run keeps.
     */
    private volatile List<KeptUnit>           keptOrderedOwners   = List.of();
    /**
     * The ordered owners whose unit this queue released with a key still in a handler, because that handler had run longer
     * than {@code stuckHandlerTimeout}: by a shed ({@link #shedOrAcquireOrderedShards}) or by the hand-back from a dead
     * pump ({@link #handBackUnitsOnDeadPumps}). Kept only so {@link #keptUnitsInHandler()} counts them while that handler
     * runs on, as it counts a kept unit given up on. An owner with no key left in a handler is dropped
     * ({@link #pruneReleasedPastStuckHandler()}) by every heartbeat tick, every stop ({@link #endRun}), every add and every
     * read of that gauge: nothing in production may read the gauge, and an owner kept here keeps what it had buffered.
     * <p>
     * A queue-wide field a heartbeat tick writes, which the rule that everything a run owns lives on its {@link Run}
     * otherwise forbids. Exempt because it holds no state of a run: its owners have left their run and released their
     * lease rows, nothing decides anything from it, and a stuck handler runs on whichever run of this queue is current,
     * or none - so a stop drops only the owners whose handlers have returned, and keeps the others.
     */
    private final    Set<LeasedOwner>         releasedPastStuckHandler = ConcurrentHashMap.newKeySet();
    /**
     * What this queue adds to {@link ShardOwnerMetrics#keptUnitsInHandler()}, one object for the life of the queue so the
     * metrics can drop it and take it back ({@link #countKeptUnitsInHandlerInMetrics()}).
     */
    private final    ShardOwnerMetrics.KeptUnitsInHandlerSource keptUnitsInHandlerSource = new ShardOwnerMetrics.KeptUnitsInHandlerSource() {
        @Override
        public int keptUnitsInHandler() {
            return ShardOwnedQueue.this.keptUnitsInHandler();
        }

        @Override
        public boolean isStarted() {
            return ShardOwnedQueue.this.isStarted();
        }
    };
    /**
     * How many hand-backs of an ended run - {@link #handBackWithinBudget} on its budget thread
     * ({@link #withinShutdownBudget}) - are still running ({@link #handBackStillRunning()}): counted up by {@link #endRun}
     * before it starts the thread, and down by that thread once its work has returned or thrown. Not by the stop, which
     * stops waiting for the thread after {@link #SHUTDOWN_BUDGET}: the abandoned thread goes on, blocked in the pool or
     * on a lock, and may still release units and deregister the instance id long after the stop has returned.
     */
    private final    AtomicInteger            handBacksRunning = new AtomicInteger();
    /**
     * How many of this queue's own hand-backs ({@link #handBackWithinBudget}) are deciding whether to delete this
     * instance's membership row, or deleting it: counted up before a hand-back reads {@link #runNeedsMembership} for that
     * decision, and down once it has left the row or its deletion has returned or thrown, when
     * {@link #deregistrationFinished} is notified. {@link #start()} writes {@code runNeedsMembership} before it reads this,
     * so of a start and a hand-back at once, either the hand-back reads the start's need and leaves the row, or the start
     * sees the deletion under way and waits for it, up to {@link #SHUTDOWN_BUDGET}, before it registers the instance
     * ({@link #awaitDeregistrationsRunning}); a deletion still running then is not waited for further. The other lane of
     * the subscription ({@link #membershipSharedWith}) counts its hand-backs' deletions of the row on its own counter, not
     * on this one.
     */
    private final    AtomicInteger            deregistrationsRunning = new AtomicInteger();
    /**
     * Notified each time {@link #deregistrationsRunning} is counted down, which is done holding this monitor, for
     * {@link #awaitDeregistrationsRunning}.
     */
    private final    Object                   deregistrationFinished = new Object();
    /**
     * The other lane of the {@link PostgresqlMessageQueue} subscription this queue is a lane of, which shares its instance
     * id and so its membership row ({@link #shareMembershipWith}); {@code null} for a queue built on its own, whose stop
     * deletes the row whenever it keeps no ordered unit and the queue has not started again by the time its hand-back
     * gets there.
     */
    private volatile ShardOwnedQueue          membershipSharedWith;
    /**
     * Whether a run of this queue still needs the instance's membership row ({@link #needsMembership()}). Set by
     * {@link #start()} as it installs a run; set by {@link #endRun} to whether an ordered unit is kept - by the run it
     * ends, or by an earlier one whose key is still in its handler, whose stop's budget thread has not flushed it yet
     * or whose hand-back is running ({@link #keptOrderedOwners}, {@link KeptUnit.Phase#STOP_FLUSHING},
     * {@link KeptUnit.Phase#HANDING_BACK}) - once it has decided
     * that, after draining. A run a stop has taken out still needs the row until then - while its owners drain,
     * and while a start still running ends it for a stop that gave up waiting ({@link #endRunOfStart}). An abrupt stop
     * leaves it set, as it leaves the units leased: it stands in for a crash, whose membership row ages out. A snapshot as
     * of those writes: a hand-back of a kept unit claimed after {@code endRun} wrote it is not in it, and
     * {@link #needsMembership()} reads that from the kept units live. Read through {@code needsMembership()} by the stop
     * of the other lane of the subscription, and by the hand-back of an earlier stop of this queue, which may finish
     * after a later start has set it ({@link #handBackWithinBudget}).
     * <p>
     * A queue-wide field a run writes, exempt from the rule against those for the reason {@link #keptOrderedOwners}
     * is: written under {@link #lifecycleLock} as a run starts and as it ends, never by a heartbeat tick, and at most one
     * run is installed or being ended at a time.
     */
    private volatile boolean                  runNeedsMembership;
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
     * What a test does to each adoption ({@link #adoptIntoRun}); {@link AdoptionHook#NONE} unless a test passed one to the
     * builder ({@link ShardOwnedQueueBuilder#setAdoptionHook}). Fixed at construction, so nothing can swap it while the
     * queue runs.
     */
    private final    AdoptionHook             adoptionHook;

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
        this(dataSource, queueId, shardCount, instanceId, metrics, AdoptionHook.NONE);
    }

    /**
     * @param metrics      the engine's observability wiring — its own counters, and the consumer-supplied
     *                     {@link dk.trustworks.essentials.components.queue.shardowned.spi.QueueObserver}
     * @param adoptionHook what a test does to each adoption ({@link AdoptionHook}); {@link AdoptionHook#NONE} outside tests
     */
    ShardOwnedQueue(DataSource dataSource, short queueId, int shardCount, String instanceId,
                    ShardOwnerMetrics metrics, AdoptionHook adoptionHook) {
        this.adoptionHook = requireNonNull(adoptionHook, "No adoptionHook provided");
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
        // Only an owner of the run current now: once a stop has taken its run out, its owners are draining and pick up
        // no hand-off, so the row goes in like any other.
        var run   = localHandoffEnabled ? currentRun.get() : null;
        var owner = run != null ? run.ownedShards.get(shard) : null;
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
     * ({@link #lifecycleLock}) and then starts a new run. Called while an earlier start is still doing its database work -
     * the case after a stop that gave up waiting for that start and returned, below - it waits for that start, which
     * does the stop's work before it returns. Nothing in this queue bounds that wait: a statement of the earlier start
     * that hangs ends at the driver's {@code socketTimeout}, and without one never. It is not silent: once it has waited
     * {@link #SHUTDOWN_BUDGET}, this logs one WARN naming the queue and the instance, and goes on waiting.
     * <p>
     * A start that fails with anything but a critical error ({@link Exceptions#isCriticalError}) leaves the queue not
     * started, so a later {@code start()} runs again: it releases the units it had leased, ends any owner it had built,
     * and hands back the runtime it borrowed. A {@link SQLException} escapes wrapped in an {@link IllegalStateException},
     * anything else as it was thrown - the ordered lane's refusal to start on a database that hides
     * {@code pg_stat_activity.backend_xid}, for one. A critical error escapes untouched, with nothing undone, and the
     * queue stays started - a later {@code start()} doing nothing - until a {@link #stop()}, which ends its run as it ends a
     * started queue's, on an interrupted thread too ({@link Phase#START_ABORTED}). The
     * instance's membership row, written before the lease loop, is left to age out rather than deleted: the two lanes of
     * a {@code PostgresqlMessageQueue} subscription share one instance id, and deleting the row would drop the other
     * lane's membership too, until its next heartbeat.
     * <p>
     * A stop that gave up waiting for this start ({@link #lifecycleLock}) has taken its run out already. This start then
     * leases nothing more, adopts nothing, schedules no heartbeat, and does that stop's work itself before it returns
     * ({@link #endRunOfStart}). It returns normally, with the queue not started: a failure of that work - a
     * {@code QueueObserver} throwing for the ownership changes it tells - is the stop's, whose caller has returned, and is
     * logged at WARN rather than thrown from here. A critical error is the exception, and escapes.
     * <p>
     * On a queue {@link #retire() retired}, this starts no run: a queue not started stays so, and one still running until
     * the {@link #stop()} that follows {@code retire()} is left as it is.
     * <p>
     * The hand-back to the database of an earlier {@link #stop()} may still be running, on a thread that stop gave up
     * waiting for after {@link #SHUTDOWN_BUDGET} ({@link #handBackStillRunning()}). Finishing after this start has
     * registered the instance, it leaves the membership row to this run; only a deletion of the row it had already
     * decided on when this start began is still under way, and this start waits for that before it registers, up to
     * {@link #SHUTDOWN_BUDGET}; if it has not finished by then, this logs one WARN and goes on
     * ({@link #awaitDeregistrationsRunning}).
     */
    @Override
    public void start() {
        requireTrue(activeSettings != null, "Nothing configured — call configureUnordered or configureOrdered first");
        lockForStart();
        try {
            // Read under the lock: a retire() just before a stop() that then waits for this lock, or hands its work to this
            // start, is seen here, or this start began before it and that stop ends what it starts.
            if (retired) {
                return;
            }
            // A fresh run, with nothing of any earlier run's in it. No lock beyond lifecycleLock: a heartbeat tick of an
            // earlier run compares the current run with its own, which this never is, so installing it changes nothing
            // for that tick (adoptIntoRun).
            var run = new Run();
            if (!currentRun.compareAndSet(null, run)) {
                return;
            }
            // After the run is installed, so the metrics, dropping this queue's source while it was stopped and counted
            // nothing, see it started once it is out, or find it added back here (ShardOwnerMetrics.dropIfFinished).
            countKeptUnitsInHandlerInMetrics();
            // Before this start registers the instance: the hand-back of an earlier stop of this queue, or a stop of the
            // other lane of the subscription, reading it from then on leaves the membership row to this run
            // (handBackWithinBudget). A deletion by this queue's own hand-back that read it before is waited for, up to
            // SHUTDOWN_BUDGET, below, and not further if it is still running then; the other lane's is not waited for
            // (awaitDeregistrationsRunning).
            runNeedsMembership = true;
            startHoldsLifecycleLock = true;
            try {
                awaitDeregistrationsRunning();
                startRun(run);
            } finally {
                startHoldsLifecycleLock = false;
                // Still STARTING only when the start escaped with a critical error before it scheduled the heartbeat, which
                // leaves the run installed: from here no start is running for it, and a stop must not hand it its work
                // (stopWithoutWaiting). Every other way out has moved the run on already.
                synchronized (run) {
                    if (run.phase() == Phase.STARTING) {
                        run.advanceTo(Phase.START_ABORTED);
                    }
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Take {@link #lifecycleLock} for {@link #start()}, waiting as long as it takes - as a plain {@code lock()} would,
     * the thread's interrupt included - but not silently: once it has waited {@link #SHUTDOWN_BUDGET}, it logs one WARN
     * saying what holds the lock, and goes on waiting. A stop holding it is bounded, by its drain's {@code shedGrace} and
     * its database work's budget. An earlier start holding it is not: its database work blocks as long as a statement
     * does, and after a stop that gave up waiting for it, it does that stop's work before it lets go
     * ({@link #endRunOfStart}).
     */
    private void lockForStart() {
        Locks.lockWarningOnce(lifecycleLock, SHUTDOWN_BUDGET, () -> {
            if (startHoldsLifecycleLock) {
                log.warn("Instance {} has waited {} and is still waiting to start queue {}: an earlier start of the queue is "
                                 + "still running - registering the instance and leasing its units, and, if a stop gave up "
                                 + "waiting for it, doing that stop's work once its statements return. Nothing in this queue "
                                 + "bounds that wait: a statement that hangs ends at the driver's socketTimeout, and without one "
                                 + "never",
                         instanceId, SHUTDOWN_BUDGET, queueId);
            } else {
                log.warn("Instance {} has waited {} and is still waiting to start queue {}: a stop of the queue is still "
                                 + "draining its handlers and handing its units back",
                         instanceId, SHUTDOWN_BUDGET, queueId);
            }
            return true;
        });
    }

    /**
     * Wait, up to {@link #SHUTDOWN_BUDGET}, for every deletion of this instance's membership row that this queue's own
     * hand-backs have decided on and not finished ({@link #deregistrationsRunning}), so that it lands before
     * {@link #start()} registers the instance rather than after. Called by {@code start()} once it has written its run's
     * need for the row ({@link #runNeedsMembership}): a hand-back reading that need from then on leaves the row alone
     * ({@link #handBackWithinBudget}), so only one that read it before is waited for. A deletion by the other lane of the
     * subscription already under way is not waited for here: that lane's own start registers the row again, or waits for
     * it. Past the budget this logs one WARN and returns, as the deletion may hang as long as a statement does: landing
     * late, it removes the row this start registers, and other instances read this instance's units as ownerless until
     * its next heartbeat writes the row again. An interrupt does not end the wait; it is set on the thread again once this
     * returns.
     */
    private void awaitDeregistrationsRunning() {
        if (deregistrationsRunning.get() == 0) {
            return;
        }
        var deadline    = System.nanoTime() + SHUTDOWN_BUDGET.toNanos();
        var interrupted = false;
        try {
            synchronized (deregistrationFinished) {
                while (deregistrationsRunning.get() > 0) {
                    var left = deadline - System.nanoTime();
                    if (left <= 0) {
                        log.warn("Instance {} has waited {} for an earlier stop of queue {} to finish deleting the instance's "
                                         + "membership row, and starts without it: should that deletion land after this start has "
                                         + "registered the instance, other instances read this instance's units as ownerless until its "
                                         + "next heartbeat writes the row again (one every {} ms)",
                                 instanceId, SHUTDOWN_BUDGET, queueId, heartbeatIntervalMillis());
                        return;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(deregistrationFinished, left);
                    } catch (InterruptedException e) {
                        // Waited on regardless, towards the same deadline; the flag is set again on the way out.
                        interrupted = true;
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
     * {@link #start()}'s work on the run it has just installed, under {@link #lifecycleLock}.
     */
    private void startRun(Run run) {
        List<Integer> adopted;
        StopKind      owed;
        try {
            adopted = "ordered".equals(activeLane) ? startOrdered(run) : startUnordered(run);
            owed = startHeartbeatUnlessStopped(run);
        } catch (SQLException e) {
            // A critical error (Exceptions.isCriticalError, which looks at the causes too) escapes untouched, with no
            // undo attempted: the JVM is then in no state to run one, and a failure of the undo must not stand in for it.
            Exceptions.rethrowIfCriticalError(e);
            throw undoFailedStart(run, new IllegalStateException("Failed to start consuming shards for instance " + instanceId, e));
        } catch (RuntimeException e) {
            Exceptions.rethrowIfCriticalError(e);
            throw undoFailedStart(run, e);
        } catch (Error e) {
            Exceptions.rethrowIfCriticalError(e);
            throw undoFailedStart(run, e);
        }
        if (owed != null) {
            doOwedStop(run, owed);
            return;
        }
        // The shards taken at start-up are ownership changes like any other. Reporting only the ones rebalancing moves
        // would leave an observer unable to tell which shards this instance serves. After the heartbeat is scheduled,
        // so an observer that throws escapes the start with the run started: every unit it is told of is served.
        reportAcquired(adopted);
        log.info("Instance {} owns {}shards {}", instanceId, "ordered".equals(activeLane) ? "ordered " : "", adopted);
    }

    /**
     * Do the work of the stop that took this start's run out without waiting for it ({@link #endRunOfStart}). That stop
     * has returned already, and the start itself did nothing wrong, so what the work throws - a {@code QueueObserver}
     * throwing for an ownership change it is told, or anything else ending the run throws - is logged rather than thrown
     * from {@link #start()}. An observer that throws leaves nothing undone: it is told of every unit even so, the run is
     * ended whatever it throws for the acquisitions ({@link #endRunOfStart}), and the releases are told last
     * ({@link #endRun}). A critical error ({@link Exceptions#isCriticalError}) escapes, as everywhere in this module.
     */
    private void doOwedStop(Run run, StopKind owed) {
        try {
            endRunOfStart(run, owed);
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Instance {} failed while its start of queue {} did the work of a {} that had stopped the queue without "
                             + "waiting for that start. That stop has returned, so this is logged rather than thrown from start(), "
                             + "which leaves the queue not started",
                     instanceId, queueId, owed == StopKind.ABRUPT ? "stopAbruptly()" : "stop()", e);
        }
    }

    /**
     * Schedule the heartbeat of the run a start has just filled, unless a stop that gave up waiting for the start has
     * taken the run out meanwhile. Decided under the run's monitor, the one that stop takes the run out under
     * ({@link #stopWithoutWaiting}): either the heartbeat is scheduled and the stop finds the run {@link Phase#STARTED},
     * or the stop took the run out first ({@code STOP_OWED_*}) and no heartbeat is ever scheduled for it. One scheduled
     * for an ended run would have no stop left to cancel it, and each of its ticks would keep the stopped instance live in
     * the membership table.
     *
     * @return the stop the start owes, which took its run out ({@link Run#owedStop}); {@code null} when the run is current
     * and its heartbeat scheduled
     */
    private StopKind startHeartbeatUnlessStopped(Run run) {
        synchronized (run) {
            // While this start holds lifecycleLock, only a stop without it moves the run on from STARTING, to the stop owed.
            if (run.phase() != Phase.STARTING) {
                return run.owedStop();
            }
            // The ordered lane had no heartbeat at all: its leases were taken once and left to expire, after which a
            // second node could take the same shard while this one kept delivering.
            startHeartbeat(run);
            run.advanceTo(Phase.STARTED);
            return null;
        }
    }

    /**
     * Undo a start that failed: take its run out, if no stop has, and end it ({@link #endRunOfStart}) - without
     * deregistering, unless a stop that gave up waiting for the start is owed. Whatever the undo itself throws is added to
     * {@code failure} as suppressed, so the start's own failure is what escapes - but for a critical error
     * ({@link Exceptions#isCriticalError}), which escapes instead, with {@code failure} added to it as suppressed.
     * {@code failure} itself is never a critical error: {@link #start()} rethrows one before it undoes anything.
     *
     * @return {@code failure}, for the caller to throw
     */
    private <T extends Throwable> T undoFailedStart(Run run, T failure) {
        StopKind owed;
        // Under the run's monitor, as every take-out is (Run).
        synchronized (run) {
            currentRun.compareAndSet(run, null);
            owed = run.owedStop();
            run.advanceTo(Phase.ENDED);
        }
        try {
            endRunOfStart(run, owed);
        } catch (RuntimeException | Error undo) {
            if (Exceptions.isCriticalError(undo)) {
                undo.addSuppressed(failure);
                Exceptions.rethrowIfCriticalError(undo);
            }
            // An observer that threw this very instance for an acquisition (the start's failure) and again for a release
            if (undo != failure) {
                failure.addSuppressed(undo);
            }
        }
        return failure;
    }

    /**
     * End a run its own start has taken out, or found taken out by a stop that gave up waiting for it, while the start
     * still holds {@link #lifecycleLock}: what that stop would have done, or for a failed start what undoes it.
     * <p>
     * The observer is told first of every owner the run adopted: each one's acquisition was counted
     * ({@link #adoptIntoRun}), and ending the run reports its release. Then, for {@code stopAbruptly()}, only the
     * runtime is handed back, as {@link #stopAbruptly()} does; otherwise the run is ended as {@link #stop()} ends one
     * ({@link #endRun}): its owners drained, ended and released, the runtime handed back - and the instance deregistered
     * only when a {@code stop()} is owed. With no owner adopted, which is every failure before the adoption and every run
     * a stop took out before it, that is the runtime, and the deregistration when a {@code stop()} is owed.
     *
     * @param owed the stop the start owes, {@code null} for a failed start no stop is waiting on
     */
    private void endRunOfStart(Run run, StopKind owed) {
        synchronized (run) {
            run.advanceTo(Phase.ENDED);
        }
        var adopted = run.owners.stream().map(LeasedOwner::shard).toList();
        try {
            reportAcquired(adopted);
        } finally {
            if (owed == StopKind.ABRUPT) {
                releaseRuntime(run);
            } else {
                endRun(run, owed == StopKind.GRACEFUL);
            }
        }
    }

    @Override
    public boolean isStarted() {
        return currentRun.get() != null;
    }

    /**
     * The ordered lane's start-up up to the adoption of its units; {@link #start()} schedules the heartbeat.
     *
     * @return the units adopted into {@code run}, in the order they were leased
     */
    private List<Integer> startOrdered(Run run) throws SQLException {
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
        // That write is the liveness the delivery gate measures, so it counts as a confirmation. Otherwise the gate reads
        // the previous run's last heartbeat, or the queue's construction time, and a start more than four fifths of a
        // lease after either pauses delivery until the first heartbeat tick, a third of a lease later.
        confirmLiveness();

        ensureRuntime(settings);
        var leased = leaseAtStart(run, "ordered", orderedUnits(), maxShards);
        return adoptAtStart(run, "ordered", leased, (entry, wakeup) -> {
            var owner = new OrderedShardOwner(storage, entry.shard(), entry.grant().fence(), settings, handler, metrics,
                                              redeliveryPolicy, instanceId, wakeup,
                                              dispatch, entry.grant().previousOwnerReleased());
            owner.setDeliveryGate(this::deliveryPermitted);
            return owner;
        });
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

    /**
     * The unordered lane's start-up up to the adoption of its units; {@link #start()} schedules the heartbeat.
     *
     * @return the units adopted into {@code run}, in the order they were leased
     */
    private List<Integer> startUnordered(Run run) throws SQLException {
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
        // That write is the liveness the delivery gate measures, so it counts as a confirmation. Otherwise the gate reads
        // the previous run's last heartbeat, or the queue's construction time, and a start more than four fifths of a
        // lease after either pauses delivery until the first heartbeat tick, a third of a lease later.
        confirmLiveness();

        ensureRuntime(settings);
        var leased = leaseAtStart(run, "unordered", shardCount, maxShards);
        if (leased.isEmpty() && runtime.hasLivePump() && currentRun.get() == run) {
            log.warn("Instance {} leased no shards — another instance holds them all", instanceId);
        }

        return adoptAtStart(run, "unordered", leased, (entry, wakeup) -> {
            var owner = new ShardOwner(storage, entry.shard(), entry.grant().fence(), settings, handler, metrics,
                                       redeliveryPolicy, wakeup,
                                       instanceId, dispatch, entry.grant().previousOwnerReleased());
            owner.setDeliveryGate(this::deliveryPermitted);
            return owner;
        });
    }

    /**
     * Adopt the units a start-up leased into the run it is starting, each through {@link #adoptIntoRun}, as a heartbeat
     * tick's acquire loops do, so a unit joins a run one way only. The run can end meanwhile: a stop that gave up waiting
     * for the start takes it out without {@link #lifecycleLock}. Then the unit that finds it ended, and every unit leased
     * after it, is released again ({@link #abandonAcquisition}), as a tick undoes an acquisition its run no longer wants;
     * those adopted before are the run's, and the start ends them with it ({@link #endRunOfStart}). A unit whose owner
     * cannot be built fails the start: it and every unit leased after it are released before the failure escapes
     * ({@link #releaseLeasedByFailedStart}), and those adopted before it are ended with the run ({@link #undoFailedStart}) -
     * unless the failure is a critical error ({@link Exceptions#isCriticalError}), which escapes untouched, with nothing
     * released or ended.
     *
     * @return the units adopted, in the order they were leased
     */
    private List<Integer> adoptAtStart(Run run, String lane, List<LeasedUnit> leased,
                                       BiFunction<LeasedUnit, ShardWakeup, LeasedOwner> build) {
        var shardRuntime = runtime;
        var adopted      = new ArrayList<Integer>();
        for (var index = 0; index < leased.size(); index++) {
            var entry = leased.get(index);
            boolean joined;
            try {
                joined = adoptIntoRun(run, shardRuntime, lane, entry.shard(), wakeup -> build.apply(entry, wakeup));
            } catch (RuntimeException | Error e) {
                // A critical error escapes untouched, with nothing released (start()).
                Exceptions.rethrowIfCriticalError(e);
                releaseLeasedByFailedStart(lane, leased.subList(index, leased.size()), e);
                throw e;
            }
            if (!joined) {
                leased.subList(index, leased.size()).forEach(refused -> abandonAcquisition(refused.shard(), refused.grant().fence()));
                break;
            }
            adopted.add(entry.shard());
        }
        return adopted;
    }

    /**
     * Lease up to {@code maxShards} of {@code lane}'s free units for a starting queue: nothing while every pump of its
     * runtime has died, as in the heartbeat's acquire loops ({@link ShardRuntime#hasLivePump()}).
     * <p>
     * Called after {@link #ensureRuntime}, which is where a borrowed runtime becomes known. A unit taken with no live pump
     * would be placed on a dead pump, ended there on arrival ({@code ShardPump.add}), reported as owned and handed back a
     * heartbeat later — and for that third of a lease no other instance could take it. Left free instead, it shows in
     * {@code QueueHealth.unownedShards}, and the dead pump's ERROR says to restart the runtime.
     * <p>
     * Leases nothing more once {@code run} has ended - a stop that gave up waiting for the start took it out - and
     * returns what it has leased, for {@link #adoptAtStart} to release again.
     * <p>
     * When an acquire throws, the units already leased are released before the failure escapes
     * ({@link #releaseLeasedByFailedStart}): the start fails, so no owner will ever be built for them, and no stop or
     * heartbeat of the run releases them. Left named to this instance, they would be served by nobody, and taken by no
     * other instance while this one's membership row - written before this loop - is live. A critical error
     * ({@link Exceptions#isCriticalError}) is the exception: it escapes untouched, with nothing released.
     */
    private List<LeasedUnit> leaseAtStart(Run run, String lane, int units, int maxShards) throws SQLException {
        var leased = new ArrayList<LeasedUnit>();
        // First, pumps or none: a unit an earlier stop kept whose handler has run longer than stuckHandlerTimeout is no
        // longer waited for: this start takes it over below. And one whose handler has returned since, and that is not
        // handed back yet, is handed back now, so this start - or another instance - finds it released; reviewed first, so
        // one given up with no key in a handler - kept that long with the stop's flush of it not returned - is among them.
        reviewStuckKeptUnits();
        handBackKeptUnits();
        if (!runtime.hasLivePump()) {
            log.warn("Instance {} acquires no {} unit on queue {}: every pump of its ShardRuntime has died, so nothing would "
                             + "serve them. They stay free for other instances; restart the ShardRuntime to serve them here",
                     instanceId, lane, queueId);
            return leased;
        }
        // A unit an earlier stop of this queue kept, not handed back yet: its row names this instance, so acquireLeaseGrant
        // would take it at once, and the new owner could start that key beside the handler, or redeliver what it handled.
        // Counted towards maxShards, as a row this instance holds. Not one this queue no longer waits for, past
        // stuckHandlerTimeout (reviewStuckKeptUnits), and not handed back above: the loop takes that one over like any unit, under a new fence - and records that it asked,
        // so no later tick takes it over again past its limit (keptUnitsGivenUp).
        var notHandedBack = "ordered".equals(lane) ? keptUnitsNotHandedBack() : Set.<Integer>of();
        var givenUp       = "ordered".equals(lane) ? keptUnitsGivenUp() : Set.<Integer>of();
        if (!notHandedBack.isEmpty()) {
            log.info("Instance {} leaves ordered unit(s) [{}] on queue {} unacquired for now: a stop of this queue kept them "
                             + "because a key was still in a handler, and they have not been handed back. Each is handed back "
                             + "once its handler has returned - its acknowledgements flushed, its lease released - and is then "
                             + "free for this instance or another to take, so the key is not started beside its handler; one "
                             + "whose handler runs longer than stuckHandlerTimeout ({}) is taken over by this queue instead",
                     instanceId, summarise(notHandedBack), queueId, activeSettings.stuckHandlerTimeout());
        }
        try {
            // Checked per unit too, as in rebalance: what is placed after the last live pump died is ended on arrival.
            for (var shard = 0; shard < units && leased.size() + notHandedBack.size() < maxShards && runtime.hasLivePump() && currentRun.get() == run; shard++) {
                if (notHandedBack.contains(shard)) {
                    continue;
                }
                var grant = storage.acquireLeaseGrant(lane, shard, instanceId, leaseTtlMillis);
                if (givenUp.contains(shard)) {
                    takeoverAsked(shard);
                }
                if (grant.isPresent()) {
                    leased.add(new LeasedUnit(shard, grant.get()));
                }
            }
        } catch (SQLException | RuntimeException | Error e) {
            // A critical error escapes untouched, with nothing released (start()).
            Exceptions.rethrowIfCriticalError(e);
            releaseLeasedByFailedStart(lane, leased, e);
            throw e;
        }
        return leased;
    }

    /**
     * Release, in one fenced statement, the units a failing start leased and will build no owner for, and add to
     * {@code failure} as suppressed a release that fails - but for a critical error ({@link Exceptions#isCriticalError}),
     * which escapes instead, with {@code failure} added to it as suppressed. Fenced as {@link #abandonAcquisition}'s release is, so a row
     * leased again since under a newer fence is left alone. A release that fails puts the units in
     * {@link #unreleasedAcquisitions}, which the heartbeat of the next run started retries; until then they stay leased
     * to this instance, and free to another only once its membership row is older than the lease TTL.
     */
    private void releaseLeasedByFailedStart(String lane, List<LeasedUnit> units, Throwable failure) {
        if (units.isEmpty()) {
            return;
        }
        var fences = new HashMap<Integer, Long>();
        units.forEach(unit -> fences.put(unit.shard(), unit.grant().fence()));
        try {
            storage.releaseFencedLeases(lane, fences, instanceId);
            log.debug("Instance {} released {} unit(s) [{}] on queue {} again: its start failed after leasing them",
                      instanceId, lane, summarise(fences.keySet()), queueId);
        } catch (SQLException | RuntimeException | Error e) {
            if (Exceptions.isCriticalError(e)) {
                e.addSuppressed(failure);
                Exceptions.rethrowIfCriticalError(e);
            }
            fences.forEach((unit, fence) -> unreleasedAcquisitions.merge(unit, fence, Math::max));
            failure.addSuppressed(e);
            log.warn("Instance {} could not release the {} unit(s) [{}] on queue {} its failed start had leased. Each stays leased "
                             + "to this instance, served by nobody, until another instance can take it - once this instance's "
                             + "membership row is older than the lease TTL ({} ms) - or a later start of this queue retries the "
                             + "release on its heartbeat",
                     instanceId, lane, summarise(fences.keySet()), queueId, leaseTtlMillis, e);
        }
    }

    /**
     * Stop owning, without draining. Used by the crash-recovery tests, where the point is that
     * another instance picks up whatever was left.
     * <p>
     * Waits for no {@link #start()} still doing its database work, as a crash would not: it takes the starting run out
     * and leaves the start to hand back the runtime ({@link #lifecycleLock}). It waits only for a start already past that
     * work, and for a {@link #stop()} that has not yet taken its run out.
     */
    public void stopAbruptly() {
        // No drain and no release: this stands in for a crash. The units stay leased to this instance, and another instance
        // takes them over once this instance's membership row is older than the lease TTL: stopAbruptlyLocked() cancels the
        // run's heartbeat, which refreshes that row, and does not deregister it. It holds back no acknowledgement - the
        // runtime's pumps still flush the ones they hold when they exit - so a test that needs messages left unacknowledged
        // keeps their handlers from returning.
        // One lifecycle operation at a time, as start() and stop() (lifecycleLock) - but without waiting for it.
        if (!lifecycleLock.tryLock()) {
            stopWithoutWaiting(StopKind.ABRUPT, false);
            return;
        }
        try {
            stopAbruptlyLocked();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * {@link #stopAbruptly()}'s work, under {@link #lifecycleLock}.
     */
    private void stopAbruptlyLocked() {
        // Takes the run out as stop() does, so a tick still acquiring cannot hand its owner to a later start() (Run).
        // Its owners are neither ended nor released: this stands in for a crash.
        var run = currentRun.get();
        if (run != null) {
            synchronized (run) {
                currentRun.compareAndSet(run, null);
                run.advanceTo(Phase.ENDED);
            }
            run.cancelHeartbeat();
        }
        releaseRuntime(run);
    }

    /**
     * A stop that did not get {@link #lifecycleLock} - {@link #stop()} within {@link #SHUTDOWN_BUDGET} or on an
     * interrupted thread, {@link #stopAbruptly()} at once. A run whose start is still running ({@link Phase#STARTING}) is taken out here,
     * under the run's monitor, with the stop it owes recorded on it ({@link Run#phase}): the start, which holds the lock,
     * does the stop's work ({@link #endRunOfStart}). Any other run is stopped under the lock after all: one whose start has
     * finished its database work ({@link Phase#STARTED}) once that start lets go of the lock, all it has left being to
     * tell the observer of its acquisitions; one whose start escaped with a critical error ({@link Phase#START_ABORTED})
     * as soon as the lock is free, which for an interrupted stop it usually is already - no start is running for that
     * run, and nobody would do the work handed to it. No run current means there is nothing to stop: the holder is a stop
     * already ending it, or a start that failed and is undoing its own.
     *
     * @param interrupted whether {@link #stop()} got no further because its thread was interrupted, rather than because
     *                    it waited out {@link #SHUTDOWN_BUDGET}; an interrupted stop may not have waited at all
     */
    private void stopWithoutWaiting(StopKind stop, boolean interrupted) {
        var run = currentRun.get();
        if (run == null) {
            return;
        }
        synchronized (run) {
            if (currentRun.get() != run) {
                return;
            }
            // STARTING is the one phase in which a start is running for the run, holding lifecycleLock (Phase).
            if (run.phase() == Phase.STARTING) {
                run.advanceTo(stop == StopKind.ABRUPT ? Phase.STOP_OWED_ABRUPT : Phase.STOP_OWED_GRACEFUL);
                currentRun.compareAndSet(run, null);
                if (stop == StopKind.GRACEFUL && interrupted) {
                    log.warn("Instance {} was interrupted while its stop of queue {} waited for the queue's start to finish its database "
                                     + "work, and stopped the queue without it. The start does the stop's work itself once its statements "
                                     + "return: it releases the units it leased, hands back the runtime and deregisters the instance",
                             instanceId, queueId);
                } else if (stop == StopKind.GRACEFUL) {
                    log.warn("Instance {} gave up waiting {} for the start of queue {} to finish its database work, and stopped the "
                                     + "queue without it. The start does the stop's work itself once its statements return: it "
                                     + "releases the units it leased, hands back the runtime and deregisters the instance",
                             instanceId, SHUTDOWN_BUDGET, queueId);
                } else {
                    log.warn("Instance {} stopped queue {} abruptly without waiting for its start to finish its database work. The "
                                     + "start adopts none of the units it leases from then on, releasing each again, and hands back "
                                     + "the runtime once its statements return",
                             instanceId, queueId);
                }
                return;
            }
        }
        lifecycleLock.lock();
        try {
            if (stop == StopKind.ABRUPT) {
                stopAbruptlyLocked();
            } else {
                stopRun();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Hand back what this queue borrowed, forgetting the wake-ups of {@code run}'s owners (none when {@code run} is
     * {@code null}). A runtime passed in by the caller is theirs to close; the shared one is reference counted and closes
     * when its last borrower stops.
     */
    private void releaseRuntime(Run run) {
        if (runtime == null) {
            return;
        }
        if (run != null) {
            run.owners.forEach(owner -> runtime.forget(queueId, owner.lane(), owner.shard()));
        }
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
     * is unaffected by the clock moving. Every start confirms it when it registers the instance, so a run never reads the
     * value an earlier run, or the construction, left behind.
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
     * How often the heartbeat of a run ticks ({@link #startHeartbeat}): a third of {@code leaseTtl}, and no more often
     * than every 200 ms.
     */
    private long heartbeatIntervalMillis() {
        return Math.max(200L, leaseTtlMillis / 3);
    }

    /**
     * Renew the leases this instance holds, at a third of their lifetime.
     * <p>
     * A third rather than a half so that a single missed renewal — a GC pause, a slow query, a blip
     * on the connection — does not cost the shard. An owner whose renewal is refused has been
     * superseded and stops immediately; it does not wait to find out at acknowledgement time,
     * because by then it may have dispatched work its successor is also dispatching.
     */
    private void startHeartbeat(Run run) {
        var interval = heartbeatIntervalMillis();
        // Every tick works on this run, the one it was scheduled for, and on no other: a tick still running when the run has
        // ended - cancelling does not stop it - reaches only the ended run's owners.
        run.heartbeat = runtime.scheduleHeartbeat(() -> {
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
                var held = run.owners.stream()
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
                    // Decided under the run's monitor, the one stop() takes the run out under, and asked after the read,
                    // not before it. stop() takes the run out before it ends the run's owners and releases their rows, so
                    // either every owner below is moved before the stop has ended any of them, or the stop has taken the
                    // run out and none is: the run's owners are the stop's from then on (stop() ends each and releases or
                    // keeps its row; stopAbruptly() leaves them as a crash would), and a unit the stop gives up is not a
                    // lost lease. A row this read found absent because the stop had released it is therefore never read
                    // as taken: that release came after the take-out, so the check sees it.
                    var taken = new ArrayList<Integer>();
                    synchronized (run) {
                        if (currentRun.get() != run) {
                            break;
                        }
                        for (var owner : held) {
                            var fence = fences.get(owner.shard());
                            if (fence != null && fence == owner.fence()) {
                                metrics.leaseRenewals.increment();
                            } else {
                                // Absent, or back under a NEW fence — the unit was taken and handed on.
                                // Both mean this owner's fence is stale. The owner counts it in leasesLost if this call
                                // moves its row to RELEASED. It is RELEASED already, and not counted here, when one of its
                                // own fenced writes was refused since `held` was read: a refused takeover charge counted
                                // the loss in leasesLost, a refused acknowledgement in fencedOutAcks.
                                owner.onLeaseEnded(LeasedOwner.LeaseEnd.TAKEN);
                                taken.add(owner.shard());
                            }
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
            // A unit an earlier stop kept whose handler has outlasted stuckHandlerTimeout is no longer waited for: the
            // rebalance takes it over. Reviewed BEFORE the hand-backs below: a unit given up with no key in a handler - kept
            // that long with the stop's flush of it not returned - is then handed back in this pass rather than taken over.
            try {
                reviewStuckKeptUnits();
            } catch (RuntimeException e) {
                Exceptions.rethrowIfCriticalError(e);
                log.warn("Instance {} could not review the ordered units a stop of queue {} kept; retried on the next heartbeat",
                         instanceId, queueId, e);
            }
            // BEFORE the rebalance, as the hand-backs around it: a unit an earlier stop kept whose handler has returned, and
            // whose hand-back as its last key left the handler did not run or failed, is free for an acquire loop - this
            // instance's or another's - once it is handed back. Each unit's failure is its own, and logged there.
            handBackKeptUnits();
            // Prune releasedPastStuckHandler here, not only when the keptUnitsInHandler gauge is read: nothing in production
            // may ever read it, and an owner kept in that set holds what it had buffered.
            pruneReleasedPastStuckHandler();
            // BEFORE the rebalance, so a unit handed back here is free for its acquire loop to take again on a live pump, and
            // so its removeIf, which drops owners whose lease has ended, never drops one whose lease is still to be released.
            try {
                handBackUnitsOnDeadPumps(run);
            } catch (Exception e) {
                log.warn("Handing back the units on a dead pump failed for queue {}; retried on the next heartbeat", queueId, e);
            }
            try {
                rebalance(run);
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
     * Release again the lease rows an earlier heartbeat tick could not release when it undid an acquisition, or a failed
     * start could not release ({@link #unreleasedAcquisitions}, {@link #abandonAcquisition},
     * {@link #releaseLeasedByFailedStart}). One fenced statement for all of them; each entry is
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
            log.info("Instance {} released {} of {} {} unit(s) [{}] on queue {} that a heartbeat tick leased as the queue stopped, or a "
                             + "start leased before it failed, and that could not be released then; any not released had been "
                             + "taken meanwhile, by another instance or by this one under a newer fence",
                     instanceId, released, retrying.size(), activeLane, summarise(retrying.keySet()), queueId);
        } else {
            log.debug("Instance {} found the {} unit(s) [{}] on queue {} that could not be released as the queue stopped, or as a start "
                              + "failed, taken meanwhile, by another instance or by this one under a newer fence; nothing left to release",
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
     * Each tick's wait is logged at DEBUG, and once it outlasts {@code shedGrace} a WARN names the unit's keys still in a
     * handler, once per unit ({@link OrderedShardOwner#stuckOnDeadPump()}), as an abandoned ordered shed does. The wait is
     * bounded by {@code stuckHandlerTimeout} ({@link ShardOwnerSettings}): once the key handed to its handler first has
     * been there longer ({@link OrderedShardOwner#keysInHandler()}), the handler counts as stuck and the unit is handed back
     * with every key still in a handler - that one and any other still running - with one WARN naming the keys and the wait
     * ({@link #releasedPastStuckHandler}). Each of those messages is charged an attempt first, and one the charge brings to
     * the redelivery limit is dead-lettered ({@link #chargeKeysInHandlerBeforeRelease}): the next owner finds the unit
     * released and charges nothing, so a message whose handler hung on every delivery would otherwise never reach the
     * dead-letter table. Whoever takes the unit next delivers each of those messages not dead-lettered again, possibly
     * beside its handler.
     * Unbounded, a handler that never returned kept the unit leased and unserved until the runtime was restarted.
     * <p>
     * With no live pump left in the runtime this instance acquires nothing, so the units are released for other instances
     * only, and stay unowned if none runs; the WARN reporting the hand-back says which of the two it is.
     * <p>
     * No acknowledgement is flushed first, unlike {@link #stop()}: the owner's state was kept by the thread a critical
     * error just ended, and is not trusted to say what was handled. What was handled and not acknowledged is redelivered
     * (at-least-once).
     */
    private void handBackUnitsOnDeadPumps(Run run) throws SQLException {
        var shardRuntime = runtime;
        if (shardRuntime == null) {
            return;
        }
        var ready   = new ArrayList<LeasedOwner>();
        var waiting = new ArrayList<Integer>();
        // The ordered units handed back with a key still in a handler past stuckHandlerTimeout, each with its keys.
        var stuck   = new LinkedHashMap<LeasedOwner, OrderedShardOwner.KeysInHandler>();
        var bound   = activeSettings.stuckHandlerTimeout();
        for (var owner : run.owners) {
            // The runtime says which pump, the owner where its row stands. Normally ENDED_ROW_HELD, which the death moved it
            // to; HELD if ending it threw (ShardPump.endOnDeadPump), its row held all the same. Not RELEASED: a unit found
            // taken has no row here to release, and the rebalance's removeIf drops it and tells the runtime.
            if (!owner.holdsLeaseRow() || !shardRuntime.onDeadPump(owner)) {
                continue;
            }
            if ("ordered".equals(owner.lane()) && owner.inFlight() > 0) {
                if (owner instanceof OrderedShardOwner ordered) {
                    // Past stuckHandlerTimeout the handler counts as stuck, and the hand-back stops waiting for it: kept, the
                    // unit stayed leased and unserved for as long as the handler hung - until a restart if it never returned.
                    var inHandler = ordered.keysInHandler();
                    if (inHandler.isPresent() && inHandler.get().longest().compareTo(bound) >= 0) {
                        stuck.put(owner, inHandler.get());
                        ready.add(owner);
                        continue;
                    }
                    // Past shedGrace, one WARN per unit naming its keys, as an abandoned shed does: the pump's ERROR cannot
                    // say which key to look at. Every tick's DEBUG below covers the rest.
                    ordered.stuckOnDeadPump().ifPresent(
                            keys -> log.warn("Instance {} has waited {} ms to hand back ordered unit {} on queue {} from its dead pump: "
                                                     + "{} key(s) still in a handler {}. A unit is not handed back while one of its "
                                                     + "keys is in a handler, so it stays leased and unserved until the handler "
                                                     + "returns, or until a key has been in its handler longer than "
                                                     + "stuckHandlerTimeout ({}), when it is handed back with every key still in a handler. "
                                                     + "Logged once per unit",
                                             instanceId, keys.waited().toMillis(), owner.shard(), queueId, keys.keys().size(),
                                             keys.describeKeys(), bound));
                }
                waiting.add(owner.shard());
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
        // Each message still in a handler of a unit handed back past stuckHandlerTimeout is charged its attempt first, as the
        // next owner charges nothing for a released unit. Charged once per owner, so a retry after a failure below charges
        // nothing again. A unit whose charge fails is left out of this hand-back, held and retried on the next heartbeat; the
        // other units are handed back all the same.
        var charges = new HashMap<LeasedOwner, OrderedShardOwner.ReleaseCharge>();
        for (var owner : List.copyOf(stuck.keySet())) {
            try {
                charges.put(owner, chargeKeysInHandlerBeforeRelease(owner));
            } catch (SQLException | RuntimeException | Error e) {
                Exceptions.rethrowIfCriticalError(e);
                warnChargeBeforeReleaseFailed(owner, "from its dead pump", e);
                stuck.remove(owner);
                ready.remove(owner);
            }
        }
        if (ready.isEmpty()) {
            return;
        }
        // If it throws, nothing below has run: every owner is still in the run's `owners` with its row held (holdsLeaseRow),
        // and the next heartbeat retries. Neither acquire loop takes the unit again meanwhile: both count it towards the
        // fair share through holdsLeaseRow, and skip it (acquireFreeOrderedShards through its held set, the unordered loop
        // through the run's ownedShards).
        releaseLeaseRows(ready);
        var released = new ArrayList<Integer>();
        for (var owner : ready) {
            shardRuntime.handedBack(owner);
            run.owners.remove(owner);
            if (owner instanceof ShardOwner unordered) {
                run.ownedShards.remove(owner.shard(), unordered);
            }
        }
        // The observer last, after the bookkeeping for every owner: one that throws part-way leaves no owner released in
        // the database and still listed here. Only the releases this call is the first to report: a stop() of this run
        // running meanwhile releases these owners too (countRelease).
        for (var owner : ready) {
            if (countRelease(run, owner)) {
                released.add(owner.shard());
            }
        }
        stuck.forEach((owner, inHandler) -> releasedPastStuckHandler(owner, inHandler, charges.get(owner), "from its dead pump"));
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
        reportReleases(released);
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

    private void rebalance(Run run) throws SQLException {
        // Read once, as handBackUnitsOnDeadPumps does. stop() cancels the heartbeat without interrupting a tick already
        // running and then nulls the field for a borrowed shared runtime, so a tick overlapping a stop met it null here
        // and logged a NullPointerException as "Rebalance failed" during an ordinary shutdown. Every use below goes through
        // the local, so the rest of this tick cannot meet it null, whenever stop() lets it go.
        //
        // The current-run check spares a tick whose run has ended the whole rebalance; on a runtime the caller owns the
        // field stays set, and the tick would otherwise lease units for a stopped queue. It is check-then-act, so it is not
        // what keeps a tick already past it from leaving a unit behind when stop() begins during the acquire loops: both
        // loops adopt each unit they lease into this tick's run only while it is still the current one (adoptIntoRun says
        // why that closes the race, a stop followed by a start included).
        var shardRuntime = runtime;
        if (shardRuntime == null || currentRun.get() != run) {
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
        var lost = new ArrayList<Integer>();
        run.owners.removeIf(owner -> {
            // Only RELEASED. Not one whose row is still to be released (ENDED_ROW_HELD: its pump died, and
            // handBackUnitsOnDeadPumps releases it): dropping it here would leave the lease row naming this instance for good.
            if (owner.holdsLeaseRow()) {
                return false;
            }
            if (owner instanceof ShardOwner unordered) {
                run.ownedShards.remove(owner.shard(), unordered);
            }
            // A unit on a dead pump found taken is dropped here, not handed back, so stop counting it in
            // shardsOnDeadPumps. A no-op for any other owner.
            shardRuntime.handedBack(owner);
            // A unit found taken - by the lease check, or by one of its owner's fenced writes being refused - is counted
            // released here, as it leaves this instance. One this queue released was counted when it released it, and
            // countRelease counts nothing for it again.
            if (countRelease(run, owner)) {
                lost.add(owner.shard());
            }
            return true;
        });
        // Told before the acquire loops below, which may take the same units again: the observer hears the loss first.
        reportReleases(lost);

        if ("ordered".equals(activeLane)) {
            shedOrAcquireOrderedShards(run, fairShare, shardRuntime);
            return;
        }

        // leaseHeld (HELD): only an owner that serves its unit is shed. One whose pump died (ENDED_ROW_HELD) is released by
        // handBackUnitsOnDeadPumps.
        var held = run.owners.stream().filter(LeasedOwner::leaseHeld).toList();
        if (held.size() > fairShare) {
            var released = new ArrayList<Integer>();
            // Only the releases this loop is the first to count: a stop() of this run running meanwhile counts the
            // others (countRelease).
            var counted = new ArrayList<Integer>();
            Throwable loopFailure = null;
            try {
                for (var owner : held.subList(fairShare, held.size())) {
                    // One unit at a time: a failed release leaves this owner serving (HELD) and the next tick sheds it again.
                    // The owner may dispatch for the moment between the release and its end; a successor's acquire bumps the
                    // fence, so that is at worst a redelivery, which this lane allows.
                    releaseLeaseRows(List.of(owner));
                    run.ownedShards.remove(owner.shard(), owner);
                    if (countRelease(run, owner)) {
                        counted.add(owner.shard());
                    }
                    released.add(owner.shard());
                    log.debug("Instance {} released unordered shard {}", instanceId, owner.shard());
                }
            } catch (SQLException | RuntimeException | Error e) {
                loopFailure = e;
                throw e;
            } finally {
                // However the loop ended - a later shard's release throwing included - what it released is logged and
                // reported. The observer last, once every release is done: told inside the loop, one that throws for the
                // first unit left the rest of the excess unshed until the next tick. A release that threw stays the failure
                // the tick ends with, an observer's added to it (reportReleases).
                if (!released.isEmpty()) {
                    log.info("Instance {} released {} unordered shard(s) [{}] on queue {} — fair share is {} of {} "
                                     + "across {} instance(s); now holding {}",
                             instanceId, released.size(), summarise(released), queueId, fairShare, shardCount,
                             liveInstances, countHeld(run));
                }
                reportReleases(counted, loopFailure);
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
        var rowsHeld = run.owners.stream().filter(LeasedOwner::holdsLeaseRow).count();
        Throwable loopFailure = null;
        try {
            for (var shard = 0; shard < shardCount && rowsHeld < fairShare; shard++) {
                if (!shardRuntime.hasLivePump()) {
                    // The last live pump died during this loop. What it placed from here on would be ended on arrival
                    // (ShardPump.add) and handed back next tick, every tick.
                    break;
                }
                if (run.ownedShards.containsKey(shard)) {
                    continue;
                }
                var grant = storage.acquireLeaseGrant("unordered", shard, instanceId, leaseTtlMillis);
                if (grant.isEmpty()) {
                    continue;
                }
                var unit                  = shard;
                var fence                 = grant.get().fence();
                var previousOwnerReleased = grant.get().previousOwnerReleased();
                var adopted = adoptIntoRun(run, shardRuntime, "unordered", unit,
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
        } catch (SQLException | RuntimeException | Error e) {
            loopFailure = e;
            throw e;
        } finally {
            // However the loop ended - a later shard's acquire throwing included - what it adopted is listed and counted,
            // so it is logged and reported too. The observer last, outside the run's monitor (adoptIntoRun says why). An
            // acquire that threw stays the failure the tick ends with, an observer's added to it (reportAcquired).
            if (!acquired.isEmpty()) {
                log.info("Instance {} acquired {} unordered shard(s) [{}] on queue {}; now holding {} of {}",
                         instanceId, acquired.size(), summarise(acquired), queueId, countHeld(run), shardCount);
            }
            reportAcquired(acquired, loopFailure);
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
     * <p>
     * Bounded by {@code stuckHandlerTimeout} ({@link ShardOwnerSettings}): a shed whose grace runs out with a key in its
     * handler longer than that completes without it ({@link OrderedShardOwner#shedPastStuckHandler()}), and the unit is
     * released with the handler still running, with one WARN ({@link #releasedPastStuckHandler}). Each message still in a
     * handler is charged an attempt before the release, and one the charge brings to the redelivery limit is dead-lettered
     * ({@link #chargeKeysInHandlerBeforeRelease}), as the next owner charges nothing for a released unit. Unbounded, a hung
     * handler kept the unit here, the cluster unbalanced and the unit's other keys unserved, until a restart.
     */
    private void shedOrAcquireOrderedShards(Run run, int fairShare, ShardRuntime shardRuntime) throws SQLException {
        // Release the drained ones first, so a shard this instance has finished with is free before
        // it goes on to decide whether it wants more.
        var drained = new ArrayList<Integer>();
        // As in rebalance()'s unordered shed: only the releases this loop is the first to count.
        var counted = new ArrayList<Integer>();
        Throwable loopFailure = null;
        try {
            for (var owner : List.copyOf(run.owners)) {
                if (owner instanceof OrderedShardOwner ordered && ordered.shedComplete()) {
                    // A shed completed past stuckHandlerTimeout did not drain: a key was still in its handler. Each such
                    // message is charged its attempt before the release, as the next owner charges nothing for a released unit.
                    var stuck  = ordered.shedPastStuckHandler();
                    var charge = OrderedShardOwner.ReleaseCharge.NONE;
                    if (stuck.isPresent()) {
                        try {
                            charge = chargeKeysInHandlerBeforeRelease(ordered);
                        } catch (SQLException | RuntimeException | Error e) {
                            // This unit's failure is its own: it stays held, shed-complete, and is retried on the next heartbeat,
                            // while the other units are released. The fair-share comparison below leaves it out, as it does every
                            // shed-complete unit, so a charge that keeps failing does not keep this tick from the acquire step.
                            Exceptions.rethrowIfCriticalError(e);
                            warnChargeBeforeReleaseFailed(ordered, "in a fair-share shed", e);
                            continue;
                        }
                    }
                    // One unit at a time, as the unordered shed. A shed-complete owner dispatches nothing, so ending it after
                    // the release costs nothing.
                    releaseLeaseRows(List.of(ordered));
                    // A unit that finished its shed before its pump died, and whose hand-back failed this tick, is released
                    // here instead. Dropped from `owners` below, it would never be handed back, and stay counted in
                    // shardsOnDeadPumps (and shardsAwaitingTakeover) until the runtime stops. A no-op on a live pump.
                    shardRuntime.handedBack(ordered);
                    run.owners.remove(ordered);
                    if (countRelease(run, ordered)) {
                        counted.add(ordered.shard());
                    }
                    // Said at WARN, per unit, rather than in the summary below.
                    if (stuck.isPresent()) {
                        releasedPastStuckHandler(ordered, stuck.get(), charge, "in a fair-share shed");
                        continue;
                    }
                    drained.add(ordered.shard());
                    log.debug("Instance {} released ordered unit {} after draining", instanceId, ordered.shard());
                }
            }
        } catch (SQLException | RuntimeException | Error e) {
            loopFailure = e;
            throw e;
        } finally {
            // As in rebalance()'s unordered shed: logged and reported however the loop ended, the observer last, and a
            // release that threw stays the failure the tick ends with.
            if (!drained.isEmpty()) {
                log.info("Instance {} released {} ordered unit(s) [{}] on queue {} after draining; now holding {}",
                         instanceId, drained.size(), summarise(drained), queueId, countHeld(run));
            }
            reportReleases(counted, loopFailure);
        }

        // leaseHeld (HELD), as on the unordered lane: only an owner that serves its unit is asked to shed. One whose pump died
        // (ENDED_ROW_HELD) drains until no key is in its handlers, or one has been there longer than stuckHandlerTimeout, and
        // is released by handBackUnitsOnDeadPumps. Nor is one whose shed is complete: it is on its way out. The loop above took
        // every unit it released out of `owners`, so one still here is one whose release a later tick retries - its charge
        // before the release failed, or its shed completed after the loop read it. Counted, a unit whose charge kept failing
        // kept the run above its fair share for as long as it failed: each tick only asked for that shed again and returned,
        // and never reached the acquire step, so a given-up kept unit was not taken over. Its row is still this instance's, so the acquire step counts it towards its limit (holdsLeaseRow).
        var held = run.owners.stream()
                             .filter(LeasedOwner::leaseHeld)
                             .filter(owner -> !(owner instanceof OrderedShardOwner ordered && ordered.shedComplete()))
                             .toList();
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
                                 + "— fair share is {} of {}; a key in flight is finished first, unless its handler has run longer "
                                 + "than stuckHandlerTimeout",
                         instanceId, asked.size(), summarise(asked), queueId, fairShare, orderedUnits());
            }
            return;
        }
        acquireFreeOrderedShards(run, fairShare, shardRuntime);
    }

    /**
     * Take ordered shards nobody holds — which happens when an owner dies, and now also when another
     * instance has finished draining one.
     */
    private void acquireFreeOrderedShards(Run run, int fairShare, ShardRuntime shardRuntime) throws SQLException {
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
        var heldShards = run.owners.stream()
                                   .filter(LeasedOwner::holdsLeaseRow)
                                   .map(LeasedOwner::shard)
                                   .collect(java.util.stream.Collectors.toSet());
        // A unit an earlier stop of this queue kept stays unacquired until it has been handed back, as at start-up
        // (leaseAtStart); asked on every tick, so the first tick after the hand-back may take it. Its row names this
        // instance until then, so it counts towards this loop's limit as the run's own rows do, and the loop takes one free
        // unit fewer for it. The shed above does not count it: a run already over its share - through leaseAtStart while
        // the other instances were down - sheds down to its share of its own units and holds the kept row on top of them
        // until the hand-back.
        var notHandedBack = keptUnitsNotHandedBack();
        if (!notHandedBack.isEmpty()) {
            log.debug("Instance {} leaves ordered unit(s) [{}] on queue {} unacquired: a stop of this queue kept them, and they "
                              + "have not been handed back", instanceId, summarise(notHandedBack), queueId);
        }
        // A kept unit this queue no longer waits for, past stuckHandlerTimeout (reviewStuckKeptUnits), is taken over, under a new fence, before
        // any free unit, and whatever the limit: its row names this instance, so it counts towards the limit as a row held
        // already, and left untaken at a full share it would be served by nobody - no other instance takes a live
        // instance's row. A run over its share after it sheds as usual. Once, whether the grant is given or refused
        // (takeoverAsked): the kept owner holds its row in memory until its handler returns, maybe never, so asked again,
        // a unit this run sheds would be taken back past the limit on the tick that released it, and one another instance
        // holds would count as a row held here, one free unit fewer, for as long as the handler hangs.
        var takeOver = keptUnitsGivenUp();
        takeOver.removeAll(heldShards);
        takeOver.removeAll(notHandedBack);
        var rowsHeld = new HashSet<>(heldShards);
        rowsHeld.addAll(notHandedBack);
        rowsHeld.addAll(takeOver);
        var candidates = new ArrayList<Integer>(takeOver);
        for (var shard = 0; shard < orderedUnits(); shard++) {
            if (!takeOver.contains(shard)) {
                candidates.add(shard);
            }
        }
        var acquired   = new ArrayList<Integer>();
        Throwable loopFailure = null;
        try {
            for (var shard : candidates) {
                var takingOver = takeOver.contains(shard);
                if (!takingOver && rowsHeld.size() >= limit) {
                    break;
                }
                if (!takingOver && rowsHeld.contains(shard)) {
                    continue;
                }
                var grant = storage.acquireLeaseGrant("ordered", shard, instanceId, leaseTtlMillis);
                if (takingOver) {
                    takeoverAsked(shard);
                }
                if (grant.isEmpty()) {
                    continue;
                }
                var unit                  = shard;
                var fence                 = grant.get().fence();
                var previousOwnerReleased = grant.get().previousOwnerReleased();
                var adopted = adoptIntoRun(run, shardRuntime, "ordered", unit,
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
                rowsHeld.add(shard);
                acquired.add(shard);
                log.debug("Instance {} acquired ordered unit {} under fence {}", instanceId, shard, fence);
            }
        } catch (SQLException | RuntimeException | Error e) {
            loopFailure = e;
            throw e;
        } finally {
            // As in rebalance()'s unordered loop: what this loop adopted is logged and reported however it ended, and an
            // acquire that threw stays the failure the tick ends with. Not
            // orderedUnits() here: the loop's first condition resolved it, so the field holds it whenever something was adopted.
            if (!acquired.isEmpty()) {
                log.info("Instance {} acquired {} ordered unit(s) [{}] on queue {}; now holding {} of {}",
                         instanceId, acquired.size(), summarise(acquired), queueId, heldShards.size(), orderedUnits);
            }
            reportAcquired(acquired, loopFailure);
        }
    }

    /**
     * Adopt a unit leased for {@code run} into it, if it is still the current run ({@link #currentRun}). Adopting places
     * the unit on its pump ({@link ShardRuntime#place}), builds its owner with the wake-up that placement chose
     * ({@code build}), lists it in the run's {@code owners} (and {@code ownedShards}, for an unordered {@link ShardOwner}),
     * registers it on the pump and counts the acquisition ({@link #countAcquisition}). The one way a unit joins a run: a
     * start-up adopts what it leased through it ({@link #adoptAtStart}), and both heartbeat acquire loops each unit they
     * lease; when it refuses, the loop undoes the acquisition ({@link #abandonAcquisition}) and stops acquiring.
     * <p>
     * <b>Why under the run's monitor.</b> {@link #stop()} takes the run out of {@link #currentRun} and reads its
     * {@code owners} under the same monitor, so the two cannot interleave. Adopted first, the owner is in the list the stop
     * reads, and the stop drains, ends and releases it like the others; the stop first, this refuses, and only the tick
     * undoes it. Without the lock the stop's list was read concurrently with this check, and both could see the owner: the
     * stop released it and reported a release whose acquisition was never reported (the tick had not reported it yet), and
     * the tick released it again. A check before the owner is listed, like the one at the top of {@link #rebalance}, cannot
     * stand in for this one: a unit leased by a tick already past it when the stop began was in no list the stop read.
     * Nothing ended it, and on a runtime that keeps running after this queue stops (one the caller passed with
     * {@code setRuntime}, even to this queue alone, or the one shared per {@code DataSource} while another queue still
     * borrows it), it went on reading and dispatching for the stopped queue, its lease row naming an instance the stop had
     * just deregistered. The run's own monitor, not one of the queue's: a start-up adopting into a new run never waits
     * behind a tick of an earlier one.
     * <p>
     * <b>Placed only once adopted.</b> The placement is under the lock too, after the check, so a refused unit was never
     * placed and leaves no wake-up behind in the runtime. Placed before the check, as it was, a refused unit's wake-up had
     * to be forgotten again, and whether that was safe depended on whether the run current by then had placed the same
     * unit and so reused that wake-up: forgetting it left the unit's notifications reaching no owner, keeping it left an
     * entry nothing forgot until the runtime stopped. Placing the unit and building its owner are both in-memory work.
     * <p>
     * <b>An owner that cannot be built leaves no wake-up behind either.</b> When {@code build} throws, the unit joins no
     * run, so no {@link #releaseRuntime} forgets its wake-up: the placement is withdrawn ({@link ShardRuntime#withdraw})
     * and the failure escapes, its row left to the caller: a start-up releases it ({@link #adoptAtStart}), and a heartbeat
     * tick's acquire loop ends, leaving the row naming this instance for a later tick to take again. Withdrawn only when the placement
     * created the wake-up: one it reused was there before, and the unit is in no list of this run, so no owner of the run
     * waits on it. A critical error ({@link Exceptions#isCriticalError}) escapes untouched, with nothing withdrawn.
     * <p>
     * <b>The observer is told by the caller, outside the lock, in a {@code finally} around its loop</b>
     * ({@link #reportAcquired}). It is the caller's code (a Micrometer binding, tracing, {@code PostgresqlMessageQueue}'s
     * fan-out): told under the lock, a slow one held up {@code stop()} and {@link #stopAbruptly()}, which take it, and one
     * that threw escaped with the owner already listed and on its pump, and ended the acquire loop.
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
     * <b>Why the run's identity, not "is the queue running".</b> A tick can still be inside its acquire UPDATE after the
     * queue has been stopped and started again in this process, under the same instance id. A queue-wide running flag was
     * set again by then, so that tick found it set and its owner - built with the earlier run's runtime, its stale
     * settings, and a fence the new run may already have superseded - joined the new run until the next lease check
     * dropped it. The new run is a different {@link Run}, so the tick's is no longer current and it undoes its acquisition
     * instead.
     *
     * @param run   the run the unit was leased for: the one a heartbeat tick was scheduled for, or the one a start installed
     * @param build the unit's owner, built with the wake-up its placement chose
     * @return whether the unit was adopted; when not, nothing was placed or built, and the caller releases the row
     * @throws RuntimeException what {@code build} throws (or an {@code Error}), the unit not adopted and its placement
     *                          withdrawn; or what the {@link AdoptionHook} throws - from
     *                          {@link AdoptionHook#beforeAdopting} before anything is placed or the run checked, from
     *                          {@link AdoptionHook#beforeBuildingOwner} as {@code build} would. What becomes of the
     *                          unit's row is the caller's
     */
    private boolean adoptIntoRun(Run run, ShardRuntime shardRuntime, String lane, int unit,
                                 Function<ShardWakeup, LeasedOwner> build) {
        adoptionHook.beforeAdopting(lane, unit);
        synchronized (run) {
            if (currentRun.get() != run) {
                return false;
            }
            var         placement = shardRuntime.place(queueId, lane, unit);
            LeasedOwner owner;
            try {
                adoptionHook.beforeBuildingOwner(lane, unit);
                owner = build.apply(placement.wakeup());
            } catch (RuntimeException | Error e) {
                // A critical error escapes untouched, with nothing undone (start()).
                Exceptions.rethrowIfCriticalError(e);
                // The unit joins no run, so nothing forgets its wake-up later (releaseRuntime forgets only the owners a
                // run lists). The caller releases its row.
                shardRuntime.withdraw(placement);
                throw e;
            }
            run.owners.add(owner);
            if (owner instanceof ShardOwner unordered) {
                run.ownedShards.put(owner.shard(), unordered);
            }
            placement.register(owner);
            countAcquisition(run, owner);
            return true;
        }
    }

    /**
     * What a test does to each adoption ({@link #adoptIntoRun}) to reach a state no real adoption can be made to hold:
     * a start held between two adoptions, or an owner that cannot be built. Passed at construction, through the builder's
     * package-private {@link ShardOwnedQueueBuilder#setAdoptionHook}, and fixed for the queue's life; every queue built
     * otherwise has {@link #NONE}, which does nothing.
     */
    interface AdoptionHook {
        AdoptionHook NONE = new AdoptionHook() { };

        /**
         * Told the lane and number of a unit about to be adopted, before the run's monitor is taken, so blocking here
         * holds the adopting thread without holding that monitor. A heartbeat tick's acquire loop holds nothing a stop
         * waits for. A start-up still holds {@link #lifecycleLock}, and a plain {@link #stop()} waits for that lock, up to
         * {@link #SHUTDOWN_BUDGET}: only a stop that does not take it - {@code stop()} on an interrupted thread or past
         * that budget, or {@link #stopAbruptly()} - takes the run out while a start-up is held here
         * ({@link #stopWithoutWaiting}). What it throws escapes {@link #adoptIntoRun} before anything is placed or the
         * run checked, as a failed build would escape it.
         */
        default void beforeAdopting(String lane, int unit) {
        }

        /**
         * Told the lane and number of a unit whose owner is about to be built, under the run's monitor once the unit is
         * placed; what it throws fails that build, as an owner that cannot be built would. No real owner build fails short
         * of a critical error, and a test needs one that does to reach what the queue does with that unit's placement, its
         * row, and the units adopted before it.
         */
        default void beforeBuildingOwner(String lane, int unit) {
        }
    }

    /**
     * Undo an acquisition {@link #adoptIntoRun} refused, because the run the unit was leased for has ended: release
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
            // A run is current again only if the queue was started again: the one the unit was leased for has ended.
            if (currentRun.get() != null) {
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
                // The stop deregisters this instance - or leaves that to the other lane of its PostgresqlMessageQueue
                // subscription while that lane still runs, whose heartbeat keeps the row live until its own stop - or, when
                // an ordered unit was kept or the deregistration failed, the cancelled heartbeat stops refreshing the
                // membership row: gone, or older than the lease TTL, either way.
                log.warn("Instance {} leased {} unit {} on queue {} as the queue stopped, and could not release it again; it is "
                                 + "free to another instance once this instance's membership row is gone or older than the "
                                 + "lease TTL ({} ms). Should the queue be started again under the same instance id first, the "
                                 + "release is retried on every heartbeat until it goes through",
                         instanceId, activeLane, unit, queueId, leaseTtlMillis, e);
            }
        }
    }

    /**
     * Count an owner's acquisition, and record it as counted in its run ({@link Run#acquisitionReported}). The caller tells
     * the observer, outside the run's monitor: at start-up once the heartbeat is scheduled, on a heartbeat tick in a
     * {@code finally} around its acquire loop ({@link #reportAcquired}).
     */
    private void countAcquisition(Run run, LeasedOwner owner) {
        run.acquisitionReported.add(owner);
        metrics.shardsAcquired.increment();
    }

    /**
     * A copy of the owners the current run lists for the units it leases, in list order: the list {@link #stop()} ends and
     * releases, and the heartbeat's lease check and fair share go by. Empty while the queue is not started, a stop under
     * way included. Package-private: it is for asserting which owner serves a unit, not a figure to operate on.
     */
    List<LeasedOwner> owners() {
        var run = currentRun.get();
        return run == null ? List.of() : List.copyOf(run.owners);
    }

    /**
     * The monitor of the run current now, or {@code null} while the queue is not started: the one a heartbeat tick of that
     * run decides under, in {@link #adoptIntoRun}, whether its acquisition joins the run or is refused, and that
     * {@link #stop()} and {@link #stopAbruptly()} take the run out under. A later run has a monitor of its own.
     * Package-private: it is for a test that holds it to keep every tick of that run from adopting or refusing a unit until
     * it lets go, not for synchronising on in production code.
     */
    Object currentRunMonitor() {
        return currentRun.get();
    }

    /**
     * List an owner the caller has already put on a pump in the current run, with the bookkeeping {@link #adoptIntoRun}
     * does for an owner a heartbeat tick acquired - listed, mapped in {@code ownedShards} when it is an unordered {@link ShardOwner}, and its
     * acquisition counted ({@link #countAcquisition}) - so {@link #stop()} ends, releases and reports it like any other.
     * Package-private: it is for staging an owner whose behaviour a test controls, which a real acquisition cannot build.
     * The observer is not told of the acquisition; whatever releases the owner ({@link #stop()}, or a dead-pump hand-back,
     * {@link #handBackUnitsOnDeadPumps}) tells it of the release.
     *
     * @param owner the owner to list
     * @param first list it ahead of every other owner, so it is the first one {@link #stop()} ends, rather than last
     * @throws IllegalStateException when the queue is not running: {@link #adoptIntoRun} adopts into no ended run either
     */
    void adoptPlacedOwner(LeasedOwner owner, boolean first) {
        var run = currentRun.get();
        if (run != null) {
            synchronized (run) {
                if (currentRun.get() == run) {
                    if (first) {
                        run.owners.addFirst(owner);
                    } else {
                        run.owners.add(owner);
                    }
                    if (owner instanceof ShardOwner unordered) {
                        run.ownedShards.put(owner.shard(), unordered);
                    }
                    countAcquisition(run, owner);
                    return;
                }
            }
        }
        throw new IllegalStateException("Queue " + queueId + " is not running, so it adopts no owner");
    }

    /**
     * Tell the observer of every unit a start-up adopted ({@link #start()}, {@link #endRunOfStart}) or a heartbeat tick's
     * acquire loop adopted ({@link #adoptIntoRun}), outside the run's monitor. Each one is told even when the observer throws
     * for an earlier one: its acquisition is counted and its release will be told, so a unit skipped here would leave the
     * observer's released count ahead of its acquired count.
     * The first throw is rethrown once every unit has been told, the others added to it as suppressed, so the heartbeat
     * still logs the observer's failure.
     */
    private void reportAcquired(List<Integer> units) {
        tellOwnershipChanged(units, true, null);
    }

    /**
     * {@link #reportAcquired(List)} from the {@code finally} around a heartbeat tick's acquire loop, which may be ending
     * with {@code loopFailure} - an acquire statement that failed after the loop had adopted units ({@link #tellOwnershipChanged}
     * says what that changes).
     *
     * @param loopFailure what the loop is throwing, {@code null} when it ended normally
     */
    private void reportAcquired(List<Integer> units, Throwable loopFailure) {
        tellOwnershipChanged(units, true, loopFailure);
    }

    /**
     * Tell the observer of the release of every unit whose release the caller has just counted ({@link #countRelease}),
     * each one even when the observer throws for an earlier one, as {@link #reportAcquired} does: every one of them was
     * told acquired, so a unit skipped here would leave the observer's acquired count ahead of its released count for good.
     */
    private void reportReleases(List<Integer> units) {
        tellOwnershipChanged(units, false, null);
    }

    /**
     * {@link #reportReleases(List)} from the {@code finally} around a heartbeat tick's shed loop - either lane's - which may
     * be ending with {@code loopFailure}: a release statement that failed after the loop had released units
     * ({@link #tellOwnershipChanged} says what that changes).
     *
     * @param loopFailure what the loop is throwing, {@code null} when it ended normally
     */
    private void reportReleases(List<Integer> units, Throwable loopFailure) {
        tellOwnershipChanged(units, false, loopFailure);
    }

    /**
     * Tell the observer of every one of {@code units}, each one even when it throws for an earlier one - a
     * {@code RuntimeException} or a non-critical {@code Error}, such as a test double's {@code AssertionError} - and then
     * throw the first failure as it was thrown, the others added to it as suppressed ({@link Exceptions#forEachThenThrowFirst}).
     * Only a critical error ({@link Exceptions#isCriticalError}) leaves the units after it untold.
     * <p>
     * Called from a {@code finally} whose loop is already throwing ({@code loopFailure} not {@code null}), it throws nothing
     * of its own: every distinct failure of the observer is added to {@code loopFailure} as suppressed, once each
     * ({@link Exceptions#forEachCollectingFailures}). An observer that throws one instance for every unit adds it once.
     * The loop's failure is the database error that left the rest of the loop's work undone, and it is what ends the
     * heartbeat tick and what its {@code Rebalance failed} WARN logs. Thrown from the {@code finally}, the observer's
     * failure replaced it, and the database error was lost. A critical error still escapes: one the observer throws,
     * with {@code loopFailure} added to it as suppressed; and a {@code loopFailure} that is one escapes untouched, with
     * no unit told.
     *
     * @param loopFailure what the caller's loop is throwing, {@code null} when there is none
     */
    private void tellOwnershipChanged(List<Integer> units, boolean acquired, Throwable loopFailure) {
        Consumer<Integer> tell = unit -> metrics.observer().shardOwnershipChanged(unit, acquired);
        if (loopFailure == null) {
            Exceptions.forEachThenThrowFirst(units, tell);
        } else if (!Exceptions.isCriticalError(loopFailure)) {
            Exceptions.forEachCollectingFailures(units, tell, loopFailure);
        }
    }

    /**
     * Count an owner's release, if its acquisition was reported and its release is not yet: the owner leaves its run's
     * {@link Run#acquisitionReported}, atomically, so of a heartbeat tick and a {@link #stop()} of that run releasing it at
     * once, one counts it. The caller collects the units this returns true for and tells the observer of them after its
     * own work is done ({@link #reportReleases}), so an observer that throws leaves none of that work undone.
     *
     * @return whether this call counted it
     */
    private boolean countRelease(Run run, LeasedOwner owner) {
        if (!run.acquisitionReported.remove(owner)) {
            return false;
        }
        metrics.shardsReleased.increment();
        return true;
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
     *
     * @return how many rows the statement released: fewer than {@code releasing} when some had been taken meanwhile
     */
    private int releaseLeaseRows(List<? extends LeasedOwner> releasing) throws SQLException {
        if (releasing.isEmpty()) {
            return 0;
        }
        var fenceByShard = new HashMap<Integer, Long>();
        releasing.forEach(owner -> fenceByShard.put(owner.shard(), owner.fence()));
        var released = storage.releaseFencedLeases(activeLane, fenceByShard, instanceId);
        for (var owner : releasing) {
            owner.onLeaseEnded(LeasedOwner.LeaseEnd.RELEASED);
        }
        return released;
    }

    /**
     * Owners that serve their unit ({@link LeasedOwner#leaseHeld()}, {@code HELD}). Not the lease rows this instance holds:
     * see {@link #shardsHeld()}.
     */
    private static long countHeld(Run run) {
        return run.owners.stream().filter(LeasedOwner::leaseHeld).count();
    }

    /**
     * The units this instance serves on its configured lane: owners whose lease is held in memory
     * ({@link LeasedOwner.LeaseRow#HELD}).
     * <p>
     * Not every lease row that names this instance. A unit whose pump died keeps its row until the queue's heartbeat hands
     * it back, and until then it is counted in {@link ShardOwnerMetrics#shardsOnDeadPumps}, not here: on either lane its
     * owner ended at the death ({@code ENDED_ROW_HELD}), and the dead pump serves it no more.
     * <p>
     * The current run's only, so zero whenever {@link #isStarted()} is false: from the moment a stop begins, while it is
     * still draining and releasing, and after a {@link #stopAbruptly()}.
     */
    public int shardsHeld() {
        var run = currentRun.get();
        return run == null ? 0 : (int) countHeld(run);
    }

    /**
     * Whether an ordered unit a {@link #stop()} of this queue kept - or a start that ended its own run ({@link #endRun})
     * - has not been handed back yet: kept because a key was still in its handler {@code shedGrace} after the stop
     * began, its lease row still names this instance id, and the instance stays registered while the key is there. True
     * while the key is in its handler, and after that until the unit has been handed back ({@link #handBackKeptUnit}) -
     * its acknowledgements flushed, its lease row released - or found taken by another instance. A hand-back that fails
     * leaves it true, as the row still names this instance; {@link #handBackKeptUnits()} retries it. Until it is handed back,
     * another instance id can take the unit only once the instance's liveness lapses, a lease TTL after its last
     * heartbeat. This queue's own later runs do not take it before then ({@link #keptUnitsNotHandedBack()}), and take it
     * as any other unit once it is released - or take it over, under a new fence, once it is given up on past
     * {@code stuckHandlerTimeout} and not handed back ({@link #keptUnitsGivenUp()}). A queue that is not this one but registers under the same
     * instance id - a new {@link PostgresqlMessageQueue} subscription given the id, another {@code ShardOwnedQueue} built
     * with it - knows nothing of it, and would take the unit at once ({@link ShardOwnedStorage#acquireLeaseGrant} accepts
     * a row naming its own owner): while the key is in its handler it could start that key beside it, and after that it
     * would read the unit as taken over from an owner that never let go of it, charge an attempt and deliver again what
     * the handler acknowledged. {@link PostgresqlMessageQueue} asks {@link #keptOrderedUnitAwaited()}, which is this but for
     * a unit given up on past {@code stuckHandlerTimeout} ({@link #reviewStuckKeptUnits()}), before it lets the next {@code consume()} register
     * under an ended subscription's instance id, once it has had {@link #handBackKeptUnits()} retry a hand-back that has
     * not run or has failed. True for such a unit too, until it is handed back or found taken.
     * <p>
     * Asked of the owners themselves, so false from the moment the unit is handed back, whether or not this queue runs
     * again: the hand-back runs as the last key leaves its handler ({@link OrderedShardOwner#whenNoKeyInHandler}). A
     * {@link #stopAbruptly()} keeps no unit, as it releases nothing. Asked right after a {@code stop()} that gave up
     * waiting for a start still running, it does not yet see what that start's run keeps: that start ends its run, and
     * records what it kept, only once its statements return.
     */
    boolean keptOrderedUnitNotHandedBack() {
        return keptOrderedOwners.stream().anyMatch(KeptUnit::notHandedBack);
    }

    /**
     * Whether a later run of this queue, or a queue registering under its instance id, still has to wait for an ordered unit
     * a stop of this queue kept: one not handed back yet ({@link #keptOrderedUnitNotHandedBack()}) whose key has not been in
     * its handler longer than {@code stuckHandlerTimeout} ({@link ShardOwnerSettings}), nor that has been kept that long
     * with the stop that kept it still flushing it, as the last review of the kept units found
     * ({@link #reviewStuckKeptUnits()}, which gives such a unit up), and that was not released on request
     * with its key still in the handler ({@link #releaseKeptOrderedUnit}). A read only: it gives nothing up and logs
     * nothing, so the caller that wants a unit past the bound to count as given up has that review run first.
     * {@link PostgresqlMessageQueue} asks this before it lets the next {@code consume()} register under an ended
     * subscription's instance id, once it has had the lanes review their kept units: past the bound it stops waiting, and
     * the new subscription's ordered lane takes the unit over under a new fence ({@link ShardOwnedStorage#acquireLeaseGrant}
     * accepts a row naming its own owner and bumps the fence), as a run of this queue does ({@link #keptUnitsGivenUp()}).
     */
    boolean keptOrderedUnitAwaited() {
        return keptOrderedOwners.stream().anyMatch(kept -> kept.notHandedBack() && !kept.nothingWaitsForHandler());
    }

    /**
     * The ordered units whose kept owner ({@link #keptOrderedOwners}) still holds its lease row, and that have not been
     * given up on: the units no run of this queue acquires until they have been
     * handed back, and that its acquire loops count as rows this instance holds. A unit whose hand-back failed included:
     * every heartbeat tick retries it ({@link #handBackKeptUnits()}), and one the budget thread of the stop that kept it is
     * still flushing ({@link KeptUnit.Phase#STOP_FLUSHING}), for up to {@code stuckHandlerTimeout}. One given up on ({@link #reviewStuckKeptUnits()}) is not here
     * but in {@link #keptUnitsGivenUp()}, unless a hand-back of it is running ({@link KeptUnit.Phase#HANDING_BACK}): its
     * handler has returned, and the unit is being released.
     */
    private Set<Integer> keptUnitsNotHandedBack() {
        var units = new TreeSet<Integer>();
        for (var kept : keptOrderedOwners) {
            if (kept.owner().holdsLeaseRow() && !kept.isGivenUp()) {
                units.add(kept.owner().shard());
            }
        }
        return units;
    }

    /**
     * The ordered units a stop of this queue kept whose key was in its handler longer than {@code stuckHandlerTimeout}, or
     * that were kept that long with that stop still flushing them ({@link #reviewStuckKeptUnits()}), whose kept owner still
     * holds its lease row, and that no acquire of this queue
     * has asked for since ({@link KeptUnit.Phase#GIVEN_UP}, not yet {@code TAKEOVER_ASKED}): the units the ordered acquire loop takes over, under a new
     * fence, before any free unit ({@link #acquireFreeOrderedShards}). Taken over, the unit has a new owner in the run,
     * and the caller leaves out the units the run holds; the kept owner holds its row, as far as it knows, until its
     * handler returns and its fenced flush is refused - which may be never. So each unit is asked for once
     * ({@link #takeoverAsked}): once the run has taken it over, a shed or release of it frees its row like any unit's,
     * and once another instance holds it the refused grant is not asked again; either way it is then any other unit to
     * the acquire loops, under the fair-share limit.
     */
    private Set<Integer> keptUnitsGivenUp() {
        var units = new TreeSet<Integer>();
        for (var kept : keptOrderedOwners) {
            if (kept.owner().holdsLeaseRow() && kept.phase() == KeptUnit.Phase.GIVEN_UP) {
                units.add(kept.owner().shard());
            }
        }
        return units;
    }

    /**
     * Record that an acquire of this queue has asked for ordered unit {@code shard}, given or refused, so a kept unit
     * given up on ({@link #keptUnitsGivenUp()}) for that unit is asked for once ({@link KeptUnit.Phase#TAKEOVER_ASKED}).
     * Recorded only for a unit at {@link KeptUnit.Phase#GIVEN_UP}: an ask that lands while a hand-back claimed from there
     * runs ({@link KeptUnit.Phase#HANDING_BACK}) records nothing, so should that hand-back leave the row held, the unit is
     * back at {@code GIVEN_UP} and may be asked for once more.
     */
    private void takeoverAsked(int shard) {
        for (var kept : keptOrderedOwners) {
            if (kept.owner().shard() == shard) {
                kept.moveFrom(KeptUnit.Phase.GIVEN_UP, KeptUnit.Phase.TAKEOVER_ASKED);
            }
        }
    }

    /**
     * Have {@link ShardOwnerMetrics#keptUnitsInHandler()} count this queue ({@link #keptUnitsInHandlerSource}). Called after
     * each write that may leave this queue started or counting a kept unit: by {@link #start()} once it has installed its
     * run, by {@link #endRun} once it has written {@link #keptOrderedOwners}, and as an owner is added to
     * {@link #releasedPastStuckHandler}. Not as the queue is built: the metrics drop a source once its queue is stopped
     * and counts nothing, so metrics shared by queues built and closed over time do not keep every one of them.
     */
    private void countKeptUnitsInHandlerInMetrics() {
        metrics.countKeptUnitsInHandler(keptUnitsInHandlerSource);
    }

    /**
     * How many ordered units a stop of this queue kept still have a key in a handler ({@link #keptOrderedOwners}): what
     * this queue adds to {@link ShardOwnerMetrics#keptUnitsInHandler()}. A unit given up on and taken over
     * ({@link #reviewStuckKeptUnits()}) counts too while its old handler runs on, and so does one a shed or a dead pump's
     * hand-back released with a key still in a handler past {@code stuckHandlerTimeout} ({@link #releasedPastStuckHandler}),
     * dropped from that set here once its handler has returned, as by {@link #pruneReleasedPastStuckHandler()}.
     */
    private int keptUnitsInHandler() {
        var count = 0;
        for (var kept : keptOrderedOwners) {
            if (kept.owner().inFlight() > 0) {
                count++;
            }
        }
        for (var released : releasedPastStuckHandler) {
            if (released.inFlight() > 0) {
                count++;
            } else {
                releasedPastStuckHandler.remove(released);
            }
        }
        return count;
    }

    /**
     * Drop from {@link #releasedPastStuckHandler} every owner with no key left in a handler. Run by every heartbeat tick,
     * by every {@link #endRun} (a stop) and before each add, besides each read of {@link #keptUnitsInHandler()}: nothing
     * in production may read that gauge, and an owner left in the set keeps what it had buffered.
     */
    private void pruneReleasedPastStuckHandler() {
        releasedPastStuckHandler.removeIf(released -> released.inFlight() == 0);
    }

    /**
     * How many owners {@link #releasedPastStuckHandler} holds, read without pruning it - unlike
     * {@link #keptUnitsInHandler()} - so a test can see whether something other than a read of the gauge pruned it.
     */
    int releasedPastStuckHandlerSize() {
        return releasedPastStuckHandler.size();
    }

    /**
     * WARN that {@code owner}'s unit was released with keys still in a handler, the longest there longer than
     * {@code stuckHandlerTimeout}, and have {@link #keptUnitsInHandler()} count it while one is: by a shed, or by the
     * hand-back from a dead pump ({@code from}), with the attempts {@code charge} made on the messages in those handlers
     * before the release ({@link #chargeKeysInHandlerBeforeRelease}). Called once the release has run, so the row named
     * this instance under the owner's fence until then; the release does not bump the fence, but whoever takes the unit
     * next does.
     */
    private void releasedPastStuckHandler(LeasedOwner owner, OrderedShardOwner.KeysInHandler inHandler, OrderedShardOwner.ReleaseCharge charge,
                                          String from) {
        pruneReleasedPastStuckHandler();
        if (owner.inFlight() > 0) {
            releasedPastStuckHandler.add(owner);
            // After the add: a tick of a run a stop has taken out may get here with the queue stopped and its source dropped.
            countKeptUnitsInHandlerInMetrics();
        }
        log.warn("Instance {} released ordered unit {} on queue {} {} with {} key(s) {} still in a handler, the longest there {} ms, "
                         + "longer than stuckHandlerTimeout ({}), so those handlers were not waited for. {} message(s) in those "
                         + "handlers were charged an attempt, as a takeover charges one, and {} dead-lettered at their attempt limit {}. "
                         + "Whoever takes the unit next does so under a new fence and delivers the messages in those handlers again, "
                         + "bar those dead-lettered, possibly while those handlers still run them; nothing they do is acknowledged under the "
                         + "old fence. Counted in ShardOwnerMetrics.keptUnitsInHandler while one runs. Raise stuckHandlerTimeout if handlers of this "
                         + "queue legitimately run this long",
                 instanceId, owner.shard(), queueId, from, inHandler.keys().size(), inHandler.describeKeys(),
                 inHandler.longest().toMillis(), activeSettings.stuckHandlerTimeout(), charge.charged(),
                 charge.deadLetteredKeys().size(), charge.describeDeadLetteredKeys());
    }

    /**
     * Charge an attempt to each message still in a handler of {@code owner}, and dead-letter each one that reaches the
     * redelivery limit, before its unit is released without waiting for those handlers: past {@code stuckHandlerTimeout} by
     * a shed or the hand-back from a dead pump, or on request ({@link #releaseKeptOrderedUnit}). See
     * {@link OrderedShardOwner#chargeKeysInHandlerBeforeRelease}; nothing for an owner that is not ordered.
     *
     * @throws SQLException when the charge or a dead-letter move fails; the caller then does not release this owner's unit,
     *                      and its retry does not charge again
     */
    private OrderedShardOwner.ReleaseCharge chargeKeysInHandlerBeforeRelease(LeasedOwner owner) throws SQLException {
        if (!(owner instanceof OrderedShardOwner ordered)) {
            return OrderedShardOwner.ReleaseCharge.NONE;
        }
        try (var connection = storage.connection()) {
            return ordered.chargeKeysInHandlerBeforeRelease(connection);
        }
    }

    /**
     * WARN that the charge before releasing {@code owner}'s unit past {@code stuckHandlerTimeout} ({@code from} a shed or a
     * dead pump) failed, so the unit was not released on this heartbeat tick.
     */
    private void warnChargeBeforeReleaseFailed(LeasedOwner owner, String from, Throwable failure) {
        log.warn("Instance {} could not charge the messages in a handler of ordered unit {} on queue {} an attempt before releasing "
                         + "it {} past stuckHandlerTimeout ({}), so it did not release the unit: it stays leased to this instance and "
                         + "the release is retried on the next heartbeat. Other units are released as usual",
                 instanceId, owner.shard(), queueId, from, activeSettings.stuckHandlerTimeout(), failure);
    }

    /**
     * Look at every ordered unit a stop of this queue kept ({@link #keptOrderedOwners}) whose lease row still names this
     * instance:
     * <ul>
     *   <li>Once the key handed to its handler first has been there longer than {@code stuckHandlerTimeout}
     *       ({@link ShardOwnerSettings}), stop waiting for it, once and for good ({@link KeptUnit.Phase#GIVEN_UP}), with one
     *       WARN - once the budget thread of the stop that kept it has flushed it ({@link KeptUnit.Phase#STOP_FLUSHING}):
     *       from then on no run of this queue skips the unit, its acquire loop takes it over under a new fence, once
     *       ({@link #keptUnitsGivenUp()}), and neither the unit nor its handler keeps the instance registered on a later
     *       stop or keeps a {@link PostgresqlMessageQueue} subscription's instance id out of use
     *       ({@link #keptOrderedUnitAwaited()}). The handler's acknowledgement is refused under the new fence and its
     *       message delivered again, with an attempt charged, possibly while the handler still runs. Past the bound with
     *       that flush still running, WARN that the unit is held up for it, at most once a minute per unit.</li>
     *   <li>Whatever its handler does, once the unit has been kept longer than {@code stuckHandlerTimeout} and still
     *       stands at {@link KeptUnit.Phase#STOP_FLUSHING} ({@link KeptUnit#keptFor()}), give it up the same way, with
     *       one WARN: that budget thread's database work has no timeout of its own and may never return - on a connection
     *       that hangs with no {@code socketTimeout} - and nothing else lets the unit out of that phase. A unit with no
     *       key in a handler is then handed back ({@link #handBackKeptUnit}): a heartbeat tick, a start and a
     *       {@link PostgresqlMessageQueue} {@code consume()} review before they hand back, so in the same pass, unless the
     *       hand-back fails and an acquire takes the unit over. Should that flush return once the unit has been released or
     *       taken over, its writes are refused, and the messages it acknowledges delivered again: the at-least-once the
     *       bound accepts. An ended {@link PostgresqlMessageQueue} subscription's instance id stays out of use while that
     *       flush runs all the same ({@link #handBackStillRunning()}).</li>
     *   <li>Before the bound, once the key has been in its handler longer than {@code leaseTtl}, WARN that the unit is
     *       held up, naming the queue, the unit, its keys in a handler, how long the longest has been there and the bound
     *       - at most once a minute per unit ({@code KeptUnit.warningDue}, an {@link ErrorWindow}). Before
     *       {@code leaseTtl}, the stop's own WARN said it kept the unit, and the acquire loops say at DEBUG that they skip
     *       it.</li>
     * </ul>
     * Called by every heartbeat tick and start, before they acquire anything, by a stop as it ends a run, and by
     * {@link PostgresqlMessageQueue} for the lanes of an ended subscription before it asks them
     * {@link #keptOrderedUnitAwaited()}, outside its own monitor, and by {@link #releaseKeptOrderedUnit} before it claims: this takes each kept owner's lock to read its keys, and
     * logs. Logs, never throws but a critical error.
     */
    void reviewStuckKeptUnits() {
        var settings = activeSettings;
        if (settings == null) {
            return;
        }
        for (var kept : keptOrderedOwners) {
            if (kept.isGivenUp() || !kept.owner().holdsLeaseRow()) {
                continue;
            }
            // The stop's budget thread lets the unit out of STOP_FLUSHING once its flush returns, and that flush has no
            // query timeout of its own: on a connection that hangs - a half-open socket with no socketTimeout - it may
            // never return, and nothing else lets the unit out. So kept longer than stuckHandlerTimeout and still at
            // STOP_FLUSHING, the unit is given up whatever its handler does: a flush still running then is refused should
            // it return once the unit has been released or taken over.
            var keptFor = kept.keptFor();
            if (kept.phase() == KeptUnit.Phase.STOP_FLUSHING && keptFor.compareTo(settings.stuckHandlerTimeout()) >= 0) {
                if (kept.moveFrom(KeptUnit.Phase.STOP_FLUSHING, KeptUnit.Phase.GIVEN_UP)) {
                    var stillInHandler = kept.keysInHandler();
                    log.warn("Instance {} stops waiting for ordered unit {} on queue {}: a stop of this queue kept it {} ms ago, "
                                     + "longer than stuckHandlerTimeout ({}), and that stop has still not finished flushing the "
                                     + "acknowledgements of the owners it ended, so nothing has handed the unit back. {}. Should "
                                     + "that flush return once the unit has been released or taken over, its writes for the unit "
                                     + "are refused, and the messages they acknowledge are delivered again. While that flush runs, "
                                     + "an ended PostgresqlMessageQueue subscription this queue is a lane of keeps its instance id out "
                                     + "of use, so the next consume() registers under another one. A database call that "
                                     + "never returns is often a connection hung with no socketTimeout set on the DataSource",
                             instanceId, kept.owner().shard(), queueId, keptFor.toMillis(), settings.stuckHandlerTimeout(),
                             stillInHandler.map(keys -> keys.keys().size() + " key(s) " + keys.describeKeys() + " are still in a "
                                     + "handler, the longest there " + keys.longest().toMillis() + " ms. A run of this queue - the "
                                     + "one running now, or the next - takes the unit over under a new fence, or another instance "
                                     + "once this instance's liveness has lapsed: the messages in those handlers are "
                                     + "delivered again, with an attempt charged, and a key may be in two handlers at once")
                                           .orElse("No key of it is in a handler: a heartbeat tick, a start or a later stop of "
                                                   + "this queue hands it back, as does the next consume() of a "
                                                   + "PostgresqlMessageQueue whose ended subscription this queue is a lane of, "
                                                   + "unless a run of this queue takes it over first, under a new fence, or "
                                                   + "another instance once this instance's liveness has lapsed"));
                }
                continue;
            }
            var inHandler = kept.keysInHandler();
            if (inHandler.isEmpty()) {
                continue;
            }
            var longest = inHandler.get().longest();
            if (longest.compareTo(settings.stuckHandlerTimeout()) >= 0) {
                // From AWAITING_HAND_BACK only: not while the stop's budget thread is still flushing the owner - taken over
                // then, that flush would be refused; given up above once it has run past the bound - nor while a hand-back
                // runs, whose handler has returned.
                if (kept.moveFrom(KeptUnit.Phase.AWAITING_HAND_BACK, KeptUnit.Phase.GIVEN_UP)) {
                    log.warn("Instance {} stops waiting for ordered unit {} on queue {}: a stop of this queue kept it because {} key(s) "
                                     + "{} were still in a handler, and the longest has now been there {} ms, longer than "
                                     + "stuckHandlerTimeout ({}). A run of this queue - the one running now, or the next - or a "
                                     + "PostgresqlMessageQueue subscription registering under this instance id takes the unit over "
                                     + "under a new fence: the handler's "
                                     + "acknowledgement is refused, its message delivered again with an attempt charged, and that key "
                                     + "may be in two handlers at once, the stuck one and the new one. Raise stuckHandlerTimeout if "
                                     + "handlers of this queue legitimately run this long",
                             instanceId, kept.owner().shard(), queueId, inHandler.get().keys().size(), inHandler.get().describeKeys(),
                             longest.toMillis(), settings.stuckHandlerTimeout());
                } else if (kept.phase() == KeptUnit.Phase.STOP_FLUSHING && kept.warningDue()) {
                    log.warn("Instance {} holds ordered unit {} on queue {} unserved: a stop of this queue kept it because {} key(s) {} were "
                                     + "still in a handler, and the longest has now been there {} ms, longer than stuckHandlerTimeout "
                                     + "({}), but that stop is still flushing the acknowledgements of the owners it ended, this one "
                                     + "among them. Nothing gives the unit up until that flush has returned, or until the unit has "
                                     + "been kept longer than stuckHandlerTimeout - {} ms so far. Logged at most once a minute per unit",
                             instanceId, kept.owner().shard(), queueId, inHandler.get().keys().size(), inHandler.get().describeKeys(),
                             longest.toMillis(), settings.stuckHandlerTimeout(), keptFor.toMillis());
                }
            } else if (longest.toMillis() > leaseTtlMillis && kept.warningDue()) {
                log.warn("Instance {} holds ordered unit {} on queue {} unserved: a stop of this queue kept it because {} key(s) {} were "
                                 + "still in a handler, and the longest has now been there {} ms, longer than the {} ms leaseTtl. No run "
                                 + "of this queue takes the unit, and no other instance while this one is live, until that handler "
                                 + "returns or has been there longer than stuckHandlerTimeout ({}), when a run of this queue, or a "
                                 + "PostgresqlMessageQueue subscription registering under this instance id, takes it over - or until "
                                 + "it is released on request before that (MessageQueue.releaseKeptOrderedUnit(\"{}\", {}), admin API POST "
                                 + ".../ordered-units/{}/release-kept?instanceId={}, on this process), which accepts that the messages in "
                                 + "those handlers are delivered again, possibly beside them. Logged at most once a minute per unit",
                         instanceId, kept.owner().shard(), queueId, inHandler.get().keys().size(), inHandler.get().describeKeys(),
                         longest.toMillis(), leaseTtlMillis, settings.stuckHandlerTimeout(),
                         instanceId, kept.owner().shard(), kept.owner().shard(), instanceId);
            }
        }
    }

    /**
     * Hand back every kept unit ({@link #keptOrderedOwners}) no key of which is in a handler and whose lease row still
     * names this instance ({@link #handBackKeptUnit}), on the caller's thread. Called by every heartbeat tick, before its
     * rebalance, by a start before it leases anything, and by {@link PostgresqlMessageQueue} for the lanes of an ended
     * subscription whose instance id it holds ({@link #keptOrderedUnitAwaited()}), before it asks whether a
     * {@code consume()} may register under that id: those lanes never run again, so nothing else retries a hand-back that
     * failed as the key left its handler. Each caller reviews the kept units first ({@link #reviewStuckKeptUnits()}), so a
     * unit that review gives up with no key in a handler is handed back here before any acquire may take it over. Each unit's failure
     * is its own and logged; nothing is thrown, but a critical error.
     */
    void handBackKeptUnits() {
        for (var kept : keptOrderedOwners) {
            handBackKeptUnit(kept);
        }
    }

    /**
     * Hand back the ordered unit {@code kept}, on a thread of its own, once no key of its owner is in a handler: what the
     * kept owner's last key leaving its handler runs ({@link OrderedShardOwner#whenNoKeyInHandler}), so the unit is handed
     * back whether or not this queue runs by then - an unsubscribed {@link PostgresqlMessageQueue} subscription's lanes
     * never run again. Not on the handler's thread: that thread belongs to the runtime's handler executor, and the
     * hand-back waits for the database.
     */
    private void handBackKeptUnitOnItsOwnThread(KeptUnit kept) {
        try {
            if (kept.owner().inFlight() > 0 || !kept.owner().holdsLeaseRow()) {
                return;
            }
            var thread = new Thread(() -> handBackKeptUnit(kept), "shard-queue-kept-unit-" + queueId + "-" + kept.owner().shard());
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Instance {} could not start handing back ordered unit {} on queue {}, kept by a stop while a key was in "
                             + "its handler; a heartbeat or start of this queue retries it if the queue runs again, as does the next "
                             + "consume() of a PostgresqlMessageQueue that would register under this instance id",
                     instanceId, kept.owner().shard(), queueId, e);
        }
    }

    /**
     * Hand back the ordered unit {@code kept}, which a stop of this queue kept because a key was still in its handler: when
     * no key of its owner is in a handler, its lease row still names this instance, the budget thread of the stop that
     * kept it has flushed it ({@link KeptUnit.Phase#STOP_FLUSHING}) - or has not within {@code stuckHandlerTimeout}, and
     * the unit was given up on ({@link #reviewStuckKeptUnits()}) -, and no other hand-back of it is running
     * ({@link KeptUnit#claimHandBack}), flush the owner's acknowledgements under the fence it was kept under and release its
     * row ({@link #releaseLeaseRows}). Both are fenced: the fence is still valid, as releasing does not bump it, and a unit
     * another instance has taken meanwhile - once this one's liveness lapsed - is left alone: a refused flush moves the
     * owner to {@code RELEASED} itself ({@link LeasedOwner.LeaseRow}), and nothing is released then. Every caller - the key
     * leaving its handler, a heartbeat tick, a start, a later stop, a {@link PostgresqlMessageQueue} {@code consume()} -
     * comes through here, so none flushes the owner while that budget thread may still be flushing it, bar one whose
     * flush has outlasted {@code stuckHandlerTimeout}: the thread's own flush, finding the row released, was refused and
     * counted a lease lost.
     * <p>
     * Whoever takes the unit next finds it released ({@link ShardOwnedStorage.LeaseGrant#previousOwnerReleased()}): it
     * charges no attempt for a takeover and delivers again nothing the kept handler acknowledged. Taken back with the row
     * still naming this instance, as a run of this queue used to, the unit read as taken over from an owner that never let
     * go of it: an attempt charged on the key's rows, and the message the handler had just handled delivered again.
     * <p>
     * Not counted as a release ({@link #countRelease}): the stop that kept the unit counted it, as the unit left its run.
     * A failure is logged and leaves the row as it is; {@link #handBackKeptUnits()} retries it, and a later run's stop.
     */
    private void handBackKeptUnit(KeptUnit kept) {
        var owner = kept.owner();
        if (owner.inFlight() > 0 || !owner.holdsLeaseRow()) {
            return;
        }
        var claimedFrom = kept.claimHandBack();
        if (claimedFrom == null) {
            return;
        }
        try {
            // Asked again under the claim: another hand-back may have released the row since.
            if (owner.inFlight() > 0 || !owner.holdsLeaseRow()) {
                return;
            }
            try (var connection = storage.connection()) {
                owner.flushOnStop(connection);
            }
            if (owner.holdsLeaseRow()) {
                releaseLeaseRows(List.of(owner));
                if (claimedFrom.isGivenUp()) {
                    // The fenced release changed nothing if the unit was taken over since, by this instance id too.
                    log.info("Instance {} released ordered unit {} on queue {} under the fence a stop kept it under: this queue "
                                     + "stopped waiting for the unit past stuckHandlerTimeout, and no key of it is in a handler now. "
                                     + "Whoever has taken the unit over since holds it under a newer fence, which this release left alone",
                             instanceId, owner.shard(), queueId);
                } else {
                    log.info("Instance {} handed back ordered unit {} on queue {}, which a stop kept while a key was in its handler: "
                                     + "the handler has returned, its acknowledgements are flushed and its lease released - unless "
                                     + "another instance had taken the unit meanwhile - so any instance below its fair share may take it",
                             instanceId, owner.shard(), queueId);
                }
            } else {
                log.info("Instance {} found ordered unit {} on queue {}, which a stop kept while a key was in its handler, taken "
                                 + "over meanwhile - by another instance, or, once this queue had stopped waiting for it past "
                                 + "stuckHandlerTimeout, "
                                 + "under this instance id - when the handler returned; nothing left to hand back",
                         instanceId, owner.shard(), queueId);
            }
        } catch (SQLException | RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Instance {} could not hand back ordered unit {} on queue {}, which a stop kept while a key was in its handler "
                             + "that has returned since. Its lease still names this instance; a heartbeat or start of this queue "
                             + "retries the hand-back if the queue runs again, as does the next consume() of a PostgresqlMessageQueue "
                             + "that would register under this instance id, and another instance can take the unit once this "
                             + "one's liveness lapses", instanceId, owner.shard(), queueId, e);
        } finally {
            kept.endHandBack(claimedFrom, owner.holdsLeaseRow());
        }
    }

    /**
     * Release, on request, ordered unit {@code unit} if a stop of this queue kept it ({@link #keptOrderedOwners}) and its
     * lease row still names this instance under the fence it was kept under, whether or not a key of it is still in its
     * handler: the operator's way out of a handler known to be hung, before {@code stuckHandlerTimeout}
     * ({@code MessageQueue.releaseKeptOrderedUnit}, which {@link PostgresqlMessageQueue} answers by asking each of its
     * lanes). Through the hand-back's own claim ({@link KeptUnit#claimHandBack}) and its two steps: the owner's
     * acknowledgements flushed under the kept fence, then its row released ({@link #releaseLeaseRows}), both fenced - so a
     * unit taken meanwhile, by another instance or under this instance id past {@code stuckHandlerTimeout}, is left alone
     * and reported {@link KeptUnitRelease.Outcome#ALREADY_TAKEN}. Nothing is done while the stop that kept the unit is still
     * flushing it ({@link KeptUnit.Phase#STOP_FLUSHING}) or another hand-back or release of it runs:
     * {@link KeptUnitRelease.Outcome#BUSY}. The kept units are reviewed first ({@link #reviewStuckKeptUnits()}), as no
     * heartbeat does it for a queue that is stopped: a unit kept longer than {@code stuckHandlerTimeout} whose stop has
     * still not flushed it is given up on then, and released here rather than answered {@code BUSY} for as long as that
     * flush hangs. More than one kept entry can name the unit - a run of this queue that took it
     * over past {@code stuckHandlerTimeout} and whose stop kept it again lists its entry after the earlier one - so each is
     * tried in turn until one is released; with none released, the answer is {@code BUSY} when any entry was, else
     * {@code ALREADY_TAKEN}.
     * <p>
     * Which keys are in a handler is read once per entry, under the claim, after the flush and before the row is released:
     * approximately, a handler that returned before the flush had its acknowledgement flushed and is not named (the two
     * narrow windows where the reading and the flush disagree are described at the read). That one reading decides
     * the log line, the keys in the answer and the phase the unit ends in. Released with a key still in its handler, the
     * unit moves to {@link KeptUnit.Phase#FORCE_RELEASED}: nothing waits for that handler any more. Before the row is
     * released, the key's message is charged an attempt, under the kept fence, and dead-lettered if that brings it to the
     * redelivery limit ({@link #chargeKeysInHandlerBeforeRelease}): whoever takes the unit next finds it released and
     * charges nothing, so without it a message whose handler hung on every delivery, released on request each time, never
     * reached the dead-letter table. That next owner delivers the key's message again, unless it was dead-lettered, as it
     * was never acknowledged, possibly while the handler still runs it; the handler's acknowledgement, once it returns, is
     * not flushed, as the owner no longer holds the row. One WARN says so, naming the keys. Released with none in a handler,
     * it is an ordinary hand-back ({@link KeptUnit.Phase#HANDED_BACK}), and nothing is charged. A {@code BUSY} entry, not
     * claimed, names the keys as they stand when asked. Not counted as a release ({@link #countRelease}): the stop that
     * kept the unit counted it.
     * <p>
     * Asked for another instance id than this queue's ({@code requestedInstanceId}), this touches nothing - not even the
     * review - and answers empty: the operator named the instance whose WARN reported the unit kept, and a unit of the
     * same number this queue kept is not the one diagnosed.
     *
     * @param requestedInstanceId the instance id the caller asks for, as the stop's WARN names it
     * @return what was done, empty when this queue keeps no such unit with its row still held, or runs under another
     * instance id than {@code requestedInstanceId}
     * @throws SQLException when the flush, the charge or the release fails; the unit is then left as it was, kept, for a
     *                      later hand-back or request, and a later request does not charge again
     */
    Optional<KeptUnitRelease> releaseKeptOrderedUnit(String requestedInstanceId, int unit) throws SQLException {
        requireNonNull(requestedInstanceId, "No requestedInstanceId provided");
        if (!instanceId.equals(requestedInstanceId)) {
            return Optional.empty();
        }
        reviewStuckKeptUnits();
        // More than one kept entry can name the unit: a run of this queue that took it over past stuckHandlerTimeout, and
        // whose stop kept it again, lists its own entry after the earlier one, which still reads as holding its row until a
        // fenced write of it is refused. So an entry found taken or busy does not end the search: a later one may hold the row.
        KeptUnitRelease busy  = null;
        KeptUnitRelease taken = null;
        for (var kept : keptOrderedOwners) {
            var owner = kept.owner();
            if (owner.shard() != unit || !owner.holdsLeaseRow()) {
                continue;
            }
            var claimedFrom = kept.claimHandBack();
            if (claimedFrom == null) {
                // Not claimed, so read as the keys stand now: approximate, as a handler may return before the stop's flush
                // or the hand-back working on the unit does.
                busy = keptUnitRelease(unit, KeptUnitRelease.Outcome.BUSY, kept.keysInHandler());
                continue;
            }
            var released  = false;
            var inHandler = Optional.<OrderedShardOwner.KeysInHandler>empty();
            var charge    = OrderedShardOwner.ReleaseCharge.NONE;
            try {
                // Asked again under the claim: the stop's own flush may have found the row taken since.
                if (owner.holdsLeaseRow()) {
                    try (var connection = storage.connection()) {
                        owner.flushOnStop(connection);
                    }
                }
                // Read once, under the claim, after the flush and before the row is released: the keys whose
                // acknowledgements this release drops. A handler that returned before the flush had its acknowledgement
                // flushed, so its message is not delivered again; one still in a handler here returns to an owner whose
                // row is released, and its acknowledgement is never flushed. The reading is approximate, in both
                // directions: a handler that returns while the flush runs, after the flush took its batch, goes unnamed
                // though its acknowledgement is not flushed; one that queued its acknowledgement before the flush took
                // its batch but has not yet left its handler bookkeeping is named though its acknowledgement was
                // flushed. Both windows are narrow. The log line, the answer and the phase the unit ends in all go by
                // this one reading. Should the flush throw, the row is still held, and the unit goes back where it was
                // claimed from whatever this reads.
                inHandler = kept.keysInHandler();
                if (owner.holdsLeaseRow()) {
                    // Each message still in a handler is charged its attempt before the release, as the next owner charges
                    // nothing for a released unit. Should it throw, nothing is released, and a later request does not charge again.
                    if (inHandler.isPresent()) {
                        charge = chargeKeysInHandlerBeforeRelease(owner);
                    }
                    released = releaseLeaseRows(List.of(owner)) > 0;
                }
            } finally {
                kept.endForcedRelease(claimedFrom, owner.holdsLeaseRow(), inHandler.isPresent());
            }
            if (released) {
                return Optional.of(reportReleasedOnRequest(unit, inHandler, charge));
            }
            taken = keptUnitRelease(unit, KeptUnitRelease.Outcome.ALREADY_TAKEN, inHandler);
        }
        // Nothing released. Busy before taken: an entry that could not be claimed may still hold the row, while one found
        // taken holds nothing any more.
        if (busy != null) {
            log.info("Instance {} did not release ordered unit {} on queue {} on request: the stop that kept it is still flushing "
                             + "its acknowledgements, or another hand-back or release of it is running. Ask again",
                     instanceId, unit, queueId);
            return Optional.of(busy);
        }
        if (taken != null) {
            log.info("Instance {} found ordered unit {} on queue {}, which a stop kept, taken over under a newer fence when asked to "
                             + "release it - by another instance once this one's liveness lapsed, or under this instance id once "
                             + "this queue had stopped waiting for it past stuckHandlerTimeout; nothing released",
                     instanceId, unit, queueId);
            return Optional.of(taken);
        }
        return Optional.empty();
    }

    /**
     * The answer of {@link #releaseKeptOrderedUnit} for one kept entry of {@code unit}, naming the keys {@code inHandler}
     * read.
     */
    private KeptUnitRelease keptUnitRelease(int unit, KeptUnitRelease.Outcome outcome, Optional<OrderedShardOwner.KeysInHandler> inHandler) {
        return new KeptUnitRelease(unit, instanceId, outcome,
                                   inHandler.map(OrderedShardOwner.KeysInHandler::keys).orElse(List.of()),
                                   inHandler.map(OrderedShardOwner.KeysInHandler::longest).orElse(Duration.ZERO));
    }

    /**
     * Log what {@link #releaseKeptOrderedUnit} did with {@code unit}, released with the keys {@code inHandler} read under
     * its claim still in a handler - a WARN when there are any, naming the attempts {@code charge} made on their messages,
     * as whoever takes the unit next delivers those not dead-lettered again - and give the answer.
     */
    private KeptUnitRelease reportReleasedOnRequest(int unit, Optional<OrderedShardOwner.KeysInHandler> inHandler, OrderedShardOwner.ReleaseCharge charge) {
        var answer = keptUnitRelease(unit, KeptUnitRelease.Outcome.RELEASED, inHandler);
        inHandler.ifPresentOrElse(
                held -> log.warn("Instance {} released ordered unit {} on queue {} on request, which a stop kept while {} key(s) {} were in "
                                         + "a handler, the longest there {} ms: its acknowledgements are flushed and its lease released "
                                         + "without waiting for those handlers. {} message(s) in those handlers were charged an attempt, as a "
                                         + "takeover charges one, and {} dead-lettered at their attempt limit {}. Whoever takes the unit next "
                                         + "does so under a new fence and delivers the messages in those handlers again, bar those "
                                         + "dead-lettered, possibly while those handlers still run them; nothing they do is acknowledged "
                                         + "under the old fence. Counted in ShardOwnerMetrics.keptUnitsInHandler while one runs",
                                 instanceId, unit, queueId, held.keys().size(), held.describeKeys(), held.longest().toMillis(),
                                 charge.charged(), charge.deadLetteredKeys().size(), charge.describeDeadLetteredKeys()),
                () -> log.info("Instance {} handed back ordered unit {} on queue {} on request, which a stop kept while a key was in its "
                                       + "handler: that handler had returned, its acknowledgements are flushed and its lease released",
                               instanceId, unit, queueId));
        return answer;
    }

    /**
     * Whether the database hand-back of a run this queue has ended - flushing acknowledgements, releasing lease rows and
     * deregistering the instance id - is still running on the thread a stop gave up waiting for after
     * {@link #SHUTDOWN_BUDGET} ({@link #withinShutdownBudget}). Until it is done, that thread may still delete the
     * membership row of this queue's instance id ({@link ShardOwnedStorage#deregisterInstance(String)}), however
     * long after the stop returned. A later {@link #start()} of this queue does not lose its row to it - the hand-back
     * leaves the row to a run started since - unless that deletion was already under way when the start began and
     * outlasts the start's {@link #SHUTDOWN_BUDGET} wait ({@link #awaitDeregistrationsRunning}); then the row is gone
     * until the run's next heartbeat. Another queue registering under the same id meanwhile can, and other
     * instances then read its units as ownerless. {@link PostgresqlMessageQueue} asks this before it lets the next
     * {@code consume()} register under an ended subscription's instance id. Like {@link #keptOrderedUnitNotHandedBack()},
     * asked right after a {@code stop()} that gave up waiting for a start still running, it does not yet count the
     * hand-back that start does once its statements return.
     */
    boolean handBackStillRunning() {
        return handBacksRunning.get() > 0;
    }

    /**
     * Make this queue a lane of one {@link PostgresqlMessageQueue} subscription, whose other lane is {@code otherLane}:
     * both register under one instance id, and so share one membership row. Called on both lanes, before either starts.
     * <p>
     * The row's lifetime is the pair's, decided by the lanes' own stops: the stop of one lane deletes it only when the
     * other no longer needs it ({@link #needsMembership()}) and it kept no ordered unit itself, and otherwise leaves it to
     * that lane's stop. So the row is deleted by the lane stopping last when neither lane's stop kept an ordered unit,
     * unless a hand-back of an ordered unit an earlier stop kept is still releasing that unit's row when that lane reads
     * the other's need: that lane then leaves the row, which the other lane's stop deletes if its own read comes after
     * that hand-back has ended, and which otherwise ages out as for a kept unit.
     * When either kept one, because a key was still in its handler past {@code shedGrace}, nobody deletes it - the lane
     * that kept the unit still needs it, so the other lane's later stop leaves it too - and it ages out a lease TTL after
     * the last heartbeat. The kept unit is handed back as soon as its handler has returned ({@link #keptOrderedOwners}),
     * and becomes takeable then, or once the membership row has aged out, whichever comes first.
     * Without this, the unordered lane, which {@code PostgresqlMessageQueue} stops first, deleted the row while the
     * ordered lane still served its units, or went on to keep one, and another instance could take that unit at once and
     * start its key beside the handler still running it. The lanes, not the subscription, because each lane alone knows
     * when its stop has finished: a stop may hand its work to a start still running, and its hand-back to the database
     * may outlast its {@link #SHUTDOWN_BUDGET} on a thread the stop no longer waits for.
     * <p>
     * A queue never paired deletes the row whenever its stop keeps no ordered unit, as before. Paired or not, a stop
     * leaves the row to a run of the same queue started since ({@link #handBackWithinBudget}).
     *
     * @param otherLane the subscription's other lane, registering under the same instance id on the same queue
     */
    void shareMembershipWith(ShardOwnedQueue otherLane) {
        requireNonNull(otherLane, "No otherLane provided");
        requireTrue(otherLane != this, "A queue shares its membership row with the other lane of its subscription, not with itself");
        requireTrue(otherLane.queueId == queueId && otherLane.instanceId.equals(instanceId),
                    "The two lanes of a subscription register under one instance id on one queue");
        membershipSharedWith = otherLane;
    }

    /**
     * Whether this queue still needs its instance's membership row: from the moment a {@link #start()} installs a run
     * until that run is ended, once drained, keeping no ordered unit - by a stop, or by the undo of a failed start - and,
     * when it kept one, until a later run ends keeping nothing, with no key an earlier stop kept a unit for still in its
     * handler and no such unit that stop's budget thread has not flushed yet, nor one a hand-back is still releasing, as
     * that later run's stop finds them when it ends the run
     * ({@link #keptOrderedOwners}, {@link KeptUnit.Phase#STOP_FLUSHING}, {@link KeptUnit.Phase#HANDING_BACK}). A stop that gave up waiting for a start still running
     * ({@link #lifecycleLock}) leaves it true until that start has ended the run; an abrupt stop leaves it true, as it
     * leaves the units leased. Read by the stop of the other lane of the subscription ({@link #shareMembershipWith}),
     * and by the hand-back of each stop of this queue, before it deletes the row: a hand-back reading it after a later
     * start of this queue reads that start's need.
     * <p>
     * Also true, whatever {@link #endRun} wrote, while a hand-back or release on request of a unit a stop of this queue
     * kept is running with the unit's lease row still naming this instance ({@link #keptUnitsBeingHandedBack()}): read
     * live from the kept units, so a claim made after {@code endRun} read their phases - by the thread a key leaving its
     * handler started, a heartbeat tick still running, a start, a {@code consume()}, a release on request, or this
     * queue's own stop handing back a unit an earlier stop kept - counts from the moment it is made. Deleted while such a
     * hand-back runs, the row would make the unit read as ownerless: another instance could take it under a new fence,
     * and the hand-back's flush would be refused. A claim made after a stop's hand-back has read this, and before its
     * deletion lands, is not seen by that stop.
     */
    boolean needsMembership() {
        return runNeedsMembership || keptOrderedOwners.stream().anyMatch(KeptUnit::handBackRunningWithRowHeld);
    }

    /**
     * The owners of the units a stop of this queue kept ({@link #keptOrderedOwners}) that a hand-back or release on
     * request is running for now ({@link KeptUnit.Phase#HANDING_BACK}) and whose lease row still names this instance:
     * read live, as each claim moves its unit there ({@link KeptUnit#claimHandBack}). None of them is this caller's own
     * claim when the caller has finished its hand-backs: a hand-back leaves its unit at {@link KeptUnit.Phase#HANDED_BACK},
     * at {@link KeptUnit.Phase#FORCE_RELEASED} or back where it was claimed from.
     */
    private List<LeasedOwner> keptUnitsBeingHandedBack() {
        return keptOrderedOwners.stream().filter(KeptUnit::handBackRunningWithRowHeld).map(KeptUnit::owner).toList();
    }

    /**
     * Retire this queue for good: no {@link #start()} from now on starts it. It does not stop it: a {@link #stop()}
     * called after this ends whatever runs, a run whose start was already under way when this was called included - that
     * stop waits for the start, or hands its work to it, as for any start - and no start after that brings it back.
     * {@link PostgresqlMessageQueue} retires the lanes of an unsubscribed subscription before it stops them, so a
     * {@code start()} of the queue that still goes over them - the holder of its lifecycle lock, which an unsubscribe that
     * did not get that lock leaves to take them out - starts neither of them from then on.
     */
    void retire() {
        retired = true;
    }

    /**
     * Whether {@link #retire()} has been called.
     */
    boolean isRetired() {
        return retired;
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
     * Idempotent, and leaves this instance able to {@link #start()} again: the run this ends - its owners, wake-ups and
     * shard map - is dropped, so a restart leases afresh into a new {@link Run} rather than resurrecting owners whose
     * fences are long stale.
     * <p>
     * A {@link #start()} called on another thread while this runs waits for it to finish ({@link #lifecycleLock}): what
     * this stop drops is only ever the run it took out, but the runtime it hands back and the membership row it deletes
     * are the queue's.
     * <p>
     * The other way round, called while a {@code start()} is still doing its database work on another thread -
     * registering the instance and leasing its units, which nothing in this queue bounds - this waits for it up to the
     * 2 s {@link #SHUTDOWN_BUDGET}, and no longer. Past that, it takes the starting run out and returns: the queue is not
     * started from then on, and the start does this stop's work itself once its statements return - it adopts nothing
     * more, drains and ends the units it adopted before, releases what it leased, hands back the runtime and deregisters
     * the instance ({@link #lifecycleLock}).
     * The drain and release below, and their own budget, then run on the start's thread instead. A stop on a thread that
     * is interrupted, or is interrupted while it waits, stops waiting at once and does the same, and returns with the
     * thread's interrupt flag still set.
     * <p>
     * A stop that gave up waiting so returns <b>before</b> its work is done: the units the start leased are not released
     * yet, the instance is still registered, and the start still holds the runtime it borrowed and {@link #lifecycleLock}.
     * {@link #isStarted()} is false and {@link #shardsHeld()} 0 meanwhile; the WARN this stop logs is what says the start
     * is still running. Two things follow. A {@link #start()} called afterwards waits for that start to return, as long as
     * its statements take. And closing the {@code DataSource} before that start returns leaves it releasing and
     * deregistering against a closed pool: both fail into WARNs, and the units stay unserved until this instance's
     * membership row is older than the lease TTL, as after a crash. Stopping a {@code ShardRuntime} passed to the builder
     * before then costs the drain instead: the release and the deregistration go through the queue's own
     * {@code DataSource} and still succeed, but the start no longer waits for handlers still running on units it adopted
     * before this stop, so their acknowledgements may be lost and those messages redelivered.
     */
    @Override
    public void stop() {
        boolean locked;
        try {
            locked = lifecycleLock.tryLock(SHUTDOWN_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Restored, so the caller still sees it; a drain and hand-back below then return without waiting, as they
            // would for any interrupted stop.
            Thread.currentThread().interrupt();
            stopWithoutWaiting(StopKind.GRACEFUL, true);
            return;
        }
        if (!locked) {
            stopWithoutWaiting(StopKind.GRACEFUL, false);
            return;
        }
        try {
            stopRun();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Take the current run out and end it ({@link #endRun}). Under {@link #lifecycleLock}.
     */
    private void stopRun() {
        // Only stop() and stopAbruptly() take a run out - they and a start() that fails, its own - and they do it one at a
        // time (lifecycleLock). A stop that did not get the lock takes out only a run whose start is still running
        // (Phase.STARTING), which holds the lock (stopWithoutWaiting), so the run read here is still the current one inside
        // the monitor: one started, or one a start that escaped with a critical error left installed (START_ABORTED).
        var run = currentRun.get();
        if (run == null) {
            return;
        }
        synchronized (run) {
            currentRun.compareAndSet(run, null);
            run.advanceTo(Phase.ENDED);
        }
        endRun(run, true);
    }

    /**
     * End a run that has been taken out of {@link #currentRun}: drain, end and release its owners, hand back the runtime,
     * and - when {@code deregister} - delete this instance's membership row, unless an ordered unit is kept, this queue has
     * started again by then, or the other lane of the subscription still needs the row ({@link #handBackWithinBudget}).
     * What {@link #stop()} does once it has taken its run out, and what a start does for a run a stop took out while it
     * was starting, or that its own failure ended ({@link #endRunOfStart}). Under {@link #lifecycleLock}.
     */
    private void endRun(Run run, boolean deregister) {
        // The run's `owners` is read under its monitor, which every adoption into it takes (adoptIntoRun), and the run is
        // already out of currentRun: a unit a tick or a start leases from then on is refused and undone by whoever leased
        // it (abandonAcquisition), never adopted. So `ending` is every owner this run will ever have listed, but those a
        // heartbeat tick still running releases or drops itself meanwhile, and counts as released itself. Everything below
        // works on this run's state alone.
        //
        // Snapshot before releaseRuntime clears anything: the leases still have to be handed back
        // afterwards, and the owner list is what says which ones this instance holds - every owner
        // whose row is still this instance's (holdsLeaseRow), the dead pumps' included: an owner its
        // pump ended as it died (ENDED_ROW_HELD) still holds its row until it is handed back
        // (handBackUnitsOnDeadPumps), which a stop before the next heartbeat does here.
        List<LeasedOwner> ending;
        List<LeasedOwner> held;
        synchronized (run) {
            ending = List.copyOf(run.owners);
            held = ending.stream().filter(LeasedOwner::holdsLeaseRow).toList();
        }
        run.cancelHeartbeat();
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
        releaseRuntime(run);
        // End the owners, or a shared runtime goes on serving them. Its pumps outlive this queue - in
        // Spring they run until the context destroys the runtime bean, after every graceful-shutdown
        // phase - and cancelling the heartbeat above did nothing to stop them: they kept reading and
        // dispatching for a queue that had been stopped, until the liveness gate noticed the missing
        // heartbeat a lease TTL later and every owner of every stopped queue logged that it was
        // pausing. An ended owner is skipped and dropped by its pump on the next pass. Ended keeping
        // its row (ENDED_ROW_HELD) until releaseHeldLeases below has released it: a heartbeat tick
        // still running counts it as this instance's, so its acquire loop cannot take the unit again.
        held.forEach(this::endOnStop);
        // An ordered unit with a key still in a handler is NOT released now: a successor taking it now
        // could start that key while it runs here, which is reordering. Another instance can take it once this
        // instance's liveness lapses - a lease TTL - which is also why the instance must stay
        // registered. It is handed back once the handler has returned - its acknowledgements flushed, its row released
        // (handBackKeptUnit) - and a later run of this queue leaves it alone until then (keptOrderedOwners). An unordered
        // one is released regardless; at-least-once covers its redelivery.
        // Each published below in STOP_FLUSHING, which no hand-back claims until the budget thread has flushed it
        // (handBackWithinBudget), or until the notStarted callback below lets it out when that thread could not be
        // started: a heartbeat tick, a restart or a consume() may try one before then.
        var kept    = stillInHandler.stream().filter(owner -> "ordered".equals(owner.lane())).toList();
        var keptNow = kept.stream().map(KeptUnit::new).toList();
        // Units an earlier stop kept and that are not handed back yet, in three kinds, each owner's in-flight count and
        // phase read once, so none falls between them. Those whose key is still in its handler: this run did not take them
        // (keptUnitsNotHandedBack), and their rows still name this instance, so they keep it registered as this run's own
        // kept units do. Those whose handler has returned since but that another thread is still working on, so this stop
        // cannot claim their hand-back (busy): the budget thread of the stop that kept them has not let them out of
        // STOP_FLUSHING yet, or a hand-back or release on request of them is running (HANDING_BACK) - the key leaving its
        // handler, a heartbeat tick, a start, an earlier stop's budget thread or a consume(). Their rows still name this
        // instance until that thread has flushed and released them, so they keep it registered too - deleted, the row
        // would make the unit read as ownerless while that thread still flushes it, and another instance taking it then
        // has that flush refused. Those whose handler has returned since, neither busy nor handed back, whose hand-back
        // did not run or failed: handed back on the budget thread below, as this run's own rows are released there, and
        // listed still, so a later tick or start retries a hand-back that fails there. One another thread claims before
        // that budget thread does is busy by then: the budget thread reads their phase again before it deregisters
        // (handBackWithinBudget).
        // And a fourth kind, listed still but keeping nothing registered: those with a key in its handler that were given up
        // on - the key there longer than stuckHandlerTimeout, or the unit kept that long with the flush of the stop that
        // kept it not returned - which nothing waits for (reviewStuckKeptUnits) - a run of this queue may have taken
        // the unit over already, and another instance may once this one deregisters - and those released on request with
        // their key still in the handler (releaseKeptOrderedUnit, FORCE_RELEASED), whose row is released already.
        reviewStuckKeptUnits();
        pruneReleasedPastStuckHandler();
        var keptEarlier     = new ArrayList<KeptUnit>();
        var givenUpEarlier  = new ArrayList<KeptUnit>();
        var busyEarlier     = new ArrayList<KeptUnit>();
        var returnedEarlier = new ArrayList<KeptUnit>();
        for (var earlier : keptOrderedOwners) {
            var phase = earlier.phase();
            if (earlier.owner().inFlight() > 0) {
                // At HANDING_BACK too: a release on request may claim the unit with its key still in the handler, and
                // until that release has run the row names this instance, so the unit keeps it registered.
                (phase.nothingWaitsForHandler() ? givenUpEarlier : keptEarlier).add(earlier);
            } else if (earlier.owner().holdsLeaseRow()) {
                (phase == KeptUnit.Phase.STOP_FLUSHING || phase == KeptUnit.Phase.HANDING_BACK ? busyEarlier : returnedEarlier).add(earlier);
            }
        }
        var stillKept = new ArrayList<KeptUnit>(keptEarlier);
        stillKept.addAll(givenUpEarlier);
        stillKept.addAll(busyEarlier);
        stillKept.addAll(returnedEarlier);
        stillKept.addAll(keptNow);
        keptOrderedOwners = List.copyOf(stillKept);
        // After the write: the run is out of currentRun already, and the metrics may have dropped this queue's source,
        // stopped and counting nothing, before these units were kept.
        countKeptUnitsInHandlerInMetrics();
        // This run's own need for the membership row ends here, unless a unit is kept with its key still in a handler, or
        // one an earlier stop kept is still busy - at STOP_FLUSHING, or with a hand-back of it running (HANDING_BACK):
        // written before the hand-back reads the other lane's (handBackWithinBudget), so of two lanes ending at once at
        // least one reads the other's as ended.
        runNeedsMembership = !keptEarlier.isEmpty() || !busyEarlier.isEmpty() || !kept.isEmpty();
        var toRelease = held.stream().filter(owner -> !kept.contains(owner)).toList();
        var keptEarlierOwners = keptEarlier.stream().map(KeptUnit::owner).toList();
        var busyEarlierOwners = busyEarlier.stream().map(KeptUnit::owner).toList();
        // Nothing against the database for a run with no lease row to hand back, no earlier kept unit to hand back and no
        // deregistration owed: a start that failed before it adopted anything.
        if (!held.isEmpty() || !returnedEarlier.isEmpty() || deregister) {
            // Counted until the work is done, not until the budget runs out: past it the stop goes on without the thread,
            // which may still deregister the instance id (handBackStillRunning).
            handBacksRunning.incrementAndGet();
            withinShutdownBudget(() -> {
                try {
                    handBackWithinBudget(serving, toRelease, kept, keptEarlierOwners, busyEarlierOwners, returnedEarlier, keptNow,
                                         deregister);
                } finally {
                    handBacksRunning.decrementAndGet();
                }
            }, () -> {
                // The thread did not start, so nothing of the work above runs: no flush of the kept owners to wait for, and
                // nothing else would let this run's kept units out of STOP_FLUSHING - a heartbeat tick, a start or a
                // consume() hands them back instead.
                handBacksRunning.decrementAndGet();
                keptNow.forEach(unit -> unit.moveFrom(KeptUnit.Phase.STOP_FLUSHING, KeptUnit.Phase.AWAITING_HAND_BACK));
            });
        }
        // This queue drops every owner the ended run listed, released, left to lapse, or found taken (RELEASED, so not in
        // `held`): a shared runtime keeps running, and would otherwise count those on a dead pump in shardsOnDeadPumps until
        // it stops. Every owner, not only `held`: a dead pump's owner the lease check found taken is uncounted only by the
        // rebalance's removeIf, which a stop or a failed rebalance can come before. A no-op for an owner no dead pump counts,
        // or one a heartbeat tick already handed back.
        if (shardRuntime != null) {
            ending.forEach(shardRuntime::handedBack);
        }
        // The ended run's own lists, so emptying them cannot touch a run started since. A tick of this run still running
        // after its heartbeat was cancelled then finds nothing of it left to check or hand back.
        run.owners.clear();
        run.ownedShards.clear();
        // Every owner this run listed leaves the queue here, so each one whose release nobody has counted yet is counted
        // now: a unit the budget thread released (releaseHeldLeases counts nothing), an ordered unit kept because a key was
        // still in its handler, a unit whose release failed or ran out of the budget, and one found taken that no rebalance
        // has dropped yet. This instance holds none of them any more, as far as the queue knows - a kept unit's row names it
        // until the unit is handed back (or the instance's liveness lapses) - and nothing would report them later. A release still running on a heartbeat tick
        // counts nothing for an owner counted here (countRelease), and nothing here for one it counted first.
        var givenUp = new ArrayList<Integer>();
        for (var owner : ending) {
            if (countRelease(run, owner)) {
                givenUp.add(owner.shard());
            }
        }
        // Last, once the stop's own work is done: an observer that throws (QueueObserver says why that is not caught)
        // escapes the stop with nothing of it left undone. On the stop's own thread, never inside handBackWithinBudget:
        // told there, between the release and the deregistration, an observer that threw skipped the deregistration.
        reportReleases(givenUp);
    }

    /**
     * The database half of {@link #endRun}, on the budget thread ({@link #withinShutdownBudget}): flush what the serving
     * owners still hold, then let each unit this run keeps ({@code keptNow}) out of {@link KeptUnit.Phase#STOP_FLUSHING}
     * and have it handed back once its key leaves its handler, release
     * the rows of {@code toRelease}, hand back the units an earlier stop kept whose handler has returned since
     * ({@code returnedEarlier}, {@link #handBackKeptUnit}), and deregister this instance
     * ({@link ShardOwnedStorage#deregisterInstance(String)}) when {@code deregister}, no ordered unit is {@code kept} by
     * this run nor still {@code keptEarlier} by an earlier one with its key in a handler nor {@code busyEarlier} (kept by
     * an earlier one whose handler has returned but whose budget thread has not flushed it yet,
     * {@link KeptUnit.Phase#STOP_FLUSHING}, or whose hand-back or release on request another thread is still running,
     * {@link KeptUnit.Phase#HANDING_BACK}: this stop cannot claim it, and its row names this instance until that thread
     * has released it) as {@link #endRun} found them, nor a kept unit another thread has claimed since, found at
     * {@link KeptUnit.Phase#HANDING_BACK} with its row held once this thread's own hand-backs are done
     * ({@link #keptUnitsBeingHandedBack()}), this queue no longer needs the row - no run of it started since, and no such
     * claim made since that read ({@link #needsMembership()}) -, and the other lane of the {@code PostgresqlMessageQueue}
     * subscription, which shares the membership row ({@link #shareMembershipWith}), no longer needs it either: a claim of
     * a unit that lane kept counts there whenever it was made before this thread reads that lane's need, whatever that
     * lane's stop wrote. Only a claim made after those reads, before the deletion lands, is not waited for. When that
     * lane does, its own stop deletes the row later, or leaves it to age out if it keeps an ordered unit; when a later run
     * of this queue does, its own stop.
     */
    // PMD.CloseResource: otherLane is the subscription's other lane, owned and closed by PostgresqlMessageQueue; this only
    // asks it whether it still needs the membership row, and must never close it.
    @SuppressWarnings("PMD.CloseResource")
    private void handBackWithinBudget(List<LeasedOwner> serving, List<LeasedOwner> toRelease, List<LeasedOwner> kept,
                                      List<LeasedOwner> keptEarlier, List<LeasedOwner> busyEarlier, List<KeptUnit> returnedEarlier,
                                      List<KeptUnit> keptNow, boolean deregister) {
        try {
            // What the pumps would have flushed had they gone on serving these owners. Valid still:
            // releasing a lease does not bump its fence, and the leases go only after this. Nothing to
            // do for a runtime this queue closed itself - its pumps flushed on the way out. Not for an
            // owner whose pump died: its state is not trusted (handBackUnitsOnDeadPumps).
            flushAcknowledgements(serving);
        } finally {
            // Each unit this run keeps is handed back as its last key leaves the handler, on a thread of its own, whether
            // or not this queue runs by then - at once when the key has left already. Only once this thread has flushed
            // the kept owners above, whether that flush returned or threw, also when it outlasts the budget: until then
            // every unit stands at STOP_FLUSHING, which no hand-back claims - a heartbeat tick's, a restart's or a
            // consume()'s included, which may run meanwhile once the stop has given up waiting for this thread. A
            // hand-back flushing and releasing one of them while this flush still ran made this flush, refused, count a
            // lease lost. Unless this flush outlasts stuckHandlerTimeout: a review may have given such a unit up by
            // then (reviewStuckKeptUnits), which leaves the move below doing nothing, and the hand-back registered after it
            // claims the unit from where the review left it.
            for (var unit : keptNow) {
                unit.moveFrom(KeptUnit.Phase.STOP_FLUSHING, KeptUnit.Phase.AWAITING_HAND_BACK);
                if (unit.owner() instanceof OrderedShardOwner ordered) {
                    ordered.whenNoKeyInHandler(() -> handBackKeptUnitOnItsOwnThread(unit));
                }
            }
        }
        releaseHeldLeases(toRelease);
        // Before the deregistration below, as this run's own rows are released before it. One whose hand-back fails here
        // does not keep the instance registered, as a release that fails above does not.
        returnedEarlier.forEach(this::handBackKeptUnit);
        if (!kept.isEmpty()) {
            log.warn("Instance {} is keeping {} ordered unit(s) [{}] on queue {}: a key was still in a handler "
                             + "{} after stop began. Each is handed back - its acknowledgements flushed, its lease released - "
                             + "once its handler has returned, whether or not this queue runs again; until then another "
                             + "instance can take it only once this one's liveness lapses ({} ms), so no successor starts a "
                             + "key that may still be running here - unless the key has been in its handler longer than "
                             + "stuckHandlerTimeout ({}), when a later run of this queue, or a PostgresqlMessageQueue subscription "
                             + "registering under this instance id, takes the unit over - or unless it is released on request "
                             + "before that (MessageQueue.releaseKeptOrderedUnit(\"{}\", unit), admin API POST "
                             + ".../ordered-units/{unit}/release-kept?instanceId={}, on this process), which accepts that the messages in "
                             + "those handlers are delivered again, possibly beside them. Raise shedGrace if "
                             + "handlers routinely outlast it",
                     instanceId, kept.size(), summarise(kept.stream().map(LeasedOwner::shard).toList()), queueId,
                     Duration.ofNanos(activeSettings.shedGraceNanos()), leaseTtlMillis, activeSettings.stuckHandlerTimeout(),
                     instanceId, instanceId);
            return;
        }
        if (!deregister) {
            return;
        }
        // Deleted, the row would make those units read as ownerless at once, and another instance could start the key
        // beside the handler an earlier stop kept the unit for.
        if (!keptEarlier.isEmpty()) {
            log.info("Instance {} stays registered on queue {}: an earlier stop kept {} ordered unit(s) [{}] whose key was still "
                             + "in a handler when this stop ended its run, and their leases name this instance until each is "
                             + "handed back once its handler has "
                             + "returned, or this instance's liveness lapses ({} ms)",
                     instanceId, queueId, keptEarlier.size(), summarise(keptEarlier.stream().map(LeasedOwner::shard).toList()),
                     leaseTtlMillis);
            return;
        }
        // Nor while another thread is still flushing or releasing a unit an earlier stop kept: the budget thread of the stop
        // that kept it, before which no hand-back claims the unit (KeptUnit.Phase.STOP_FLUSHING), or a hand-back or
        // release on request of it running (KeptUnit.Phase.HANDING_BACK), whose claim leaves this stop none to make.
        // Its row still names this instance until that thread has released it, and deleted, the
        // instance's row would make the unit read as ownerless - taken at once, that thread's flush is refused.
        // endRun read busyEarlier before this thread began; a kept unit another thread claimed since - the thread its key
        // leaving the handler started, a heartbeat tick still running, a start, a consume() or a release on request - is not
        // among them, and one of returnedEarlier refused this thread's claim above, where handBackKeptUnit returned without a
        // word. Read again here, live, after those hand-backs (keptUnitsBeingHandedBack): such a unit stands at HANDING_BACK
        // with its row held, which this thread's own hand-back never leaves behind (it ends at HANDED_BACK, or back where it
        // was claimed from). A claim made after this read is read again below, with this queue's other needs for the row
        // (needsMembership), and the other lane of the subscription reads it there too, whatever endRun wrote.
        var busy = new LinkedHashSet<>(busyEarlier);
        busy.addAll(keptUnitsBeingHandedBack());
        if (!busy.isEmpty()) {
            log.info("Instance {} stays registered on queue {}: an earlier stop kept {} ordered unit(s) [{}] that the flush of "
                             + "that stop, or another thread's hand-back or release on request of them, was still working on "
                             + "when this stop ended its run or came to deregister the instance; their leases name this instance "
                             + "until a hand-back or release on request has released them, or this instance's liveness "
                             + "lapses ({} ms)",
                     instanceId, queueId, busy.size(), summarise(busy.stream().map(LeasedOwner::shard).toList()),
                     leaseTtlMillis);
            return;
        }
        // The membership row is the instance's, and a PostgresqlMessageQueue subscription's two lanes share it. Deleted
        // while the other lane still serves its units, or has kept an ordered unit whose key is still in a handler, those
        // read as ownerless at once, and another instance may start that key beside the handler. Read after this run wrote
        // its own need (endRun), as the other lane's stop reads this one's after writing its own: of two lanes ending at
        // once, at least one sees the other's ended and deletes the row - unless that one kept a unit, which it writes
        // first. Not from the database: a lease row of the other lane naming the instance may equally be a unit whose
        // release failed, which nobody serves, and keeping the row for it only held the other instances at a stale fair
        // share until the row aged out.
        //
        // Counted before this queue's own need is read below, as start() writes that need before it reads this count:
        // of a start and this hand-back at once, either this reads the start's need and leaves the row, or the start
        // waits for this deletion, up to SHUTDOWN_BUDGET, before it registers the instance (awaitDeregistrationsRunning);
        // a deletion still running then is not waited for further.
        deregistrationsRunning.incrementAndGet();
        try {
            // This queue's own need first: the stop that ended this run set it false before this thread began, and only a
            // later run sets it again - its start, or its stop keeping an ordered unit. The stop gives up waiting for this
            // thread after SHUTDOWN_BUDGET, so this can run after the queue has started again under the same instance id
            // and registered it; deleted then, the row the new run needs is gone, and other instances read its units as
            // ownerless until its next heartbeat. It also reads, live, a hand-back of a unit a stop of this queue kept that
            // another thread claimed since the read above. A claim made after this read, and before the deletion below
            // lands, is not seen, here nor by the other lane's read: the row is then deleted while that hand-back runs.
            if (needsMembership()) {
                log.debug("Instance {} leaves its membership row on queue {}: a run of this queue started since the stop whose "
                                  + "hand-back this is needs it - it is running, its stop is still draining, or that stop kept an "
                                  + "ordered unit - or a hand-back or release on request of an ordered unit a stop of this queue "
                                  + "kept is running and has not released that unit's lease yet",
                          instanceId, queueId);
                return;
            }
            var otherLane = membershipSharedWith;
            if (otherLane != null && otherLane.needsMembership()) {
                log.debug("Instance {} leaves its membership row on queue {} to the {} lane of the same subscription, which still "
                                  + "needs it: it is running, its stop is still draining, that stop kept an ordered unit, or a "
                                  + "hand-back or release on request of an ordered unit a stop of that lane kept is running and "
                                  + "has not released that unit's lease yet",
                          instanceId, queueId, otherLane.activeLane);
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
        } finally {
            // Counted down under the monitor awaitDeregistrationsRunning re-reads the count under, so the change and its
            // notify are one step to a waiter.
            synchronized (deregistrationFinished) {
                deregistrationsRunning.decrementAndGet();
                deregistrationFinished.notifyAll();
            }
        }
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
     * moves it on but the hand-back of a kept unit once its handler has returned ({@link #handBackKeptUnit}), whose release
     * moves it to {@code RELEASED}: the lease check that would find the unit taken runs on the heartbeat this stop
     * cancelled. It
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
     * <p>
     * Package-private for {@link PostgresqlMessageQueue#stop()}, which waits no longer than this for a
     * {@code consume()} or {@code start()} still starting its consumers.
     */
    static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(2);

    /**
     * Run the stop path's database work with a deadline, on a thread that can be abandoned.
     * <p>
     * Abandoning is safe precisely because the work is best-effort: whatever it had not done when the
     * budget ran out is something the cluster already recovers from on its own. The thread is a
     * daemon, so an attempt still blocked inside the pool cannot hold the JVM up either.
     *
     * @param notStarted run when the thread could not be created or started - {@code work} then never runs - before what
     *                   that threw is let out
     */
    private void withinShutdownBudget(Runnable work, Runnable notStarted) {
        Thread thread;
        try {
            thread = new Thread(work, "shard-queue-stop-" + queueId);
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException | Error e) {
            notStarted.run();
            throw e;
        }
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
            // Releases the rows only. Counting and telling the observer is endRun's, after this budget thread: told here,
            // an observer that threw ended this thread before the deregistration, and its failure reached neither the log
            // nor the caller of stop().
            releaseLeaseRows(stillHeld);
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

    /**
     * The instance id this queue registers and leases under - for a lane of a {@code PostgresqlMessageQueue}
     * subscription, the subscription's, which may carry a suffix.
     */
    String instanceId() {
        return instanceId;
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

    /**
     * An ordered owner a stop kept ({@link #keptOrderedOwners}), with where it stands in being handed back
     * ({@link #handBackKeptUnit}) or given up on ({@link #reviewStuckKeptUnits()}) - one {@link Phase}, moved only along
     * that enum's diagram ({@link #moveFrom}, {@link #claimHandBack}) - and when it may next WARN that the unit is held up.
     * Whether a key of the owner is in a handler ({@link LeasedOwner#inFlight()}) and whether its lease row still names
     * this instance ({@link LeasedOwner#holdsLeaseRow()}) are the owner's own state, asked of it live, never copied here.
     */
    static final class KeptUnit {
        private final LeasedOwner            owner;
        private final AtomicReference<Phase> phase         = new AtomicReference<>(Phase.STOP_FLUSHING);
        /**
         * The {@link ErrorWindow} of {@link #reviewStuckKeptUnits()}'s held-up WARN for this unit: at most once a minute.
         */
        private final AtomicLong             heldUpWarning = new AtomicLong(ErrorWindow.NEVER_TAKEN);
        /**
         * When the stop kept the unit ({@link System#nanoTime()}): when it entered {@link Phase#STOP_FLUSHING}.
         */
        private final long                   keptAtNanos   = System.nanoTime();

        /**
         * A unit the stop ending a run keeps, in {@link Phase#STOP_FLUSHING} until that stop's budget thread has flushed it,
         * or could not be started, or until it has been kept longer than {@code stuckHandlerTimeout}
         * ({@link #reviewStuckKeptUnits()}).
         */
        KeptUnit(LeasedOwner owner) {
            this.owner = requireNonNull(owner, "No owner provided");
        }

        LeasedOwner owner() {
            return owner;
        }

        /**
         * How long ago the stop kept the unit: how long it has stood at {@link Phase#STOP_FLUSHING} while it still does.
         */
        Duration keptFor() {
            return Duration.ofNanos(System.nanoTime() - keptAtNanos);
        }

        /**
         * Where the unit stands now.
         */
        Phase phase() {
            return phase.get();
        }

        /**
         * Move the unit from {@code from} to {@code to}, if it stands at {@code from} and {@link Phase}'s diagram has an
         * edge from there to {@code to} ({@link Phase#canAdvanceTo}); else leave it where it is. The rule lives there and
         * nowhere else, and naming where the move starts makes each move a compare-and-set: two callers asking at once -
         * a review giving the unit up and a hand-back claiming it - cannot both move it.
         *
         * @return whether this call moved the unit
         */
        boolean moveFrom(Phase from, Phase to) {
            requireNonNull(from, "No phase to move from provided");
            requireNonNull(to, "No phase to move to provided");
            return from.canAdvanceTo(to) && phase.compareAndSet(from, to);
        }

        /**
         * Claim the one hand-back of this unit that may run: move it to {@link Phase#HANDING_BACK} from whichever phase it
         * stands in that has an edge there.
         *
         * @return the phase the claim moved the unit from, which the hand-back moves it back to should it leave the row
         * held ({@link #endHandBack}); {@code null} when no hand-back may run now - while the unit stands at
         * {@link Phase#STOP_FLUSHING} (the stop's budget thread is still flushing the owner, and no review has given the unit
         * up past {@code stuckHandlerTimeout}: {@link #reviewStuckKeptUnits()}), another hand-back runs, or the unit is
         * handed back
         */
        Phase claimHandBack() {
            while (true) {
                var from = phase.get();
                if (!from.canAdvanceTo(Phase.HANDING_BACK)) {
                    return null;
                }
                if (phase.compareAndSet(from, Phase.HANDING_BACK)) {
                    return from;
                }
            }
        }

        /**
         * End the hand-back this caller claimed ({@link #claimHandBack}): {@link Phase#HANDED_BACK} when the owner no longer
         * holds its lease row - released, or found taken - and otherwise back to {@code claimedFrom}, for a retry.
         */
        void endHandBack(Phase claimedFrom, boolean rowHeld) {
            moveFrom(Phase.HANDING_BACK, rowHeld ? claimedFrom : Phase.HANDED_BACK);
        }

        /**
         * End the release on request this caller claimed ({@link #claimHandBack}, {@link #releaseKeptOrderedUnit}): back to
         * {@code claimedFrom} when the owner still holds its lease row - the release failed - and otherwise
         * {@link Phase#FORCE_RELEASED} when {@code keyInHandler}, {@link Phase#HANDED_BACK} when not.
         *
         * @param keyInHandler whether a key of the owner was in a handler when the release read it, under its claim and
         *                     after its flush - the reading its log line and its answer name too, so all three agree. Not
         *                     read again here: a handler returning after that reading had its acknowledgement dropped all
         *                     the same.
         */
        void endForcedRelease(Phase claimedFrom, boolean rowHeld, boolean keyInHandler) {
            moveFrom(Phase.HANDING_BACK, rowHeld ? claimedFrom : keyInHandler ? Phase.FORCE_RELEASED : Phase.HANDED_BACK);
        }

        /**
         * Whether nothing waits for the owner's handler any more ({@link Phase#nothingWaitsForHandler()}): given up on past
         * {@code stuckHandlerTimeout}, or released on request.
         */
        boolean nothingWaitsForHandler() {
            return phase.get().nothingWaitsForHandler();
        }

        /**
         * Whether the unit has been given up on: its key has been in its handler longer than {@code stuckHandlerTimeout},
         * or the stop that kept it had not flushed it that long after keeping it ({@link Phase#GIVEN_UP},
         * {@link Phase#TAKEOVER_ASKED}).
         */
        boolean isGivenUp() {
            return phase.get().isGivenUp();
        }

        /**
         * The owner's keys in a handler, and how long the longest has been there; empty when none is, or for an owner that
         * is not an {@link OrderedShardOwner} - only ordered owners are kept.
         */
        private Optional<OrderedShardOwner.KeysInHandler> keysInHandler() {
            return owner instanceof OrderedShardOwner ordered ? ordered.keysInHandler() : Optional.empty();
        }

        /**
         * Whether the held-up WARN for this unit may be logged now - the first time, or a minute after the last - claiming
         * it when it may, so two callers at once log it once.
         */
        private boolean warningDue() {
            return ErrorWindow.take(heldUpWarning, System.nanoTime());
        }

        /**
         * Whether a key of the owner is in a handler, or its lease row still names this instance:
         * {@link #keptOrderedUnitNotHandedBack()}. From the moment its last key leaves the handler until a hand-back has
         * released the row, this is true, so nothing reads the unit as handed back before it is - nor after a hand-back
         * that failed, which a retry may still complete. Asked of the owner, not of the phase: the stop's own flush can find
         * the unit taken, which moves the owner's row and not the phase.
         */
        private boolean notHandedBack() {
            return owner.inFlight() > 0 || owner.holdsLeaseRow();
        }

        /**
         * Whether a hand-back or release on request of the unit is running ({@link Phase#HANDING_BACK}) and its lease row
         * still names this instance: what keeps the instance's membership row needed for it
         * ({@link #needsMembership()}). The phase first, then the row, each read live.
         */
        private boolean handBackRunningWithRowHeld() {
            return phase.get() == Phase.HANDING_BACK && owner.holdsLeaseRow();
        }

        /**
         * Where a {@link KeptUnit} stands. Moved only along the edges below ({@link KeptUnit#moveFrom},
         * {@link KeptUnit#claimHandBack}, by {@link #canAdvanceTo}); whether a key is in a handler, and whether the lease row
         * still names this instance, stay the owner's to answer.
         * <pre>
         *   STOP_FLUSHING --&gt; AWAITING_HAND_BACK --&gt; GIVEN_UP --&gt; TAKEOVER_ASKED
         *                            |                  |              |
         *                            +------------------+--------------+--&gt; HANDING_BACK --&gt; HANDED_BACK
         *                                                                                |
         *                                                                                +--&gt; FORCE_RELEASED
         *
         *   STOP_FLUSHING --&gt; GIVEN_UP too, once kept longer than stuckHandlerTimeout with the stop's flush not returned.
         *   HANDING_BACK goes back to the phase it was claimed from when the hand-back leaves the row held.
         * </pre>
         */
        enum Phase {
            /**
             * Kept by the stop ending a run ({@link #endRun}), whose budget thread ({@link #handBackWithinBudget}) has not
             * flushed the owners that run served yet - this one among them - and may still be doing so after the stop gave
             * up waiting for it. Nothing hands the unit back, nor gives it up, until that flush has passed: a hand-back
             * flushing and releasing the owner beside it made that flush's delete find the row released, and the flush,
             * refused, counted a lease lost. Left by that thread, in a {@code finally}, whether the flush returned or
             * threw, just before it has the owner hand the unit back as its last key leaves the handler
             * ({@link OrderedShardOwner#whenNoKeyInHandler}) - or, when that thread could not be created or started
             * ({@link #withinShutdownBudget}'s {@code notStarted}), by {@link #endRun} on the stop's own thread, with no
             * flush run and no {@code whenNoKeyInHandler} registered: a heartbeat tick, a start or a {@code consume()}
             * hands it back then. One exception bounds the wait: that flush has no timeout of its own and may never
             * return - on a connection that hangs with no {@code socketTimeout} - so a unit kept longer than
             * {@code stuckHandlerTimeout} and still here is given up ({@link #GIVEN_UP}, {@link #reviewStuckKeptUnits()}),
             * whatever its handler does; that flush, should it return once the unit has been released or taken over, is
             * refused then.
             */
            STOP_FLUSHING,
            /**
             * Waiting for its key to leave the handler, or for a retry of a hand-back that left the row held: a heartbeat
             * tick, a start, a later stop, the key leaving its handler, or a {@link PostgresqlMessageQueue}
             * {@code consume()} ({@link #handBackKeptUnits()}). No run of this queue acquires it meanwhile
             * ({@link #keptUnitsNotHandedBack()}).
             */
            AWAITING_HAND_BACK,
            /**
             * One hand-back of it is running ({@link #handBackKeptUnit}) - a key leaving its handler, a heartbeat tick, a
             * start, a stop's budget thread and a {@code consume()} may all try at once, and only the one that moved it here
             * runs. Waited for as {@link #AWAITING_HAND_BACK} is, whether it was claimed from there or from a phase given
             * up on: its handler has returned, and the hand-back is releasing the row.
             */
            HANDING_BACK,
            /**
             * A hand-back found its owner holding no lease row any more: released by it, or found taken - by another
             * instance, or under this instance id once the unit was given up on. Nothing is left to do for it.
             */
            HANDED_BACK,
            /**
             * Its key has been in its handler longer than {@code stuckHandlerTimeout}, or it has been kept that long still
             * at {@link #STOP_FLUSHING} ({@link #reviewStuckKeptUnits()}): nothing waits for it any more, and the ordered
             * acquire loop takes it over, under a new fence, before any free unit ({@link #keptUnitsGivenUp()}). Still
             * handed back should no key of it be in a handler before it is taken over.
             */
            GIVEN_UP,
            /**
             * Given up on, and an acquire of this queue - a heartbeat tick's ({@link #acquireFreeOrderedShards}) or a
             * start's ({@link #leaseAtStart}) - has asked for it since, whether the grant was given or refused: the one
             * takeover {@link #keptUnitsGivenUp()} asks for. From then on it is any other unit to the acquire loops.
             */
            TAKEOVER_ASKED,
            /**
             * Released on request ({@link #releaseKeptOrderedUnit}, {@code MessageQueue.releaseKeptOrderedUnit}) with a key
             * still in its handler, or found taken by that request: its lease row no longer names this instance under the
             * fence it was kept under, and nothing waits for that handler any more - not a later stop, which stays
             * registered for nothing of it, nor a {@link PostgresqlMessageQueue} keeping an ended subscription's instance id
             * out of use ({@link #keptOrderedUnitAwaited()}). Whoever takes the unit next delivers that key's message again,
             * possibly while the handler still runs it. Moved to by the request that claimed the hand-back alone; a request
             * that finds no key in a handler ends at {@link #HANDED_BACK}, as any hand-back does.
             */
            FORCE_RELEASED;

            /**
             * Whether the diagram above has an edge from this phase to {@code target}. Staying where it is is not an edge.
             */
            boolean canAdvanceTo(Phase target) {
                return switch (this) {
                    case STOP_FLUSHING -> target == AWAITING_HAND_BACK || target == GIVEN_UP;
                    case AWAITING_HAND_BACK -> target == HANDING_BACK || target == GIVEN_UP;
                    case HANDING_BACK -> target == HANDED_BACK || target == FORCE_RELEASED || target == AWAITING_HAND_BACK || target == GIVEN_UP
                                         || target == TAKEOVER_ASKED;
                    case HANDED_BACK, FORCE_RELEASED -> false;
                    case GIVEN_UP -> target == TAKEOVER_ASKED || target == HANDING_BACK;
                    case TAKEOVER_ASKED -> target == HANDING_BACK;
                };
            }

            /**
             * Whether the unit has been given up on past {@code stuckHandlerTimeout}: {@link #GIVEN_UP} or
             * {@link #TAKEOVER_ASKED}.
             */
            boolean isGivenUp() {
                return this == GIVEN_UP || this == TAKEOVER_ASKED;
            }

            /**
             * Whether nothing waits for the unit's handler any more: given up on past {@code stuckHandlerTimeout}
             * ({@link #isGivenUp()}), or released on request ({@link #FORCE_RELEASED}).
             */
            boolean nothingWaitsForHandler() {
                return isGivenUp() || this == FORCE_RELEASED;
            }
        }
    }

    /**
     * Everything one run of this queue owns: from the {@link #start()} that installs it in {@link #currentRun} to the
     * {@link #stop()} or {@link #stopAbruptly()} that takes it out. Its identity is the run's: two runs under the same
     * instance id - a stop and a start in between - are two objects, so nothing done for one can be mistaken for the
     * other.
     * <p>
     * <b>The protocol.</b>
     * <ul>
     *   <li>{@code start()}, {@code stop()} and {@code stopAbruptly()} run one at a time under {@link #lifecycleLock}.
     *       Only {@code start()} installs a run, only the two stops take one out - they and a {@code start()} that
     *       fails, its own. One exception: a stop that does not get the lock - {@code stop()} within the
     *       {@link #SHUTDOWN_BUDGET} or on an interrupted thread, {@code stopAbruptly()} at once - takes out a run whose
     *       start, holding the lock, is still running, and records on it the stop the start owes ({@link #phase}:
     *       {@link Phase#STARTING} to {@code STOP_OWED_*}). The start then ends the run itself, under the lock
     *       ({@link #endRunOfStart}). So a stop under the lock always finds the current run its own to take out, and a start
     *       finds its run current or taken out by the one stop it owes.</li>
     *   <li>Whether a start is still running for a run is its {@link #phase}, one field moved only along
     *       {@link Phase}'s edges ({@link #advanceTo}) under the run's monitor, and never inferred from anything else. {@link Phase#STARTING} is the only phase a stop without
     *       the lock hands its work over from. It is not enough that the start has not scheduled the heartbeat: a start
     *       that escapes with a critical error leaves its run installed with no heartbeat and lets go of the lock
     *       ({@link Phase#START_ABORTED}), and a stop that handed its work to it would hand it to nobody. Four separate
     *       signals - membership in {@link #currentRun}, a start-finished flag, the stop owed, and whether the lock was
     *       held - used to stand for the phase, and read the run a critical-error start left behind as one whose start
     *       was still running.</li>
     *   <li>A run is taken out under its own monitor, and its {@link #owners} are read under it once it is out. A unit
     *       joins a run only through {@link #adoptIntoRun}, under the same monitor and only while the run is still
     *       current - a start-up's units as much as a heartbeat tick's. So every owner a run ever lists is in the list
     *       whoever ends it reads ({@link #endRun}), or was never listed and is released again by whoever leased it
     *       ({@link #abandonAcquisition}).</li>
     *   <li>A run's heartbeat is scheduled under its monitor and only while it is still current
     *       ({@link #startHeartbeatUnlessStopped}), so a run a stop took out during its start never gets one: it would
     *       have no stop left to cancel it.</li>
     *   <li>Each heartbeat is scheduled for one run and every tick works on that run's lists alone. A tick still running
     *       after its run has ended - cancelling the heartbeat does not stop it - adopts nothing, and reaches no owner
     *       of a run started since.</li>
     *   <li>A stop works on the run it took out and on nothing of a later one. Of what is the queue's rather than a
     *       run's, the runtime and the membership row are what still need {@code lifecycleLock}: a stop hands back and
     *       deregisters what a start may have just borrowed and written. A stop without the lock touches neither, and
     *       leaves both to the start it took the run from.</li>
     *   <li>{@link #unreleasedAcquisitions} is the queue's too, and outlives every run, but takes no lock: it is a
     *       concurrent map that any run's heartbeat ticks write ({@link #abandonAcquisition}) and retry
     *       ({@link #retryUnreleasedAcquisitions}), and a tick must never wait behind a stop's drain. A retry is safe
     *       against a later run because the release is fenced.</li>
     * </ul>
     * The monitor is held only for short in-memory sections, never across a stop's drain or its database work, and the
     * {@code QueueObserver} is never called under it ({@link #adoptIntoRun} says why).
     */
    static final class Run {
        /**
         * The owners of the units this run leases, in the order it adopted them: what {@link #stop()} ends and releases,
         * and what the run's heartbeat checks the leases of and computes its fair share from.
         */
        final List<LeasedOwner>        owners              = new CopyOnWriteArrayList<>();
        /**
         * The unordered lane's units this run owns, to their owner: the lookup that makes local hand-off possible.
         */
        final Map<Integer, ShardOwner> ownedShards         = new ConcurrentHashMap<>();
        /**
         * The owners whose acquisition this run has counted ({@link ShardOwnerMetrics#shardsAcquired}, by
         * {@link #countAcquisition}) and whose release it has not. A release is counted, and told to the
         * {@code QueueObserver}, only by whoever removes the owner from here ({@link #countRelease}, then {@link #reportReleases}),
         * so it is reported once, and only for an owner whose acquisition was counted: a heartbeat tick's fair-share shed or
         * dead-pump hand-back and {@link #stop()} can release the same owner at once, and both used to report it.
         * <p>
         * And every owner leaves it with its release counted, however this instance loses the unit: released by this
         * queue, found taken by another instance (counted by the {@link #rebalance} that drops the owner), or given up by
         * {@link #stop()} without a release - an ordered unit kept because a key was still in its handler, or a release
         * that failed or ran out of the shutdown budget. So {@code shardsAcquired - shardsReleased} is the number of units
         * this queue holds, and is zero once {@code stop()} has run ({@link #stopAbruptly()}, which stands in for a crash,
         * counts nothing). A unit found taken, or kept at stop, used to leave without a release counted, and the difference
         * grew by one each time, for good.
         * <p>
         * Membership does not mean the observer has been told of the acquisition. The count is taken under the run's
         * monitor; the observer is told by the caller afterwards, outside it - at start-up once the heartbeat is scheduled,
         * on a heartbeat tick in a {@code finally} around its acquire loop. So a {@link #stop()} can tell the observer of a
         * release just before the tick tells it of the acquisition: every acquisition is still told, but not always first
         * ({@link #adoptIntoRun} says why the observer is kept out of the monitor).
         */
        final Set<LeasedOwner>         acquisitionReported = ConcurrentHashMap.newKeySet();
        /**
         * This run's slot on the shared heartbeat scheduler; {@code null} until its start has scheduled it.
         */
        volatile ScheduledFuture<?>    heartbeat;
        /**
         * Where this run stands in its lifecycle ({@link Phase}). Read and moved only under the run's monitor, and only
         * along the edges of {@code Phase}'s diagram ({@link #advanceTo}).
         */
        private Phase                  phase               = Phase.STARTING;

        /**
         * Where this run stands. Under the run's monitor.
         */
        Phase phase() {
            return phase;
        }

        /**
         * Move this run to {@code target} if {@code Phase}'s diagram has an edge from where it stands to there
         * ({@link Phase#canAdvanceTo}), else leave it where it is. Under the run's monitor. The rule lives here and
         * nowhere else: {@link Phase#STARTING} moves to any other phase, every other phase only to {@link Phase#ENDED},
         * and nothing leaves {@code ENDED}. So a started or aborted run is never moved into a stop-owed phase - that would
         * hand a stop's work to a start no longer running for it - and a stop owed is never exchanged for another.
         *
         * @return the phase the run was in before this call, so the caller can tell whether it moved the run and from where
         */
        Phase advanceTo(Phase target) {
            requireNonNull(target, "No target phase provided");
            var previous = phase;
            if (previous.canAdvanceTo(target)) {
                phase = target;
            }
            return previous;
        }

        /**
         * The stop this run's start owes, when a stop took the run out while the start was still running
         * ({@link Phase#STOP_OWED_GRACEFUL}, {@link Phase#STOP_OWED_ABRUPT}); {@code null} in every other phase. Under the
         * run's monitor.
         */
        StopKind owedStop() {
            return switch (phase) {
                case STOP_OWED_GRACEFUL -> StopKind.GRACEFUL;
                case STOP_OWED_ABRUPT -> StopKind.ABRUPT;
                default -> null;
            };
        }

        /**
         * Cancel this run's heartbeat, without interrupting a tick already running: that tick finishes on this run's
         * lists, and adopts nothing into it.
         */
        void cancelHeartbeat() {
            var scheduled = heartbeat;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    /**
     * Where a {@link Run} stands, from the {@link #start()} that installs it to whoever ends it. A run moves only along the
     * edges below, under its monitor ({@link Run#advanceTo}, by {@link #canAdvanceTo}), and each stop that finds it
     * current reads from the phase whether a start is still running for it - nothing else does that inferring.
     * <pre>
     *   STARTING -+-&gt; STARTED ------------+
     *             +-&gt; START_ABORTED ------+
     *             +-&gt; STOP_OWED_GRACEFUL -+-&gt; ENDED
     *             +-&gt; STOP_OWED_ABRUPT ---+
     *             +-----------------------+   (a start that failed, undoing its own run)
     * </pre>
     */
    enum Phase {
        /**
         * Installed by a {@link #start()} that is still doing its database work, holding {@link #lifecycleLock} - the one
         * phase in which a start is known to be running for the run. A stop that did not get the lock takes the run out
         * from here, and leaves the stop's work to the start ({@link #stopWithoutWaiting}).
         */
        STARTING,
        /**
         * The start has scheduled the run's heartbeat ({@link #startHeartbeatUnlessStopped}); all it may still do is tell
         * the observer of its acquisitions, still holding {@link #lifecycleLock}. A stop that did not get the lock waits for
         * it after all.
         */
        STARTED,
        /**
         * The start escaped while still {@link #STARTING} - a critical error ({@link Exceptions#isCriticalError}), which
         * undoes nothing - and left the run installed, with no heartbeat, and let go of {@link #lifecycleLock}. No start
         * is running for it, so a stop, with the lock or without, takes it out under the lock and ends it as it ends a
         * started run.
         */
        START_ABORTED,
        /**
         * A {@link #stop()} that did not get {@link #lifecycleLock} took the run out while it was {@link #STARTING}; the
         * start does that stop's work ({@link #endRunOfStart}). A critical error escaping the start leaves the run here,
         * with that work undone, as it leaves the start's own.
         */
        STOP_OWED_GRACEFUL,
        /**
         * {@link #STOP_OWED_GRACEFUL}, for a {@link #stopAbruptly()}.
         */
        STOP_OWED_ABRUPT,
        /**
         * Taken out of {@link #currentRun} by whoever ends it: a stop under {@link #lifecycleLock}, a start doing the work
         * of the stop it owes, or a start that failed undoing its own run.
         */
        ENDED;

        /**
         * Whether the diagram above has an edge from this phase to {@code target}: from {@link #STARTING} to every other
         * phase, from {@link #STARTED}, {@link #START_ABORTED} and either {@code STOP_OWED_*} to {@link #ENDED} alone,
         * and from {@code ENDED} to none. Staying where it is is not an edge.
         */
        boolean canAdvanceTo(Phase target) {
            return switch (this) {
                case STARTING -> target != STARTING;
                case STARTED, START_ABORTED, STOP_OWED_GRACEFUL, STOP_OWED_ABRUPT -> target == ENDED;
                case ENDED -> false;
            };
        }
    }

    /**
     * Which stop a start owes when a stop took its run out without {@link #lifecycleLock} ({@link Run#owedStop}).
     */
    private enum StopKind {
        /**
         * {@link #stop()} or {@link #close()}: the run is ended as a stop ends one, the instance deregistered.
         */
        GRACEFUL,
        /**
         * {@link #stopAbruptly()}: only the runtime is handed back.
         */
        ABRUPT
    }
}
