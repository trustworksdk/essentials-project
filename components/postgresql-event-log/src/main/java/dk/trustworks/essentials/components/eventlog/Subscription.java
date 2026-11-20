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
package dk.trustworks.essentials.components.eventlog;

import org.slf4j.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The engine's subscription behind a {@link SubscriptionHandle}: package-private, started by
 * {@link EventLog#subscribe(SubscriptionDefinition, DeliveryHandler)} or, with test seams, the package-private spec path.
 * <p>
 * Two independent strategies:
 * <ul>
 *     <li>The <b>consistency mode</b> decides which received rows may be handed on. {@link ConsistencyMode#FRESH} hands
 *     on every received row once, deduplicated by a {@link HandledSet}; the resume point is
 *     {@code P + 1}, {@code P = min(received watermark, lowest received-but-unhandled - 1)}.
 *     {@link ConsistencyMode#CONTIGUOUS} holds rows above its received watermark back ({@link HeldBack})
 *     and hands on only the received prefix, in strict {@code global_order}; the resume point is the applied prefix
 *     {@code A + 1}.</li>
 *     <li>The <b>progress model</b> decides how rows are applied and where the resume point is written.
 *     {@link ProgressModel#PERIODIC} calls a per-event {@link PeriodicHandler} and saves at most every
 *     {@link EventLogSettings#progressSaveInterval()}; {@link ProgressModel#IN_HANDLER_TRANSACTION} hands pages of at most
 *     {@link EventLogSettings#pageSize()} events to a {@link PageHandler} in a transaction on a connection of the engine's
 *     {@code DataSource}, whose last statement moves the resume point - guarded by the reset epoch and by the value last
 *     committed - so a page and its progress commit together or not at all (I12; see docs/design/event-log.md
 * §11).</li>
 * </ul>
 * The lifecycle: the tail reader is the {@link EventLog}'s shared {@link ReaderLoop}; this
 * subscription holds no connection except an open page's, and runs on its own virtual delivery thread:
 * <ol>
 *     <li><b>Catch-up</b> {@code (P, W]} below the reader's watermark {@code W}, paged, each read on a borrowed
 *     connection returned before any row is applied. While {@code W} is unknown (F4) it <b>waits for the watermark</b>;
 *     {@code FRESH} only one backstop interval, then attaches under the unknown watermark, bridges and hands the gaps it
 *     found to the reader, unless there are more than {@link EventLogSettings#holeRangeThreshold()}.</li>
 *     <li><b>Attach</b>: a request to the loop, answered at its quiescent point with the cursor {@code C}, the watermark
 *     and the unresolved holes in {@code (W, C]}.</li>
 *     <li><b>Bridge</b>: {@code FRESH} reads {@code (max(P, highest handled), C]} at once, each page re-reading the attach
 *     holes and the handled set's gaps below its lower bound. {@code CONTIGUOUS} defers it: each time the
 *     received watermark rises while {@code R < C} it reads {@code (R, min(W_rcv, C)]}, which is final.</li>
 *     <li><b>Attached</b>: batches from the inbox. An overflow or a reader restart closes the inbox; the subscription
 *     <b>detaches</b> between pages, never during one, and catches up again. {@code CONTIGUOUS} keeps its pending
 *     rows across a detach.</li>
 * </ol>
 * Failures: an I/O failure of the engine (any failure outside the handler, or the page connection lost)
 * retries the same page indefinitely with a doubling backoff. What the handler throws on a live connection is the
 * handler's failure, I/O-classified or not. A handler failure of a page of several events shrinks the page
 * ({@link StepDown}); an event failing alone is retried {@link EventLogSettings#retryAttempts()} times with a doubling
 * backoff, then parked in {@code el_parked_events} - in the page transaction that moves past it (in-transaction), or in
 * autocommit before it counts as handled (periodic). A {@link ParkEventException} parks at once. The later events of a
 * parked event's aggregate are parked behind it ({@link ParkedEvents}) until {@link #retryParked(long)} or
 * {@link #discardParked(long)}; every other aggregate continues. An interrupted handler is not handled.
 * {@link #stop()} saves once more.
 */
final class Subscription {
    private static final Logger log = LoggerFactory.getLogger(Subscription.class);
    /** How long {@link #start()} waits for the delivery thread to start */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);
    /** How long {@link #stop()} waits for the delivery thread to end; package-private for tests */
    volatile Duration stopJoinTimeout = Duration.ofSeconds(30);


    /** Per-event TRACE lines of this subscriber, on a logger of its own */
    private final DeliveryTrace                          trace;

    /** Where the delivery thread is in the lifecycle; for tests and diagnostics */
    enum Phase {
        STARTING,
        /** Exclusive: waiting for the lease keeper to claim the lease; not joined to the reader */
        STANDBY,
        /** Exclusive: past the local deadline without a confirmed renewal; no handler call, no page */
        SUSPENDED,
        CATCHING_UP,
        WAITING_FOR_WATERMARK,
        ATTACHING,
        BRIDGING,
        ATTACHED,
        STOPPED
    }

    private final ProgressStore.Key              key;
    private final long                           initialResumeFrom;
    private final ConsistencyMode                mode;
    private final ProgressModel                  model;
    private final PeriodicHandler                handler;
    private final PageHandler                    pageHandler;
    private final TestSeams                      seams;
    /** Where page transactions come from; parked retries of a periodic subscription use {@link #engineSource} */
    private final PageTransactionSource          pageSource;
    private final EnginePageTransactions         engineSource;
    /** Per-subscription retry, backoff and page size */
    private final ErrorSettings                  errors;
    /** The stored filter digest, {@code null} without a filter */
    private final String                         filterDigest;
    /** A new row starts at the head */
    private final boolean                        fromHead;
    // ---- status ----
    private volatile Throwable                   lastError;
    private volatile int                         heldAggregates;
    private final ReaderLoop                     loop;
    private final EventLogStorage.TailStatements statements;
    private final TailReader.RowMapper           mapper;
    private final ProgressStore                  progress;
    private final EventLogSettings               settings;
    private final EventLogObserver               observer;
    // ---- filters ----
    private final EventTypeFilter                filter;
    /** Whether rows are filtered at all: on event type, on tenant, or both */
    private final boolean                        filtered;
    /** Whether rows are filtered on event type */
    private final boolean                        typeFiltered;
    /** The tenant filter, {@code null} for every tenant */
    private final String                         tenant;
    /** The filtered statements of this filter's shape, {@code null} unfiltered */
    private final EventLogStorage.FilterStatements filterStatements;
    private final CatchUpContext                 catchUp;
    private final CatchUpPermits                 permits;
    private final CatchUpWindow                  window;
    /** Mutant {@link WatermarkMutant#PERMIT_HELD_THROUGH_PAGE}: a permit kept until the rows read are applied */
    private boolean                              permitHeld;

    private final CountDownLatch                 started = new CountDownLatch(1);
    private volatile boolean                     running;
    private volatile boolean                     crashed;
    private volatile Throwable                   startFailure;
    private volatile Thread                      thread;
    private volatile ReaderLoop.ActiveType       type;
    private volatile Inbox                       inbox;
    private volatile Phase                       phase   = Phase.STARTING;
    private volatile long                        lastSaved;
    // ---- for tests ----
    private volatile Connection                  openPageConnection;
    private volatile long                        iterations;
    private volatile int                         heldBackHighWater;
    private volatile long                        bridgeRowsDroppedAsHandled;

    // ---- delivery thread only ----
    /** {@code W_rcv}: the watermark received - a catch-up page's ceiling, the bridge's progress (at most {@code W}), a batch's */
    private volatile long                        receivedWatermark;
    /**
     * The received rows that may be handed on and are not yet applied, in {@code global_order} order. {@code FRESH}:
     * the unhandled rows of the rows being worked through. {@code CONTIGUOUS}: every existing order in {@code (A, R]}
     */
    private final ArrayDeque<HeldBack.Entry>     pending = new ArrayDeque<>();
    private long                                 resetEpoch;
    private long                                 lastSaveNanos;
    private Long                                 repositionTo;
    private boolean                              waitReported;
    /** {@code FRESH}: when the wait under an unknown watermark began, else {@code null} */
    private Long                                 unknownSinceNanos;
    /** {@code FRESH}: a hand-off found too many gaps; wait for the watermark (F4) until it is known */
    private boolean                              handOffFellBack;
    // FRESH
    private HandledSet                           handled;
    private long                                 highestHandled = -1;
    // CONTIGUOUS
    /** {@code R}: every existing order in {@code (A, R]} is pending, {@code A <= R <= W_rcv} */
    private long                                 receivedPrefix;
    private final HeldBack                       heldBack = new HeldBack();
    /** The attach cursor {@code C} while the deferred bridge has not reached it, else {@code null} */
    private Long                                 attachCursor;
    /** The page that last rolled back, until a page commits; mutant {@link WatermarkMutant#DETACH_POSITION_PAST_OPEN_PAGE} */
    private List<HeldBack.Entry>                 lastFailedPage;
    /** {@code A} before the last page; mutant {@link WatermarkMutant#REREAD_WITHOUT_APPLIED_DROP} */
    private Long                                 appliedBeforeLastPage;
    // Failures
    private final StepDown                       stepDown;
    private final ParkedEvents                   parked;
    /** Events decided for parking, by global order: the next page that holds them parks them */
    private final Map<Long, ParkedEvents.Row>    toPark = new HashMap<>();
    /** One-shot limit of the next page: the events before one a handler asked to park commit in a page of their own */
    private int                                  pageCut;
    /** The event failing alone, and its attempts so far */
    private long                                 failingOrder = -1;
    private int                                  failingAttempts;
    /** Consecutive I/O failures of pages, for the backoff */
    private int                                  ioFailures;
    private final Queue<ParkedRequest>           parkedRequests = new ConcurrentLinkedQueue<>();
    /**
     * Cleared once, right before the delivery thread fails what is queued for the last time. Not {@link #isRunning()}: the
     * thread stays alive for the final save and leave after that drain, and a request queued then would never complete
     */
    private volatile boolean                     acceptsParkedRequests = true;
    /** When the next parked check is due (0: at once), and how many ran; delivery thread */
    private long                                 nextParkedCheckNanos;
    private volatile long                        parkedChecks;
    // ---- exclusive subscriptions; both null for a non-exclusive one ----
    private final Lease                          lease;
    private final LeaseKeeper                    keeper;
    /** The fence (and owner) of the claim this incarnation delivers under; {@code null} while standby. Delivery thread */
    private volatile ProgressStore.Fence         ownedFence;
    /** The lifecycle ended because the lease was lost. Delivery thread */
    private boolean                              leaseLost;

    Subscription(SubscriptionSpec spec, ReaderLoop loop, EventLogStorage.TailStatements statements, TailReader.RowMapper mapper, ProgressStore progress,
                 EventLogSettings settings, EventLogObserver observer, CatchUpContext catchUp) {
        this(spec, loop, statements, mapper, progress, settings, observer, catchUp, null, null);
    }

    /**
     * @param lease  the exclusive subscription's lease, {@code null} for a non-exclusive one
     * @param keeper the event log's lease keeper, with {@code lease}
     */
    Subscription(SubscriptionSpec spec, ReaderLoop loop, EventLogStorage.TailStatements statements, TailReader.RowMapper mapper, ProgressStore progress,
                 EventLogSettings settings, EventLogObserver observer, CatchUpContext catchUp, Lease lease, LeaseKeeper keeper) {
        if (spec.exclusive() != (lease != null) || (lease == null) != (keeper == null)) {
            throw new IllegalArgumentException("An exclusive subscription needs a lease and a keeper, a non-exclusive one neither");
        }
        this.lease = lease;
        this.keeper = keeper;
        this.key = spec.key();
        this.trace = new DeliveryTrace(key.subscriber(), key.aggregateType());
        this.filter = spec.filter();
        this.tenant = spec.tenant();
        this.typeFiltered = !spec.filter().matchesAll();
        this.filtered = typeFiltered || tenant != null;
        this.filterStatements = filtered ? statements.filter(typeFiltered, tenant != null) : null;
        this.catchUp = requireNonNull(catchUp, "No catchUp provided");
        this.permits = spec.seams().has(PERMIT_PER_EVENT_LOG) ? catchUp.perEventLog() : catchUp.shared();
        this.window = new CatchUpWindow(settings.catchUpScanWindow(), settings.catchUpScanWindowMax(), settings.catchUpPageSize());
        this.initialResumeFrom = spec.initialResumeFrom();
        this.mode = spec.mode();
        this.model = spec.model();
        this.handler = spec.handler();
        this.pageHandler = spec.pageHandler();
        this.seams = spec.seams();
        this.loop = requireNonNull(loop, "No loop provided");
        this.statements = requireNonNull(statements, "No statements provided");
        this.mapper = requireNonNull(mapper, "No mapper provided");
        this.progress = requireNonNull(progress, "No progress provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.observer = requireNonNull(observer, "No observer provided");
        this.errors = spec.errors() != null ? spec.errors() : ErrorSettings.defaults(settings);
        this.engineSource = EnginePageTransactions.on(loop.dataSource());
        this.pageSource = EnginePageTransactions.isEngine(spec.source()) ? engineSource : spec.source();
        this.filterDigest = spec.filterDigest();
        this.fromHead = spec.fromHead();
        this.stepDown = new StepDown(errors.pageSize(), seams.has(GROW_INSIDE_SUSPECT_RANGE));
        this.parked = new ParkedEvents(key, loop.dataSource());
    }

    /**
     * What a subscription's catch-up reads share with its event log: the type-id registry, the
     * database's permits (and, for mutant {@link WatermarkMutant#PERMIT_PER_EVENT_LOG}, the event log's own), and whether
     * the aggregate type's {@code (event_type, global_order)} index is valid
     */
    record CatchUpContext(EventTypeRegistry eventTypes, CatchUpPermits shared, CatchUpPermits perEventLog, java.util.function.BooleanSupplier indexValid,
                          java.util.function.BooleanSupplier tenantIndexValid) {
        CatchUpContext {
            requireNonNull(tenantIndexValid, "No tenantIndexValid provided");
            requireNonNull(eventTypes, "No eventTypes provided");
            requireNonNull(shared, "No shared provided");
            requireNonNull(perEventLog, "No perEventLog provided");
            requireNonNull(indexValid, "No indexValid provided");
        }
    }

    /**
     * What a subscription is: its progress key, where a new one starts, its consistency mode and progress model, the
     * handler that model calls ({@code handler} for {@link ProgressModel#PERIODIC}, {@code pageHandler} for
     * {@link ProgressModel#IN_HANDLER_TRANSACTION}) and its test seams; the page transaction source
     * ({@code null} for the engine's), the error settings ({@code null} for the event log's defaults), the filter digest
     * stored on the row and whether a new row starts at the head ({@code initialResumeFrom} is then ignored)
     */
    record SubscriptionSpec(ProgressStore.Key key, long initialResumeFrom, ConsistencyMode mode, ProgressModel model, PeriodicHandler handler, PageHandler pageHandler,
                            TestSeams seams, EventTypeFilter filter, boolean exclusive, PageTransactionSource source, ErrorSettings errors, String filterDigest,
                            boolean fromHead, String tenant) {
        SubscriptionSpec {
            requireNonNull(key, "No key provided");
            requireNonNull(filter, "No filter provided");
            requireNonNull(mode, "No mode provided");
            requireNonNull(model, "No model provided");
            requireNonNull(seams, "No seams provided");
            if (model == ProgressModel.PERIODIC) {
                requireNonNull(handler, "No handler provided");
                if (!EnginePageTransactions.isEngine(source)) {
                    throw new IllegalArgumentException("A periodic subscription has no page transactions");
                }
            } else {
                requireNonNull(pageHandler, "No pageHandler provided");
            }
            if (initialResumeFrom < 1) {
                throw new IllegalArgumentException("initialResumeFrom must be >= 1");
            }
            // The two kinds never share a key
            if (exclusive && !key.instanceName().isEmpty()) {
                throw new IllegalArgumentException("An exclusive subscription has the instance name '', not '" + key.instanceName() + "'");
            }
            if (!exclusive && key.instanceName().isBlank()) {
                throw new IllegalArgumentException("A non-exclusive subscription needs a non-blank instance name");
            }
        }

        SubscriptionSpec(ProgressStore.Key key, long initialResumeFrom, ConsistencyMode mode, ProgressModel model, PeriodicHandler handler, PageHandler pageHandler,
                         TestSeams seams, EventTypeFilter filter, boolean exclusive) {
            this(key, initialResumeFrom, mode, model, handler, pageHandler, seams, filter, exclusive, null, null, SubscriptionDefinition.filterDigest(filter, Optional.empty()),
                 false, null);
        }

        SubscriptionSpec(ProgressStore.Key key, long initialResumeFrom, ConsistencyMode mode, ProgressModel model, PeriodicHandler handler, PageHandler pageHandler,
                         TestSeams seams, EventTypeFilter filter) {
            this(key, initialResumeFrom, mode, model, handler, pageHandler, seams, filter, false);
        }

        /** The basic subscription: {@code FRESH}, periodic */
        SubscriptionSpec(ProgressStore.Key key, long initialResumeFrom, PeriodicHandler handler, TestSeams seams) {
            this(key, initialResumeFrom, ConsistencyMode.FRESH, ProgressModel.PERIODIC, handler, null, seams, EventTypeFilter.all());
        }

        /** @return this spec with the event-type filter {@code filter} */
        SubscriptionSpec withFilter(EventTypeFilter filter) {
            return new SubscriptionSpec(key, initialResumeFrom, mode, model, handler, pageHandler, seams, filter, exclusive, source, errors,
                                        SubscriptionDefinition.filterDigest(filter, Optional.ofNullable(tenant)), fromHead, tenant);
        }

        /** @return this spec with the tenant filter {@code tenant}; {@code null} for every tenant */
        SubscriptionSpec withTenant(String tenant) {
            return new SubscriptionSpec(key, initialResumeFrom, mode, model, handler, pageHandler, seams, filter, exclusive, source, errors,
                                        SubscriptionDefinition.filterDigest(filter, Optional.ofNullable(tenant)), fromHead, tenant);
        }

        /** @return this spec with test seams {@code seams} */
        SubscriptionSpec withSeams(TestSeams seams) {
            return new SubscriptionSpec(key, initialResumeFrom, mode, model, handler, pageHandler, seams, filter, exclusive, source, errors, filterDigest, fromHead, tenant);
        }

        /** @return this spec with error settings {@code errors} */
        SubscriptionSpec withErrors(ErrorSettings errors) {
            return new SubscriptionSpec(key, initialResumeFrom, mode, model, handler, pageHandler, seams, filter, exclusive, source, errors, filterDigest, fromHead, tenant);
        }

        /** @return this spec as an exclusive subscription, keyed by instance name {@code ""} */
        SubscriptionSpec asExclusive() {
            return new SubscriptionSpec(new ProgressStore.Key(key.subscriber(), key.aggregateType(), ""), initialResumeFrom, mode, model, handler, pageHandler, seams, filter,
                                        true, source, errors, filterDigest, fromHead, tenant);
        }

        static SubscriptionSpec periodic(ProgressStore.Key key, long initialResumeFrom, ConsistencyMode mode, PeriodicHandler handler, TestSeams seams) {
            return new SubscriptionSpec(key, initialResumeFrom, mode, ProgressModel.PERIODIC, handler, null, seams, EventTypeFilter.all());
        }

        static SubscriptionSpec inTransaction(ProgressStore.Key key, long initialResumeFrom, ConsistencyMode mode, PageHandler pageHandler, TestSeams seams) {
            return new SubscriptionSpec(key, initialResumeFrom, mode, ProgressModel.IN_HANDLER_TRANSACTION, null, pageHandler, seams, EventTypeFilter.all());
        }
    }

    void start() {
        running = true;
        thread = Thread.ofVirtual().name("eventlog-" + key.aggregateType() + "-" + key.subscriber()).start(this::run);
        try {
            if (!started.await(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                // The caller is told the subscribe failed and never gets a handle: the thread must not deliver on its own
                halt();
                throw new IllegalStateException("Subscription " + key + " did not start within " + START_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            halt();
            throw new IllegalStateException("Interrupted while starting " + key, e);
        }
        if (startFailure != null) {
            running = false;
            throw new IllegalStateException("Subscription " + key + " failed to start", startFailure);
        }
    }

    /**
     * Stop delivering, detach, and save the resume point once more. Idempotent.
     */
    public void stop() {
        halt();
    }

    /**
     * Test-only: stop as a crash would - no further save.
     */
    void crash() {
        crashed = true;
        halt();
    }

    private void halt() {
        running = false;
        var t = thread;
        if (t == null || t == Thread.currentThread()) {
            // A handler stopping its own subscription: the thread ends once the handler returns; joining itself would wait out the timeout
            return;
        }
        t.interrupt();
        try {
            if (!t.join(stopJoinTimeout)) {
                log.warn("Subscription {} on {}: the delivery thread did not end within {} of stop() (a handler that ignores interrupts?); " +
                                 "isRunning() stays true until it does", key.subscriber(), key.aggregateType(), stopJoinTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return whether the delivery thread is alive - also after {@link #stop()} returned while a handler that ignores
     * interrupts still runs, so the admin's {@code deleteRetiredInstance} and a re-subscribe on the same key see it
     */
    public boolean isRunning() {
        var t = thread;
        return t != null && t.isAlive();
    }

    public String subscriber() {
        return key.subscriber();
    }

    public String aggregateType() {
        return key.aggregateType();
    }

    /**
     * Have the aggregate type's reader read now instead of at its next backstop tick (later phases' wake-up tiers
     * call the same per-type wake)
     */
    public void wakeUp() {
        var t = type;
        if (t != null) {
            loop.wake(t);
        }
    }

    /** @return where the delivery thread is; for tests */
    Phase phase() {
        return phase;
    }

    ConsistencyMode mode() {
        return mode;
    }

    /**
     * @return whether the subscription has worked through everything it can: waiting for the watermark, or attached
     * with an empty inbox it waits on (a {@code CONTIGUOUS} one may still hold rows back); for tests
     */
    boolean isIdle() {
        if (phase == Phase.WAITING_FOR_WATERMARK) {
            return true;
        }
        var current = inbox;
        return phase == Phase.ATTACHED && current != null && current.consumerIdle();
    }

    /** @return the inbox while attaching, bridging or attached, else {@code null}; for tests */
    Inbox inbox() {
        return inbox;
    }

    /**
     * The last saved resume point, for tests
     */
    long lastSaved() {
        return lastSaved;
    }

    /** @return the open page transaction's connection, left open by a {@link TestSeams.Crash}; for tests */
    Connection openPageConnection() {
        return openPageConnection;
    }

    /** @return how many times the attached loop went round; for tests that wait for the delivery thread to look again */
    long iterations() {
        return iterations;
    }

    /** @return the most rows {@code CONTIGUOUS} held back at once; for tests */
    int heldBackHighWater() {
        return heldBackHighWater;
    }

    /** @return the {@code FRESH} bridge rows dropped because they were handled already; for tests */
    long bridgeRowsDroppedAsHandled() {
        return bridgeRowsDroppedAsHandled;
    }

    /** A failure: kept for {@link #lastError()} and reported to the observer */
    private void reportError(Throwable error) {
        lastError = error;
        observer.onError(key.subscriber(), key.aggregateType(), error);
    }

    /** The held set changed: kept for {@link #heldAggregates()} and reported to the observer */
    private void heldChanged() {
        heldAggregates = parked.heldCount();
        observer.onHeldAggregates(key.subscriber(), key.aggregateType(), heldAggregates);
    }

    /** @return the last failure, or {@code null}; for the status */
    Throwable lastError() {
        return lastError;
    }

    /** @return the aggregates held behind a parked head; for the status */
    int heldAggregates() {
        return heldAggregates;
    }

    /** @return the received watermark; for the status */
    long receivedWatermark() {
        return receivedWatermark;
    }

    /** @return the progress model; for the status */
    ProgressModel model() {
        return model;
    }

    /** @return the shared reader's active type while joined, else {@code null}; for the status */
    ReaderLoop.ActiveType activeType() {
        return type;
    }

    /** @return the fence this incarnation delivers under, or {@code null}; for the status */
    ProgressStore.Fence ownedFence() {
        return ownedFence;
    }

    private boolean contiguous() {
        return mode == ConsistencyMode.CONTIGUOUS;
    }

    private void run() {
        try {
            try {
                if (lease == null) {
                    var row = progress.start(key, false, mode.name(), model.name(), initialResumeFrom, fromHead ? AggregateTypeTables.of(key.aggregateType()).eventTable() : null, filterDigest);
                    resetEpoch = row.resetEpoch();
                    lastSaved = row.resumeFrom();
                    lastSaveNanos = System.nanoTime();
                    positionAt(row.resumeFrom());
                    loadParked();
                    type = loop.join(key.aggregateType(), statements, mapper, seams);
                } else {
                    // Never flip a stored row's kind; a standby never writes the row
                    var row = progress.read(key);
                    if (row.isPresent() && !row.get().exclusive()) {
                        throw new IllegalStateException("Subscription " + key.subscriber() + " on " + key.aggregateType() + " (instance '') is stored as non-exclusive and " +
                                                                "cannot start as exclusive");
                    }
                    lease.rowSeen(row.isPresent());
                    phase = Phase.STANDBY;
                    keeper.register(lease);
                }
            } catch (Throwable e) {
                startFailure = e;
                return;
            } finally {
                started.countDown();
            }
            if (lease == null) {
                log.info("Subscription {} on {} (instance '{}', {}, {}) started from {}", key.subscriber(), key.aggregateType(), key.instanceName(), mode, model,
                         resumePoint());
                lifecycle();
            } else {
                ownership();
            }
        } finally {
            phase = Phase.STOPPED;
            if (permitHeld) {
                releasePermit(true);
            }
            acceptsParkedRequests = false;
            failParkedRequests();
            try {
                seams.hooks().beforeFinalSave(key.subscriber());
            } catch (Exception e) {
                log.debug("beforeFinalSave hook of {} failed", key, e);
            }
            var current = inbox;
            if (current != null) {
                current.close(Inbox.CloseReason.STOPPED);
            }
            if (!crashed && startFailure == null) {
                Thread.interrupted();
                try {
                    if (lease == null) {
                        saveProgressNow();
                    } else {
                        releaseLease();
                    }
                } catch (Exception e) {
                    log.warn("Final progress save of {} failed", key, e);
                }
            }
            try {
                if (type != null) {
                    loop.leave(type, current);
                }
            } finally {
                if (lease != null) {
                    lease.close();
                    keeper.unregister(lease);
                }
                observer.onStopped(key.subscriber(), key.aggregateType());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ownership of an exclusive subscription
    // ---------------------------------------------------------------------------------------------------------------

    /** Thrown on the delivery thread when the lease is lost (or, {@code fatal}, the row is gone): unwinds to {@link #ownership()} */
    static final class LeaseLostException extends RuntimeException {
        final boolean fatal;

        LeaseLostException(boolean fatal) {
            super(fatal ? "The progress row of an exclusive subscription is gone" : "The lease was lost", null, false, false);
            this.fatal = fatal;
        }
    }

    /**
     * Await ownership, deliver while owner, and on a loss leave the reader and await it again
     */
    private void ownership() {
        while (running) {
            phase = Phase.STANDBY;
            Lease.Claim claim;
            try {
                claim = lease.awaitClaim();
            } catch (InterruptedException e) {
                return;
            }
            if (claim == null || !running) {
                return;
            }
            leaseLost = false;
            try {
                adopt(claim);
            } catch (LeaseLostException e) {
                leaseLost = true;
            } catch (Exception | Error e) {
                if (!running) {
                    return;
                }
                reportError(e);
                log.warn("Exclusive subscription {} on {}: starting delivery under fence {} failed; releasing the lease", key.subscriber(), key.aggregateType(),
                         claim.fence(), e);
                try {
                    progress.release(key, null, null, ownedFence);
                } catch (Exception again) {
                    log.debug("Releasing the lease of {} failed", key, again);
                }
                lease.lost("start-up failed");
                leaveAfterLoss();
                sleepQuietly(settings.backstopInterval());
                continue;
            }
            if (!leaseLost) {
                lifecycle();
            }
            if (!leaseLost) {
                return;
            }
            if (ownedFence == null && !running) {
                return;
            }
            leaveAfterLoss();
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** After a claim, what {@link #run()} does after a start - position at the row's resume point, load parked, join */
    private void adopt(Lease.Claim claim) throws Exception {
        ownedFence = new ProgressStore.Fence(claim.fence(), lease.owner());
        resetEpoch = claim.resetEpoch();
        lastSaved = claim.resumeFrom();
        lastSaveNanos = System.nanoTime();
        var digestChanged = !Objects.equals(filterDigest, claim.filterDigest());
        if (!mode.name().equals(claim.consistencyMode()) || !model.name().equals(claim.progressModel()) || digestChanged) {
            log.warn("Exclusive subscription {} on {} was stored as {}/{} (filter digest {}) and now runs as {}/{} (filter digest {}) under fence {}; the progress " +
                             "row is updated{}", key.subscriber(), key.aggregateType(), claim.consistencyMode(), claim.progressModel(), claim.filterDigest(), mode, model,
                     filterDigest, claim.fence(), digestChanged ? " and delivery continues under the new filter; reset the subscription to replay" : "");
            if (!progress.setMode(key, mode.name(), model.name(), filterDigest, resetEpoch, boundFence())) {
                onProgressConflict();
            }
        }
        var resumeFrom = repositionTo != null ? repositionTo : lastSaved;
        repositionTo = null;
        positionAt(resumeFrom);
        loadParked();
        type = loop.join(key.aggregateType(), statements, mapper, seams);
        log.info("Exclusive subscription {} on {} ({}, {}) claimed with fence {} by {}; delivering from {}", key.subscriber(), key.aggregateType(), mode, model,
                 claim.fence(), lease.owner(), resumePoint());
    }

    /**
     * Lease {@code LOST}: close the inbox, leave the reader, drop pending, held-back and parked state, and go back to
     * standby - no save, no retry of the failed write, no release
     */
    private void leaveAfterLoss() {
        var current = inbox;
        inbox = null;
        if (current != null) {
            current.close(Inbox.CloseReason.STOPPED);
        }
        if (type != null) {
            loop.leave(type, current);
            type = null;
        }
        ownedFence = null;
        positionAt(lastSaved);
        parked.clear();
        repositionTo = null;
        leaseLost = false;
        lease.standby();
        phase = Phase.STANDBY;
    }

    /** The final write on stop: a periodic owner saves and releases in one statement, an in-transaction one releases */
    private void releaseLease() throws SQLException {
        var fence = ownedFence;
        if (fence == null || lease.state() == Lease.State.LOST) {
            return;
        }
        var resume = model == ProgressModel.PERIODIC ? (Long) resumePoint() : null;
        var epoch  = seams.has(RELEASE_WRITES_ANY_EPOCH) ? null : (Long) resetEpoch;
        if (progress.release(key, resume, epoch, fence)) {
            if (resume != null && resume != lastSaved) {
                lastSaved = resume;
                observer.onProgressSaved(key.subscriber(), key.aggregateType(), resume);
            }
            log.info("Exclusive subscription {} on {} released its lease (fence {})", key.subscriber(), key.aggregateType(), fence.fence());
        } else {
            log.info("Exclusive subscription {} on {}: the release under fence {} matched nothing; the lease had moved", key.subscriber(), key.aggregateType(),
                     fence.fence());
        }
        ownedFence = null;
    }

    /** The fence a write binds: {@code null} for a non-exclusive subscription (and under the test mutants) */
    private ProgressStore.Fence boundFence() {
        if (lease == null) {
            return null;
        }
        var fence = ownedFence;
        if (fence == null) {
            throw new LeaseLostException(false);
        }
        if (seams.has(UNFENCED_SAVE)) {
            return null;
        }
        return seams.has(FENCE_WITHOUT_OWNER) ? fence.withoutOwner() : fence;
    }

    /** The epoch a save binds: {@code null} only under mutant {@link WatermarkMutant#SAVE_WITHOUT_EPOCH} */
    private Long boundEpoch() {
        return seams.has(SAVE_WITHOUT_EPOCH) ? null : (Long) resetEpoch;
    }

    /** A lost lease ends the lifecycle wherever the delivery thread is idle, leaving the reader */
    private void checkLost() {
        if (lease != null && (ownedFence == null || lease.state() == Lease.State.LOST)) {
            throw new LeaseLostException(false);
        }
    }

    /**
     * Before a handler call, a periodic save or park, or a page. Lost: unwind. Past the local deadline: suspend,
     * and wait until the keeper confirms the lease again or reports it lost
     */
    private void ownerCheck() throws InterruptedException {
        if (lease == null) {
            return;
        }
        checkLost();
        if (seams.has(NO_LOCAL_DEADLINE)) {
            return;
        }
        if (lease.suspendIfPastDeadline()) {
            log.warn("Exclusive subscription {} on {}: no lease renewal confirmed within {}; delivery is suspended", key.subscriber(), key.aggregateType(),
                     settings.leaseLocalStop());
            observer.onLeaseSuspended(key.subscriber(), key.aggregateType());
        }
        if (lease.state() != Lease.State.SUSPENDED) {
            return;
        }
        var before = phase;
        phase = Phase.SUSPENDED;
        try {
            while (running) {
                var state = lease.awaitNotSuspended(settings.backstopInterval().toNanos());
                if (state == Lease.State.LOST) {
                    throw new LeaseLostException(false);
                }
                if (state != Lease.State.SUSPENDED) {
                    log.info("Exclusive subscription {} on {}: lease confirmed again; delivery resumes", key.subscriber(), key.aggregateType());
                    observer.onLeaseResumed(key.subscriber(), key.aggregateType());
                    return;
                }
                if (lease.closed()) {
                    return;
                }
            }
        } finally {
            phase = before;
        }
    }

    /** @return the progress row's key */
    ProgressStore.Key key() {
        return key;
    }

    /**
     * Whether this subscription may serve a parked request now - running and, if exclusive, owning its lease
     */
    boolean servesParked() {
        return isRunning() && (lease == null || (lease.state() == Lease.State.OWNED && ownedFence != null));
    }

    /** @return the lease of an exclusive subscription, {@code null} for a non-exclusive one; for tests */
    Lease lease() {
        return lease;
    }

    /** The decision on a fenced write that updated nothing, in order */
    enum ConflictDecision {
        /** Non-exclusive, row gone: recreate it */
        RECREATE,
        /** Exclusive, row gone: stop with an error, never recreate (it would lose the resume point) */
        STOP,
        /** Exclusive, fence or owner differs: ownership moved */
        LOST,
        /** The epoch moved: a superseding reset, reposition */
        RESET,
        /** {@code resume_from} moved under the same epoch (and fence): another writer, reposition and log at error */
        MOVED,
        /** Nothing visible differs any more: the next write tries again */
        NONE
    }

    /**
     * The {@link ConflictDecision}, as a pure function of the row read after the zero-row write
     *
     * @param fence the fence this incarnation delivers under; {@code null} for a non-exclusive subscription
     */
    static ConflictDecision decide(boolean exclusive, Optional<ProgressStore.Progress> row, long resetEpoch, long lastSaved, ProgressStore.Fence fence) {
        if (row.isEmpty()) {
            return exclusive ? ConflictDecision.STOP : ConflictDecision.RECREATE;
        }
        var stored = row.get();
        if (exclusive && (fence == null || !Objects.equals(stored.leaseFence(), fence.fence()) || !Objects.equals(stored.leaseOwner(), fence.owner()))) {
            return ConflictDecision.LOST;
        }
        if (stored.resetEpoch() != resetEpoch) {
            return ConflictDecision.RESET;
        }
        if (stored.resumeFrom() != lastSaved) {
            return ConflictDecision.MOVED;
        }
        return ConflictDecision.NONE;
    }

    /** Start over at {@code resumeFrom}: nothing received, nothing pending */
    private void positionAt(long resumeFrom) {
        pending.clear();
        heldBack.clear();
        attachCursor = null;
        lastFailedPage = null;
        appliedBeforeLastPage = null;
        highestHandled = -1;
        if (handled == null) {
            handled = new HandledSet(resumeFrom - 1);
        } else {
            handled.reset(resumeFrom - 1);
        }
        receivedPrefix = resumeFrom - 1;
        receivedWatermark = resumeFrom - 1;
        unknownSinceNanos = null;
        handOffFellBack = false;
        toPark.clear();
        pageCut = 0;
        failingOrder = -1;
        ioFailures = 0;
        stepDown.reset();
        window.reset();
    }

    /** The held aggregates from {@code el_parked_events}: at start and after a reposition */
    private void loadParked() throws SQLException {
        if (seams.has(HELD_SET_NOT_RELOADED)) {
            parked.clear();
            return;
        }
        parked.load();
        heldChanged();
    }

    private void lifecycle() {
        while (running) {
            try {
                if (repositionTo != null) {
                    reposition();
                }
                if (contiguous()) {
                    if (catchUpAndAttachContiguous()) {
                        attachedContiguous();
                    }
                    continue;
                }
                var reply = catchUpAndAttach();
                if (reply == null || !bridge(reply)) {
                    continue;
                }
                attached();
            } catch (TestSeams.Crash e) {
                log.info("Subscription {} on {}: {}", key.subscriber(), key.aggregateType(), e.getMessage());
                crashed = true;
                running = false;
                return;
            } catch (LeaseLostException e) {
                leaseLost = true;
                return;
            } catch (InterruptedException e) {
                return;
            } catch (Exception | Error e) {
                if (!running) {
                    return;
                }
                reportError(e);
                log.warn("Subscription {} on {}: a catch-up or bridge read failed; catching up again after {}", key.subscriber(), key.aggregateType(),
                         settings.backstopInterval(), e);
                try {
                    if (inbox != null) {
                        detach(Inbox.CloseReason.ERROR);
                    }
                    Thread.sleep(settings.backstopInterval());
                } catch (InterruptedException interrupted) {
                    return;
                } catch (Exception again) {
                    log.warn("Subscription {} on {}: detaching failed", key.subscriber(), key.aggregateType(), again);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // FRESH
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Catch up {@code (P, W]} and attach
     *
     * @return the accepted attach, or {@code null} if stopped or a reset is pending
     */
    private ReaderLoop.AttachReply catchUpAndAttach() throws Exception {
        while (running && repositionTo == null) {
            var earlyIds  = typeIdsBeforeCeiling();
            var version   = type.version();
            var state     = type.state();
            var watermark = state == null ? null : state.watermark();
            if (watermark == null && (state == null || !seams.has(ATTACH_WITH_UNKNOWN_WATERMARK))) {
                if (mayAttachUnknown(state)) {
                    var reply = attachUnknown(version);
                    if (reply != null) {
                        return reply;
                    }
                    continue;
                }
                waitForWatermark(version, state);
                continue;
            }
            if (watermark != null) {
                unknownSinceNanos = null;
                handOffFellBack = false;
            }
            phase = Phase.CATCHING_UP;
            if (filtered && watermark != null && handled.prefix() < watermark) {
                seams.hooks().beforeDeliveryRead(key.subscriber(), false);
                var page = filteredRead(handled.prefix(), watermark, earlyIds);
                if (page == null) {
                    continue;
                }
                receivedWatermark = Math.max(receivedWatermark, page.advanceTo());
                var worked = work(page.rows());
                releasePermit(true);
                if (!worked) {
                    return null;
                }
                if (handled.prefix() < watermark) {
                    continue;
                }
            } else if (watermark != null && handled.prefix() < watermark) {
                seams.hooks().beforeDeliveryRead(key.subscriber(), false);
                var rows = read(false, handled.prefix(), watermark, null, null);
                var full = rows.size() >= settings.catchUpPageSize();
                receivedWatermark = Math.max(receivedWatermark, full ? rows.getLast().globalOrder() : watermark);
                var worked = work(rows);
                releasePermit(true);
                if (!worked) {
                    return null;
                }
                if (full) {
                    continue;
                }
            }
            var candidate = new Inbox(settings.subscriptionBufferSize(), false, filter, tenant, seams.has(INBOX_COUNTS_UNMATCHED));
            inbox = candidate;
            if (seams.has(BRIDGE_BEFORE_ATTACH) && state != null && !bridge(new ReaderLoop.AttachReply(true, state.cursor(), watermark, state.holes(), state.ranges()))) {
                inbox = null;
                return null;
            }
            seams.hooks().beforeAttachRequest(key.subscriber());
            phase = Phase.ATTACHING;
            var reply = loop.attach(type, candidate, key.subscriber(), seams.hooks()).get();
            if (reply.accepted()) {
                endWait();
                if (seams.has(BRIDGE_BEFORE_ATTACH)) {
                    // Bridged already: nothing between the published cursor and the attach is read
                    return new ReaderLoop.AttachReply(true, Long.MIN_VALUE, reply.watermark(), Collections.emptySortedSet(), Collections.emptySortedMap());
                }
                return reply;
            }
            inbox = null;
            waitForWatermark(version, type.state());
        }
        return null;
    }

    /**
     * A {@code FRESH} subscription that has waited one backstop interval under an unknown watermark
     * attaches anyway - unless its handled set already has more gaps than {@link EventLogSettings#holeRangeThreshold()},
     * or a hand-off fell back since the watermark was last known
     */
    private boolean mayAttachUnknown(TailReader.State state) {
        return state != null && unknownSinceNanos != null && !handOffFellBack
                && System.nanoTime() - unknownSinceNanos >= settings.backstopInterval().toNanos()
                && handled.gaps(settings.holeRangeThreshold()) != null;
    }

    /**
     * {@code attachUnknown}: attach while the watermark is unknown. The reply's watermark is
     * {@link Long#MIN_VALUE}, so neither the bridge nor a batch raises {@code W_rcv} until a batch carries the known one.
     *
     * @return the accepted attach, or {@code null} if refused (the reader restarts); a reply with a known watermark is an
     * ordinary attach
     */
    private ReaderLoop.AttachReply attachUnknown(long version) throws Exception {
        var candidate = new Inbox(settings.subscriptionBufferSize(), false, filter, tenant, seams.has(INBOX_COUNTS_UNMATCHED));
        inbox = candidate;
        seams.hooks().beforeAttachRequest(key.subscriber());
        phase = Phase.ATTACHING;
        var reply = loop.attach(type, candidate, key.subscriber(), seams.hooks(), true).get();
        if (reply.accepted()) {
            endWait();
            return reply;
        }
        inbox = null;
        waitForWatermark(version, type.state());
        return null;
    }

    /**
     * WaitingForWatermark: read nothing until the type's watermark may have changed (F4)
     */
    private void waitForWatermark(long version, TailReader.State state) throws Exception {
        checkLost();
        phase = Phase.WAITING_FOR_WATERMARK;
        if (state != null && state.watermark() == null && unknownSinceNanos == null) {
            unknownSinceNanos = System.nanoTime();
        }
        serveParkedRequests();
        if (!waitReported && (state == null || state.watermark() == null)) {
            waitReported = true;
            var heldBy = new HashSet<String>();
            if (state != null) {
                state.candidates().stream().filter(Candidate::initial).forEach(c -> heldBy.addAll(c.runningXids()));
            }
            log.debug("Subscription {} on {} waits for the reader's watermark (held by {})", key.subscriber(), key.aggregateType(), heldBy);
            observer.onWaitingForWatermark(key.subscriber(), key.aggregateType(), true, Set.copyOf(heldBy));
        }
        type.awaitChange(version, settings.backstopInterval());
    }

    private void endWait() {
        unknownSinceNanos = null;
        if (waitReported) {
            waitReported = false;
            observer.onWaitingForWatermark(key.subscriber(), key.aggregateType(), false, Set.of());
        }
    }

    /**
     * Bridge: read {@code (max(P, highest handled), C]}; each page also re-reads the attach holes at or
     * below its lower bound that the bridge has not returned yet, and the handled set's gaps - the unhandled orders
     * between {@code P} and the highest handled one - so a late commit into either is handled before its successors in
     * later pages (I2). Above {@link EventLogSettings#holeRangeThreshold()} gaps the bridge starts at {@code P}.
     *
     * @return {@code true} once the bridge reached {@code C}; {@code false} if stopped, reset or detached
     */
    private boolean bridge(ReaderLoop.AttachReply reply) throws Exception {
        phase = Phase.BRIDGING;
        var cursor   = reply.cursor();
        var holes    = new TreeSet<>(reply.holes());
        var ranges   = new TreeMap<>(reply.ranges());
        var withGaps = !seams.has(BRIDGE_FROM_HIGHEST_WITHOUT_GAPS) && !seams.has(ATTACH_WITHOUT_BRIDGE) && cursor != Long.MIN_VALUE
                && handled.gaps(settings.holeRangeThreshold()) != null;
        var lower    = seams.has(ATTACH_WITHOUT_BRIDGE) ? cursor
                                                         : withGaps || seams.has(BRIDGE_FROM_HIGHEST_WITHOUT_GAPS) ? handled.highest() : handled.prefix();
        var first    = true;
        while (lower < cursor || first && withGaps && hasGaps()) {
            if (!running || repositionTo != null) {
                return false;
            }
            if (inbox.closeReason() != null) {
                detach(inbox.closeReason());
                return false;
            }
            if (!first) {
                seams.hooks().betweenBridgePages(key.subscriber(), lower);
            }
            first = false;
            if (seams.has(DRAIN_BEFORE_BRIDGE)) {
                Batch batch;
                while ((batch = inbox.take(0)) != null) {
                    if (!work(batch.rows())) {
                        return false;
                    }
                }
            }
            var rereadHoles  = new TreeSet<Long>();
            var rereadRanges = new TreeMap<Long, Long>();
            if (!seams.has(BRIDGE_WITHOUT_HOLE_REREAD)) {
                rereadHoles.addAll(holes.headSet(lower, true));
                for (var range : ranges.headMap(lower, true).entrySet()) {
                    rereadRanges.put(range.getKey(), Math.min(range.getValue(), lower));
                }
            }
            if (withGaps && !seams.has(BRIDGE_WITHOUT_HOLE_REREAD)) {
                // The gaps are part of the hole branch: the mutant drops them with the attach holes
                var gaps = handled.gaps(settings.holeRangeThreshold());
                if (gaps == null && reply.unknownWatermark()) {
                    return fallBackToWait();
                }
                if (gaps == null) {
                    // More gaps than the threshold since the bridge started: finish from P, as an ordinary attach does
                    withGaps = false;
                    lower = handled.prefix();
                    continue;
                }
                for (var gap : gaps) {
                    if (gap.single()) {
                        rereadHoles.add(gap.low());
                    } else {
                        rereadRanges.merge(gap.low(), gap.high(), Math::max);
                    }
                }
            }
            var typeIds = typeFiltered ? resolveTypeIds() : null;
            List<StoredEvent> rows = typeIds != null && typeIds.length == 0 ? List.of() : read(true, lower, Math.max(lower, cursor), rereadHoles, rereadRanges, typeIds);
            var newRows = 0;
            var highest = lower;
            var dropped = 0;
            for (var row : rows) {
                holes.remove(row.globalOrder());
                if (handled.contains(row.globalOrder())) {
                    dropped++;
                }
                if (row.globalOrder() > lower) {
                    newRows++;
                    highest = Math.max(highest, row.globalOrder());
                }
            }
            bridgeRowsDroppedAsHandled += dropped;
            var ceiling = newRows >= settings.catchUpPageSize() ? highest : Math.max(lower, cursor);
            receivedWatermark = Math.max(receivedWatermark, Math.min(ceiling, reply.watermark()));
            var worked = work(rows);
            releasePermit(true);
            if (!worked) {
                return false;
            }
            if (filtered && ceiling > lower && (!reply.unknownWatermark() || seams.has(FILTERED_BRIDGE_MARKS_RANGE_UNDER_UNKNOWN_WATERMARK))) {
                // Filtered bridge (not under an unknown watermark: a value held below the head is no attach hole, so
                // the covered range would mark it handled; there every order not returned stays a gap and is handed off): every order of (lower, ceiling] the page did not return is non-matching or an attach hole still re-read
                if (seams.has(FILTERED_BRIDGE_MARKS_HOLES)) {
                    handled.addRange(lower, ceiling, Collections.emptySortedSet(), Collections.emptySortedMap());
                } else {
                    handled.addRange(lower, ceiling, holes, ranges);
                }
                advance();
            }
            lower = ceiling;
        }
        if (inbox.closeReason() != null) {
            detach(inbox.closeReason());
            return false;
        }
        return !reply.unknownWatermark() || handOff(cursor);
    }

    /**
     * After a bridge under the unknown watermark reached {@code C}: {@code P} is final
     * below, so every order of {@code (P, C]} the bridge did not hand on is a gap - rolled back, non-matching, or held by
     * a writer of the reader's initial candidate (I4). The gaps go to the reader, which re-reads them in every tail
     * statement until that candidate retires; then one re-read here takes what committed into them before the hand-off
     * reached the loop, ahead of every batch in the inbox (I2: a later event of the same aggregate may be in one). If
     * the candidate retired already, that re-read is final and answers the hand-off. Above
     * {@link EventLogSettings#holeRangeThreshold()} gaps: detach and wait for the watermark (F4).
     *
     * @return {@code true} to go on attached; {@code false} if detached, stopped or reset
     */
    private boolean handOff(long cursor) throws Exception {
        var limit = settings.holeRangeThreshold();
        var gaps  = handled.gaps(limit);
        if (gaps != null && handled.highest() < cursor) {
            gaps = new ArrayList<>(gaps);
            gaps.add(new HandledSet.Gap(handled.highest() + 1, cursor));
        }
        if (gaps == null || gaps.size() > limit) {
            return fallBackToWait();
        }
        if (gaps.isEmpty() || seams.has(GAPS_NOT_HANDED_OFF)) {
            return true;
        }
        seams.hooks().beforeHandOff(key.subscriber(), gaps);
        var answer = loop.handOff(type, inbox, key.subscriber(), seams.hooks(), gaps).get();
        if (answer == ReaderLoop.HandOffReply.REFUSED) {
            var reason = inbox.closeReason();
            detach(reason == null ? Inbox.CloseReason.READER_RESTART : reason);
            return false;
        }
        var holes  = new TreeSet<Long>();
        var ranges = new TreeMap<Long, Long>();
        for (var gap : gaps) {
            if (gap.single()) {
                holes.add(gap.low());
            } else {
                ranges.put(gap.low(), gap.high());
            }
        }
        var typeIds = typeFiltered ? resolveTypeIds() : null;
        List<StoredEvent> rows = typeIds != null && typeIds.length == 0 || seams.has(NO_REREAD_AFTER_HAND_OFF) ? List.of()
                                                                                                         : read(true, cursor, cursor, holes, ranges, typeIds);
        var worked = work(rows);
        releasePermit(true);
        if (!worked) {
            return false;
        }
        if (inbox.closeReason() != null) {
            detach(inbox.closeReason());
            return false;
        }
        return true;
    }

    /** Too many gaps to hand off - detach and wait for the watermark (F4) until it is known */
    private boolean fallBackToWait() throws Exception {
        handOffFellBack = true;
        log.debug("Subscription {} on {}: more than {} gaps under the unknown watermark; waiting for it instead", key.subscriber(), key.aggregateType(),
                  settings.holeRangeThreshold());
        detach(Inbox.CloseReason.TOO_MANY_GAPS);
        return false;
    }

    /** @return whether the handled set has gaps, or more than the threshold */
    private boolean hasGaps() {
        var gaps = handled.gaps(settings.holeRangeThreshold());
        return gaps == null || !gaps.isEmpty();
    }

    /**
     * Attached: take batches until the inbox closes, the subscription stops or a reset is pending
     */
    private void attached() throws Exception {
        phase = Phase.ATTACHED;
        var poll = settings.backstopInterval().toNanos();
        while (running && repositionTo == null) {
            iterations++;
            checkLost();
            serveParkedRequests();
            if (repositionTo != null) {
                return;
            }
            var current = inbox;
            var reason  = current.closeReason();
            if (reason != null) {
                detach(reason);
                return;
            }
            var batch = current.take(poll);
            if (batch == null) {
                maybeSave();
                continue;
            }
            observer.onBatchTaken(key.subscriber(), key.aggregateType(), batch.size(), current.events());
            if (batch.watermark() != Long.MIN_VALUE) {
                receivedWatermark = Math.max(receivedWatermark, batch.watermark());
            }
            work(batch.rows());
            maybeSave();
        }
    }

    /**
     * Work through received rows in order, dropping those already handled
     *
     * @return {@code false} if the subscription stopped, a reset is pending or, attached, the inbox closed before all
     * were handled
     */
    private boolean work(List<StoredEvent> rows) throws Exception {
        pending.clear();
        for (var row : rows) {
            if (filtered && !matches(row)) {
                // A non-matching row is handled as a no-op
                handled.add(row.globalOrder());
                continue;
            }
            if (seams.has(NO_DEDUPE) || !handled.contains(row.globalOrder())) {
                pending.addLast(new HeldBack.Entry(row, null));
            }
        }
        advance();
        return drain();
    }

    /** {@code FRESH}: {@code P = min(W_rcv, lowest received-but-unhandled - 1)}; never lowered */
    private void advance() {
        var prefix = receivedWatermark;
        if (!pending.isEmpty()) {
            prefix = Math.min(prefix, pending.peekFirst().globalOrder() - 1);
        }
        handled.advanceTo(prefix);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CONTIGUOUS
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Catch up {@code (R, W]} into pending, applying it as it comes, then attach. The deferred bridge starts
     * from the attach reply.
     *
     * @return {@code true} once attached; {@code false} if stopped or a reset is pending
     */
    private boolean catchUpAndAttachContiguous() throws Exception {
        while (running && repositionTo == null) {
            phase = Phase.CATCHING_UP;
            if (!pending.isEmpty()) {
                // Kept from before a detach: final, so applied ahead of the catch-up
                if (!drain()) {
                    return false;
                }
                continue;
            }
            var earlyIds  = typeIdsBeforeCeiling();
            var version   = type.version();
            var state     = type.state();
            var watermark = state == null ? null : state.watermark();
            if (watermark == null && (state == null || !seams.has(ATTACH_WITH_UNKNOWN_WATERMARK))) {
                waitForWatermark(version, state);
                continue;
            }
            phase = Phase.CATCHING_UP;
            if (filtered && watermark != null && receivedPrefix < watermark) {
                seams.hooks().beforeDeliveryRead(key.subscriber(), false);
                var page = filteredRead(receivedPrefix, watermark, earlyIds);
                if (page != null) {
                    receivedWatermark = Math.max(receivedWatermark, page.advanceTo());
                    toPending(page.rows(), page.advanceTo());
                }
                continue;
            }
            if (watermark != null && receivedPrefix < watermark) {
                seams.hooks().beforeDeliveryRead(key.subscriber(), false);
                var rows    = read(false, receivedPrefix, watermark, null, null);
                var full    = rows.size() >= settings.catchUpPageSize();
                var ceiling = full ? rows.getLast().globalOrder() : watermark;
                receivedWatermark = Math.max(receivedWatermark, ceiling);
                toPending(rows, ceiling);
                continue;
            }
            var candidate = new Inbox(settings.subscriptionBufferSize(), !seams.has(HELD_NOT_COUNTED), filter, tenant, seams.has(INBOX_COUNTS_UNMATCHED));
            inbox = candidate;
            seams.hooks().beforeAttachRequest(key.subscriber());
            phase = Phase.ATTACHING;
            var reply = loop.attach(type, candidate, key.subscriber(), seams.hooks()).get();
            if (reply.accepted()) {
                endWait();
                attachCursor = seams.has(CONTIGUOUS_SKIPS_DEFERRED_BRIDGE) ? null : reply.cursor();
                // The attach's watermark is one the reader published: everything at or below it is final (F2)
                receivedWatermark = Math.max(receivedWatermark, reply.watermark());
                return true;
            }
            inbox = null;
            waitForWatermark(version, type.state());
        }
        return false;
    }

    /**
     * Attached, {@code CONTIGUOUS}: release what the received watermark covers (deferred bridge first), apply pending,
     * and only then take the next batch
     */
    private void attachedContiguous() throws Exception {
        phase = Phase.ATTACHED;
        var poll = settings.backstopInterval().toNanos();
        while (running && repositionTo == null) {
            iterations++;
            checkLost();
            serveParkedRequests();
            if (repositionTo != null) {
                return;
            }
            var current = inbox;
            var reason  = current.closeReason();
            if (reason != null) {
                detach(reason);
                return;
            }
            release();
            if (!pending.isEmpty()) {
                drain();
                continue;
            }
            var batch = current.take(poll);
            if (batch == null) {
                maybeSave();
                continue;
            }
            observer.onBatchTaken(key.subscriber(), key.aggregateType(), batch.size(), current.events());
            receive(batch, current);
            maybeSave();
        }
    }

    /**
     * A batch's rows go into {@link HeldBack}, except a row at or below {@code R}, which is in pending or applied
     * already and is dropped
     */
    private void receive(Batch batch, Inbox counting) {
        if (batch.watermark() != Long.MIN_VALUE) {
            receivedWatermark = Math.max(receivedWatermark, batch.watermark());
        }
        for (var row : batch.rows()) {
            if (filtered && !matches(row)) {
                // Dropped on receipt; R moves with W_rcv regardless. Counted only under the mutant
                if (seams.has(INBOX_COUNTS_UNMATCHED)) {
                    counting.release(1);
                }
                continue;
            }
            var entry = new HeldBack.Entry(row, counting);
            if (row.globalOrder() <= receivedPrefix) {
                entry.release();
            } else if (seams.has(NO_HOLD_BACK)) {
                pending.addLast(entry);
                receivedPrefix = row.globalOrder();
            } else {
                heldBack.add(entry);
            }
        }
        if (heldBack.size() > heldBackHighWater) {
            heldBackHighWater = heldBack.size();
        }
    }

    /**
     * While {@code R} is below the received watermark, first bridge {@code (R, min(W_rcv, C)]} (final, so a
     * plain range read), dropping the held rows it covers; then move every held row at or below {@code W_rcv} to
     * pending and set {@code R = W_rcv}
     */
    private void release() throws Exception {
        var target = receivedWatermark;
        if (seams.has(RELEASE_AT_READER_WATERMARK)) {
            var reader = type.reader();
            var live   = reader == null ? null : reader.state().watermark();
            if (live != null) {
                target = Math.max(target, live);
            }
        }
        while (receivedPrefix < target && running && repositionTo == null) {
            if (attachCursor != null && receivedPrefix < attachCursor) {
                var upper = Math.min(target, attachCursor);
                seams.hooks().beforeDeliveryRead(key.subscriber(), true);
                List<StoredEvent> rows;
                long              ceiling;
                if (filtered) {
                    var page = filteredRead(receivedPrefix, upper, null);
                    if (page == null) {
                        continue;
                    }
                    rows = page.rows();
                    ceiling = page.advanceTo();
                } else {
                    rows = read(true, receivedPrefix, upper, null, null);
                    ceiling = rows.size() >= settings.catchUpPageSize() ? rows.getLast().globalOrder() : upper;
                }
                heldBack.dropUpTo(ceiling);
                if (ceiling >= attachCursor) {
                    attachCursor = null;
                }
                toPending(rows, ceiling);
                if (!pending.isEmpty() && !drain()) {
                    return;
                }
                continue;
            }
            attachCursor = null;
            var released = heldBack.releaseUpTo(target);
            receivedPrefix = target;
            pending.addAll(released);
            notifyRelease(released.stream().map(HeldBack.Entry::row).toList(), target);
        }
    }

    /** Catch-up or deferred-bridge rows, all at or below {@code ceiling}, which becomes {@code R} */
    private void toPending(List<StoredEvent> rows, long ceiling) throws Exception {
        for (var row : rows) {
            if (row.globalOrder() > receivedPrefix) {
                pending.addLast(new HeldBack.Entry(row, null));
            }
        }
        receivedPrefix = Math.max(receivedPrefix, ceiling);
        notifyRelease(rows, ceiling);
    }

    private void notifyRelease(List<StoredEvent> rows, long receivedWatermark) throws Exception {
        if (rows.isEmpty()) {
            return;
        }
        observer.onReleased(key.subscriber(), key.aggregateType(), rows.size(), receivedWatermark, heldBack.size());
        seams.hooks().onRelease(key.subscriber(), rows, receivedWatermark);
    }

    /** {@code CONTIGUOUS}: {@code A}, derived - every existing order in {@code (A, R]} is pending */
    private long appliedPrefix() {
        return pending.isEmpty() ? receivedPrefix : pending.peekFirst().globalOrder() - 1;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Both modes: detach, reposition, applying pending
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Detach, only ever between pages. {@code FRESH}: {@code P} is already
     * {@code min(W_rcv, lowest unhandled received - 1)} (F5); the unhandled rows and the inbox are discarded, the runs
     * kept. {@code CONTIGUOUS}: pending {@code (A, R]} is kept, held-back rows and the inbox are dropped, and catch-up
     * resumes above {@code R}.
     */
    private void detach(Inbox.CloseReason reason) throws Exception {
        if (contiguous()) {
            if (seams.has(DETACH_POSITION_PAST_OPEN_PAGE) && lastFailedPage != null) {
                var failed = lastFailedPage;
                pending.removeIf(entry -> failed.stream().anyMatch(f -> f.globalOrder() == entry.globalOrder()));
            }
            if (seams.has(REREAD_WITHOUT_APPLIED_DROP) && appliedBeforeLastPage != null) {
                pending.clear();
                receivedPrefix = appliedBeforeLastPage;
            }
            heldBack.clear();
            attachCursor = null;
            receivedWatermark = receivedPrefix;
        } else {
            if (seams.has(DETACH_RESUMES_AT_WATERMARK)) {
                handled.advanceTo(receivedWatermark);
            }
            pending.clear();
            receivedWatermark = handled.prefix();
        }
        var current = inbox;
        inbox = null;
        if (current != null && current.close(reason)) {
            loop.detach(type, current);
        }
        phase = Phase.CATCHING_UP;
        var resumeFrom = resumePoint();
        log.debug("Subscription {} on {} detached ({}); catching up from {}", key.subscriber(), key.aggregateType(), reason, resumeFrom);
        observer.onDetach(key.subscriber(), key.aggregateType(), reason.name(), resumeFrom);
        seams.hooks().onDetach(key.subscriber(), reason.name(), resumeFrom);
    }

    /**
     * A superseding reset, or (in-transaction) another writer of the progress row: detach and
     * reposition at the row's resume point; what was handled no longer counts
     */
    private void reposition() throws Exception {
        var resumeFrom = repositionTo;
        repositionTo = null;
        if (inbox != null) {
            detach(Inbox.CloseReason.RESET);
        }
        positionAt(resumeFrom);
        loadParked();
    }

    /**
     * Apply pending until it is empty: event by event ({@link ProgressModel#PERIODIC}) or page by page
     * ({@link ProgressModel#IN_HANDLER_TRANSACTION})
     *
     * @return {@code false} if the subscription stopped, a reset is pending or, attached, the inbox closed first
     */
    private boolean drain() throws Exception {
        while (!pending.isEmpty()) {
            if (!running || repositionTo != null || attachedInboxClosed()) {
                return false;
            }
            serveParkedRequests();
            if (repositionTo != null || pending.isEmpty()) {
                return repositionTo == null;
            }
            var ok = model == ProgressModel.PERIODIC ? applyOne() : applyPage();
            if (!ok) {
                return false;
            }
        }
        releasePermit(true);
        return true;
    }

    private boolean attachedInboxClosed() {
        var current = inbox;
        return phase == Phase.ATTACHED && current != null && current.closeReason() != null;
    }

    /**
     * {@link ProgressModel#PERIODIC}: the first pending event - parked in autocommit if it is decided or its aggregate is
     * held, else handled with retries - then mark it applied. A park row is inserted before the event counts
     * as handled, so a crash in between replays it and finds it parked.
     */
    private boolean applyOne() throws Exception {
        var entry = pending.peekFirst();
        var event = entry.row();
        var park  = holdRowFor(event, Map.of());
        if (park == null) {
            var outcome = handle(event);
            if (outcome.stop()) {
                return false;
            }
            park = outcome.park();
        }
        if (park != null) {
            var rows = List.of(park);
            if (!parkNow(park)) {
                return false;
            }
            seams.hooks().onPark(key.subscriber(), park.globalOrder(), park.blockedBy());
            afterParked(rows);
        }
        pending.removeFirst();
        entry.release();
        if (!contiguous()) {
            handled.add(entry.globalOrder());
            highestHandled = Math.max(highestHandled, entry.globalOrder());
            advance();
        }
        maybeSave();
        return true;
    }

    /**
     * The periodic park insert: guarded by the epoch and, exclusive, the lease; a guard that matches
     * nothing is a zero-row write, and the event does not count as handled
     *
     * @return {@code true} once the row is parked (or found parked); {@code false} if stopped or repositioning
     */
    private boolean parkNow(ParkedEvents.Row park) throws Exception {
        while (true) {
            ownerCheck();
            if (!running) {
                return false;
            }
            seams.hooks().beforePeriodicPark(key.subscriber(), park.globalOrder());
            if (seams.has(UNGUARDED_PERIODIC_PARK)) {
                parked.insertNow(List.of(park));
                return true;
            }
            if (parked.insertGuarded(park, resetEpoch, boundFence())) {
                return true;
            }
            onProgressConflict();
            if (repositionTo != null) {
                return false;
            }
        }
    }

    /** What became of a {@link ProgressModel#PERIODIC} event: stopped, handled ({@code park} null) or to be parked */
    private record Outcome(boolean stop, ParkedEvents.Row park) {
        static final Outcome STOPPED = new Outcome(true, null);
        static final Outcome HANDLED = new Outcome(false, null);
    }

    /**
     * Run the handler until it returns normally, the event's retries are used up or it asks to park it. The
     * handler holds no engine connection, so whatever it throws is its own failure - an I/O-classified one too;
     * only mutant {@link WatermarkMutant#HANDLER_IO_RETRIED_FOREVER} retries that indefinitely without counting an attempt.
     */
    private Outcome handle(StoredEvent event) throws Exception {
        var ioFailuresHere = 0;
        while (true) {
            if (!running) {
                return Outcome.STOPPED;
            }
            ownerCheck();
            if (!running) {
                return Outcome.STOPPED;
            }
            seams.hooks().beforeHandler(key.subscriber(), event);
            trace.delivering(event);
            try {
                handler.handle(event);
                if (lease != null) {
                    var overrun = lease.nanosPastDeadline();
                    if (overrun > 0) {
                        observer.onLeaseOverrun(key.subscriber(), key.aggregateType(), overrun);
                    }
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception | Error e) {
                // An Error from the handler (an AssertionError, a StackOverflowError) is a handler failure like any other:
                // retried, then parked, rather than ending the delivery thread without a word
                if (!running) {
                    return Outcome.STOPPED;
                }
                reportError(e);
                if (e instanceof ParkEventException parkNow && (parkNow.event() == null || parkNow.event().globalOrder() == event.globalOrder())) {
                    return new Outcome(false, parkRow(event, attemptsAfterFailure(event), parkNow));
                }
                Duration wait;
                if (isEngineFailure(true, e, () -> false, seams)) {
                    wait = errors.backoff(ioFailuresHere++);
                    log.warn("Subscription {} on {}: I/O failure handling global_order {}; retrying after {}", key.subscriber(), key.aggregateType(),
                             event.globalOrder(), wait, e);
                } else {
                    var attempts = attemptsAfterFailure(event);
                    if (retriesUsedUp(attempts, errors)) {
                        log.warn("Subscription {} on {}: handler failed {} times on global_order {}; parking it", key.subscriber(), key.aggregateType(), attempts,
                                 event.globalOrder(), e);
                        return new Outcome(false, parkRow(event, attempts, e));
                    }
                    wait = errors.backoff(attempts - 1);
                    log.warn("Subscription {} on {}: handler failed on global_order {} (attempt {}); retrying after {}", key.subscriber(), key.aggregateType(),
                             event.globalOrder(), attempts, wait, e);
                }
                Thread.sleep(wait);
                if (attachedInboxClosed()) {
                    return Outcome.STOPPED;
                }
                continue;
            }
            // interrupted while handling: not handled
            if (Thread.currentThread().isInterrupted() && !running) {
                return Outcome.STOPPED;
            }
            if (failingOrder == event.globalOrder()) {
                failingOrder = -1;
            }
            return Outcome.HANDLED;
        }
    }

    /** One more failed attempt of {@code event} alone; @return the attempts so far */
    private int attemptsAfterFailure(StoredEvent event) {
        if (failingOrder != event.globalOrder()) {
            failingOrder = event.globalOrder();
            failingAttempts = 0;
        }
        return ++failingAttempts;
    }

    private static ParkedEvents.Row parkRow(StoredEvent event, int attempts, Throwable error) {
        if (error instanceof ParkEventException parkNow) {
            var cause = parkNow.getCause();
            return new ParkedEvents.Row(event, null, attempts, cause != null ? cause.getClass().getName() : ParkEventException.class.getName(), parkNow.getMessage());
        }
        return new ParkedEvents.Row(event, null, attempts, error.getClass().getName(), error.getMessage());
    }

    /**
     * The park row of an event whose aggregate is held - by the held set, or by a head parked earlier in the same
     * page ({@code pageHeads}) - else {@code null}
     */
    private ParkedEvents.Row holdRowFor(StoredEvent event, Map<String, Long> pageHeads) {
        if (seams.has(NO_AGGREGATE_HOLD)) {
            return null;
        }
        var head = parked.headOf(event.aggregateId());
        if (head == null) {
            head = pageHeads.get(event.aggregateId());
        }
        return head != null && head != event.globalOrder() ? new ParkedEvents.Row(event, head, 0, null, null) : null;
    }

    /** The park rows of {@code page}, in order: events decided for parking (heads) and events of held aggregates */
    private List<ParkedEvents.Row> parksFor(List<StoredEvent> page) {
        var rows      = new ArrayList<ParkedEvents.Row>();
        var pageHeads = new HashMap<String, Long>();
        for (var event : page) {
            var decided = toPark.get(event.globalOrder());
            if (decided != null) {
                rows.add(decided);
                pageHeads.putIfAbsent(event.aggregateId(), event.globalOrder());
                continue;
            }
            var hold = holdRowFor(event, pageHeads);
            if (hold != null) {
                rows.add(hold);
            }
        }
        return rows;
    }

    /** Park rows committed: heads hold their aggregates, the error state forgets them */
    private void afterParked(List<ParkedEvents.Row> rows) {
        var heldBefore = parked.heldCount();
        for (var row : rows) {
            toPark.remove(row.globalOrder());
            if (failingOrder == row.globalOrder()) {
                failingOrder = -1;
            }
            if (row.blockedBy() == null && !seams.has(NO_AGGREGATE_HOLD)) {
                parked.hold(row.event().aggregateId(), row.globalOrder());
            }
            observer.onParked(key.subscriber(), key.aggregateType(), row.globalOrder(), row.blockedBy(), row.attempts());
        }
        if (parked.heldCount() != heldBefore) {
            heldChanged();
        }
    }

    /**
     * {@link ProgressModel#IN_HANDLER_TRANSACTION}: one page transaction over the first pending events, at
     * most the step-down size. Events decided for parking and events of held aggregates are taken out of the page
     * and parked in the same transaction; the handler gets the rest. The handler's writes, the park rows and
     * the progress update - the last statement, guarded by the reset epoch and the value last committed - commit together;
     * the page leaves pending only after the commit. A failure is classified: the engine's I/O
     * ({@link #isEngineFailure})
     * retries the same page, a park request parks, anything else shrinks, retries, then parks.
     *
     * @return {@code true} if the page committed or is to be retried; {@code false} if stopped or repositioning
     */
    private boolean applyPage() throws Exception {
        var size = Math.min(stepDown.size(), pending.size());
        if (pageCut > 0) {
            size = Math.min(size, pageCut);
        }
        var entries = new ArrayList<HeldBack.Entry>(size);
        var it      = pending.iterator();
        while (entries.size() < size) {
            entries.add(it.next());
        }
        var page          = entries.stream().map(HeldBack.Entry::row).toList();
        var parks         = parksFor(page);
        var parkedOrders  = new HashSet<Long>();
        parks.forEach(row -> parkedOrders.add(row.globalOrder()));
        var handlerPage   = parks.isEmpty() ? page : page.stream().filter(event -> !parkedOrders.contains(event.globalOrder())).toList();
        var parkOutside   = seams.has(PARK_OUTSIDE_PAGE_TRANSACTION);
        var next          = it.hasNext() ? it.next().globalOrder() : null;
        var resumeFrom    = resumeAfterPage(next);
        var expected      = seams.has(PROGRESS_WITHOUT_EXPECTED_RESUME) ? null : (Long) lastSaved;
        if (contiguous()) {
            appliedBeforeLastPage = appliedPrefix();
        }
        var hooks      = seams.hooks();
        var subscriber = key.subscriber();
        ownerCheck();
        if (!running) {
            return false;
        }
        PageTransactionSource.OpenPage open       = null;
        Connection                     connection = null;
        var                            inHandler  = false;
        // One nanoTime pair per page for the observer's page timing; the commit side reuses lastSaveNanos
        var                            pageStart  = System.nanoTime();
        try {
            open = pageSource.begin();
            connection = open.connection();
            openPageConnection = connection;
            boundByLease(connection);
            hooks.beforePageHandler(subscriber, handlerPage);
            trace.delivering(handlerPage);
            if (!handlerPage.isEmpty()) {
                inHandler = true;
                pageHandler.handle(handlerPage, handlerTransaction(open));
                failIfRollbackOnly(open);
                inHandler = false;
            }
            if (!parks.isEmpty() && !parkOutside) {
                parked.insert(connection, parks);
                for (var row : parks) {
                    hooks.onPark(subscriber, row.globalOrder(), row.blockedBy());
                }
            }
            boolean saved;
            if (seams.has(PROGRESS_AFTER_COMMIT)) {
                var ending = open;
                open = null;
                commit(ending);
                hooks.afterHandlerBeforeProgress(subscriber, handlerPage);
                saved = progress.saveExpected(key, resumeFrom, boundEpoch(), boundFence(), expected);
                hooks.afterProgressBeforeCommit(subscriber, handlerPage, resumeFrom);
            } else {
                hooks.afterHandlerBeforeProgress(subscriber, handlerPage);
                if (lease != null && seams.has(FENCE_READ_BEFORE_WRITE)) {
                    saved = ProgressStore.fenceMatches(connection, key, ownedFence) && ProgressStore.saveInTransaction(connection, key, resumeFrom, boundEpoch(), null, expected);
                } else {
                    saved = ProgressStore.saveInTransaction(connection, key, resumeFrom, boundEpoch(), boundFence(), expected);
                }
                if (saved) {
                    hooks.afterProgressBeforeCommit(subscriber, handlerPage, resumeFrom);
                    if (pastLeaseDeadline()) {
                        // Past the deadline before commit - roll back, suspend, then retry the page
                        var ending = open;
                        open = null;
                        rollback(ending);
                        connection = null;
                        ownerCheck();
                        return running && repositionTo == null;
                    }
                    var ending = open;
                    open = null;
                    commit(ending);
                } else {
                    var ending = open;
                    open = null;
                    rollback(ending);
                }
            }
            connection = null;
            if (!saved) {
                onProgressConflict();
                return repositionTo == null;
            }
            if (!parks.isEmpty() && parkOutside) {
                for (var row : parks) {
                    hooks.onPark(subscriber, row.globalOrder(), row.blockedBy());
                }
                parked.insertNow(parks);
            }
        } catch (TestSeams.Crash e) {
            // A lost JVM: no rollback, no close - the IT terminates the backend
            throw e;
        } catch (LeaseLostException e) {
            if (open != null) {
                try {
                    rollback(open);
                } catch (RuntimeException rollbackFailed) {
                    log.debug("Rolling back a page of {} after a lost lease failed", key, rollbackFailed);
                }
            }
            throw e;
        } catch (Exception | Error e) {
            // What the handler threw on a live page connection is the handler's failure, I/O-classified or not. An Error
            // too: the page is rolled back and retried or parked like any handler failure, never left open
            var pageConnection = connection;
            var io             = isEngineFailure(inHandler, e, () -> pageConnection != null && invalid(pageConnection), seams);
            if (open != null) {
                try {
                    rollback(open);
                } catch (RuntimeException rollbackFailed) {
                    io = true;
                    log.debug("Rolling back a failed page of {} failed", key, rollbackFailed);
                }
            }
            observer.onPageFailed(subscriber, key.aggregateType(), page.size(), System.nanoTime() - pageStart, e);
            if (e instanceof InterruptedException || !running) {
                return false;
            }
            lastFailedPage = entries;
            reportError(e);
            onPageFailure(e, io, page, handlerPage);
            return true;
        }
        hooks.afterCommit(subscriber, handlerPage, resumeFrom);
        lastFailedPage = null;
        ioFailures = 0;
        pageCut = 0;
        stepDown.committed(page.getFirst().globalOrder(), page.getLast().globalOrder());
        if (failingOrder >= page.getFirst().globalOrder() && failingOrder <= page.getLast().globalOrder()) {
            failingOrder = -1;
        }
        afterParked(parks);
        for (var i = 0; i < size; i++) {
            pending.removeFirst().release();
        }
        if (!contiguous()) {
            for (var row : page) {
                handled.add(row.globalOrder());
                highestHandled = Math.max(highestHandled, row.globalOrder());
            }
            advance();
        }
        lastSaved = resumeFrom;
        lastSaveNanos = System.nanoTime();
        observer.onPageCommitted(subscriber, key.aggregateType(), page.size(), lastSaveNanos - pageStart);
        trace.pageCommitted(page, parks.size(), resumeFrom, lastSaveNanos - pageStart);
        observer.onProgressSaved(subscriber, key.aggregateType(), resumeFrom);
        return true;
    }

    /**
     * Retry, shrink or park after a page rolled back (see docs/design/event-log.md §5.5). I/O: the same page again
     * after a doubling backoff, no shrink, no attempt. A
     * {@link ParkEventException} naming an event of the page: that event is parked by the page after the ones before it
     * (which commit in a page of their own). A handler failure of a page of several events: shrink; of an event
     * alone: retry after a doubling backoff, and once {@link EventLogSettings#retryAttempts()} retries are used up, park
     * it.
     */
    private void onPageFailure(Throwable e, boolean io, List<StoredEvent> page, List<StoredEvent> handlerPage) throws InterruptedException {
        var subscriber = key.subscriber();
        if (io) {
            var wait = errors.backoff(ioFailures++);
            log.warn("Subscription {} on {}: I/O failure in a page of {} events from global_order {}; retrying it after {}", subscriber, key.aggregateType(),
                     page.size(), page.getFirst().globalOrder(), wait, e);
            Thread.sleep(wait);
            return;
        }
        ioFailures = 0;
        if (e instanceof ParkEventException parkNow) {
            var named = parkNow.event() != null ? parkNow.event() : handlerPage.size() == 1 ? handlerPage.getFirst() : null;
            var index = named == null ? -1 : indexOf(page, named.globalOrder());
            if (index >= 0 && handlerPage.stream().anyMatch(event -> event.globalOrder() == named.globalOrder())) {
                var event = page.get(index);
                toPark.put(event.globalOrder(), parkRow(event, attemptsAfterFailure(event), parkNow));
                pageCut = index;
                log.info("Subscription {} on {}: the handler asked to park global_order {}", subscriber, key.aggregateType(), event.globalOrder());
                return;
            }
        }
        if (handlerPage.size() == 1 || seams.has(NO_SHRINK)) {
            var event    = handlerPage.getFirst();
            var attempts = attemptsAfterFailure(event);
            if (retriesUsedUp(attempts, errors)) {
                log.warn("Subscription {} on {}: handler failed {} times on global_order {} alone; parking it", subscriber, key.aggregateType(), attempts,
                         event.globalOrder(), e);
                toPark.put(event.globalOrder(), parkRow(event, attempts, e));
                return;
            }
            var wait = errors.backoff(attempts - 1);
            log.warn("Subscription {} on {}: handler failed on global_order {} alone (attempt {}); retrying after {}", subscriber, key.aggregateType(),
                     event.globalOrder(), attempts, wait, e);
            Thread.sleep(wait);
            return;
        }
        stepDown.failed(page.getFirst().globalOrder(), page.getLast().globalOrder(), page.size());
        log.debug("Subscription {} on {}: a page of {} events from global_order {} failed; next page {} events", subscriber, key.aggregateType(), page.size(),
                  page.getFirst().globalOrder(), stepDown.size(), e);
    }

    private static int indexOf(List<StoredEvent> page, long globalOrder) {
        for (var i = 0; i < page.size(); i++) {
            if (page.get(i).globalOrder() == globalOrder) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a failure is the engine's own I/O failure - retried indefinitely with a doubling backoff,
     * never shrinking, parking or counting an attempt. It is when it was thrown outside the handler (beginning the page,
     * the engine's statements, the commit), or the handler's page connection is lost. Whatever else the handler threw is
     * the handler's failure, even an I/O-classified one ({@link #isIoFailure}) from a connection of its own: it is retried
     * {@link ErrorSettings#retryAttempts()} times, then parked, so one event cannot stall the subscription forever.
     *
     * @param thrownByHandler    the failure came out of the handler call
     * @param error              the failure
     * @param pageConnectionLost whether the page connection is closed or invalid; asked only when {@code thrownByHandler},
     *                           and only on the failure path
     * @param seams              mutant {@link WatermarkMutant#HANDLER_IO_RETRIED_FOREVER} restores the classification
     *                           before 0.61.0
     */
    static boolean isEngineFailure(boolean thrownByHandler, Throwable error, java.util.function.BooleanSupplier pageConnectionLost, TestSeams seams) {
        if (!thrownByHandler || isEnvironmentFailure(error)) {
            return true;
        }
        if (seams.has(HANDLER_IO_RETRIED_FOREVER) && isIoFailure(error)) {
            return true;
        }
        return pageConnectionLost.getAsBoolean();
    }

    /**
     * An {@link Error} that says nothing about the event: the JVM is in trouble ({@link OutOfMemoryError},
     * {@link InternalError}, {@link UnknownError}) or the deployment is broken ({@link LinkageError}, e.g.
     * {@code NoClassDefFoundError}). Retried like the engine's own I/O, never parked - parking would need every such event
     * released by hand after the restart or redeploy that fixes it. {@link StackOverflowError} stays a handler failure:
     * an event whose handling recurses forever is parked.
     */
    static boolean isEnvironmentFailure(Throwable error) {
        var depth = 0;
        for (var t = error; t != null && depth < 32; t = t.getCause(), depth++) {
            if (t instanceof OutOfMemoryError || t instanceof InternalError || t instanceof UnknownError || t instanceof LinkageError) {
                return true;
            }
        }
        return false;
    }

    /** The event has failed alone {@code attempts} times, its first try and every retry; parked once this holds */
    static boolean retriesUsedUp(int attempts, ErrorSettings errors) {
        return attempts > errors.retryAttempts();
    }

    /**
     * The cause chain holds an {@code SQLException} of class {@code 08} (connection exception) or a
     * {@link SQLTransientConnectionException}; since 0.61.0 used only by mutant {@link WatermarkMutant#HANDLER_IO_RETRIED_FOREVER}
     */
    static boolean isIoFailure(Throwable error) {
        var depth = 0;
        for (var t = error; t != null && depth < 32; t = t.getCause(), depth++) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
        }
        return false;
    }

    /** The page connection is no longer valid */
    private static boolean invalid(Connection connection) {
        try {
            return connection.isClosed() || !connection.isValid(1);
        } catch (SQLException e) {
            return true;
        }
    }

    // ---- retry and discard of a parked aggregate ----

    private record ParkedRequest(long head, boolean discard, CompletableFuture<Boolean> result) {
    }

    /**
     * Retry the aggregate parked at {@code head}: on the delivery thread, between pages, one transaction locks the head's
     * and its held rows, hands their events to the handler in order, deletes the rows and runs the progress
     * compare-and-set unchanged; after it commits the aggregate is no longer held. If it fails the head's attempts grow
     * and the aggregate stays held.
     *
     * @return {@code true} once applied; {@code false} if it failed, {@code head} is not parked, or a reset intervened
     */
    CompletableFuture<Boolean> retryParked(long head) {
        return requestParked(head, false);
    }

    /**
     * As {@link #retryParked(long)}, but the head is deleted without being handed to the handler; the events held behind
     * it are applied
     */
    CompletableFuture<Boolean> discardParked(long head) {
        return requestParked(head, true);
    }

    private CompletableFuture<Boolean> requestParked(long head, boolean discard) {
        var request = new ParkedRequest(head, discard, new CompletableFuture<>());
        parkedRequests.add(request);
        // Read after the add, as the delivery thread clears the flag before its last drain: one of the two sees the request
        if (!acceptsParkedRequests) {
            failParkedRequests();
        }
        return request.result();
    }

    private void failParkedRequests() {
        ParkedRequest request;
        while ((request = parkedRequests.poll()) != null) {
            request.result().completeExceptionally(new IllegalStateException("Subscription " + key + " is not running"));
        }
    }

    /**
     * The parked check: while this subscription holds at least one aggregate, read the requests an operator
     * stored on its parked heads, at most once per {@link EventLogSettings#effectiveParkedCheckInterval()}, and queue them.
     * With no held aggregate there is no head to request anything on, and nothing is read
     */
    private void checkParkedRequests() throws SQLException {
        if (seams.has(PARKED_CHECK_SKIPPED) || (parked.heldCount() == 0 && !seams.has(PARKED_CHECK_ALWAYS))) {
            return;
        }
        var now = System.nanoTime();
        if (nextParkedCheckNanos != 0 && now - nextParkedCheckNanos < 0) {
            return;
        }
        nextParkedCheckNanos = now + settings.effectiveParkedCheckInterval().toNanos();
        parkedChecks++;
        for (var request : parked.requests()) {
            var queued = parkedRequests.stream().anyMatch(r -> r.head() == request.head());
            if (!queued) {
                parkedRequests.add(new ParkedRequest(request.head(), request.discard(), new CompletableFuture<>()));
            }
        }
    }

    /** @return the parked checks that read the table; for tests */
    long parkedChecks() {
        return parkedChecks;
    }

    /** Delivery thread, between pages: run the retry and discard requests, the stored ones first read by the parked check */
    private void serveParkedRequests() throws Exception {
        checkParkedRequests();
        ParkedRequest request;
        while ((request = parkedRequests.poll()) != null) {
            try {
                request.result().complete(releaseParked(request.head(), request.discard()));
            } catch (Exception | Error e) {
                request.result().completeExceptionally(e);
                throw e;
            }
        }
    }

    private boolean releaseParked(long head, boolean discard) throws Exception {
        ownerCheck();
        if (!running) {
            return false;
        }
        PageTransactionSource.OpenPage open = null;
        try {
            open = (model == ProgressModel.PERIODIC ? engineSource : pageSource).begin();
            var connection = open.connection();
            // The serve locks the progress row before the handler runs: the same lease bound as a page
            boundByLease(connection);
            // The progress row first, epoch- and fence-guarded, in the order a reset locks
            if (!seams.has(SERVE_WITHOUT_EPOCH)
                    && !ParkedEvents.lockProgress(connection, key, seams.has(SERVE_WITHOUT_EPOCH) ? null : (Long) resetEpoch,
                                                  seams.has(SERVE_WITHOUT_FENCE) ? null : boundFence())) {
                var ending = open;
                open = null;
                rollback(ending);
                onProgressConflict();
                return false;
            }
            var orders = parked.lock(connection, head);
            if (orders.isEmpty()) {
                var ending = open;
                open = null;
                rollback(ending);
                releaseHeld(head);
                return false;
            }
            var events = new ArrayList<StoredEvent>();
            try (var statement = connection.prepareStatement(statements.byOrders())) {
                statement.setArray(1, connection.createArrayOf("bigint", orders.toArray(Long[]::new)));
                try (var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        events.add(mapper.map(resultSet));
                    }
                }
            }
            var toHandle = discard ? events.stream().filter(event -> event.globalOrder() != head).toList() : events;
            if (model == ProgressModel.PERIODIC) {
                for (var event : toHandle) {
                    handler.handle(event);
                }
            } else if (!toHandle.isEmpty()) {
                pageHandler.handle(toHandle, handlerTransaction(open));
                failIfRollbackOnly(open);
            }
            parked.delete(connection, orders);
            var expected = model == ProgressModel.PERIODIC || seams.has(PROGRESS_WITHOUT_EXPECTED_RESUME) ? null : (Long) lastSaved;
            if (!ProgressStore.saveInTransaction(connection, key, lastSaved, seams.has(SERVE_WITHOUT_EPOCH) ? null : boundEpoch(),
                                                 seams.has(SERVE_WITHOUT_FENCE) ? null : boundFence(), expected)) {
                var ending = open;
                open = null;
                rollback(ending);
                onProgressConflict();
                return false;
            }
            if (pastLeaseDeadline()) {
                // As a page: past the local deadline the commit could land after another owner's claim
                var ending = open;
                open = null;
                rollback(ending);
                ownerCheck();
                return false;
            }
            var ending = open;
            open = null;
            commit(ending);
        } catch (TestSeams.Crash e) {
            // A lost JVM: no rollback, no close - the IT terminates the backend
            throw e;
        } catch (InterruptedException e) {
            // stop() interrupted the handler: never leave the serve transaction open, it holds the progress row
            if (open != null) {
                try {
                    rollback(open);
                } catch (RuntimeException rollbackFailed) {
                    log.debug("Rolling back an interrupted parked retry of {} failed", key, rollbackFailed);
                }
            }
            throw e;
        } catch (LeaseLostException e) {
            if (open != null) {
                try {
                    rollback(open);
                } catch (RuntimeException rollbackFailed) {
                    log.debug("Rolling back a parked retry of {} after a lost lease failed", key, rollbackFailed);
                }
            }
            throw e;
        } catch (Exception | Error e) {
            if (open != null) {
                try {
                    rollback(open);
                } catch (RuntimeException rollbackFailed) {
                    log.debug("Rolling back a parked retry of {} failed", key, rollbackFailed);
                }
            }
            reportError(e);
            log.warn("Subscription {} on {}: {} of the aggregate parked at global_order {} failed; it stays held", key.subscriber(), key.aggregateType(),
                     discard ? "discard" : "retry", head, e);
            try {
                parked.failedAgainGuarded(head, e, resetEpoch, boundFence());
            } catch (SQLException again) {
                log.warn("Subscription {} on {}: counting the failed attempt failed", key.subscriber(), key.aggregateType(), again);
            }
            return false;
        }
        releaseHeld(head);
        return true;
    }

    /**
     * Exclusive subscriptions: a dead owner's open transaction - a page or a parked retry, both holding the progress row -
     * cannot hold that row past one lease ({@code idle_in_transaction_session_timeout}, transaction-local)
     */
    private void boundByLease(Connection connection) throws SQLException {
        if (lease == null) {
            return;
        }
        try (var statement = connection.prepareStatement(EventLogStorage.PAGE_IDLE_TIMEOUT)) {
            statement.setString(1, settings.leaseDuration().toMillis() + "ms");
            statement.execute();
        }
    }

    /** @return whether the local lease deadline passed, so a transaction holding the progress row must roll back, not commit */
    private boolean pastLeaseDeadline() {
        return lease != null && !seams.has(NO_LOCAL_DEADLINE) && lease.pastDeadline();
    }

    private void releaseHeld(long head) {
        if (parked.release(head) != null) {
            heldChanged();
        }
    }

    /**
     * The resume point once the page is applied: {@code CONTIGUOUS} the new {@code A + 1} - the next pending
     * order, or {@code R + 1} if the page empties pending; {@code FRESH} the new {@code P + 1}
     *
     * @param next the first pending order after the page, or {@code null}
     */
    private long resumeAfterPage(Long next) {
        if (contiguous()) {
            return next == null ? receivedPrefix + 1 : next;
        }
        var prefix = next == null ? receivedWatermark : Math.min(receivedWatermark, next - 1);
        return Math.max(handled.prefix(), prefix) + 1;
    }

    /** Commit a page through its source; the connection is released either way */
    private void commit(PageTransactionSource.OpenPage open) throws SQLException {
        openPageConnection = null;
        open.commit();
    }

    /** Roll a page back through its source; an unchecked exception means the connection is unusable */
    private void rollback(PageTransactionSource.OpenPage open) {
        openPageConnection = null;
        open.rollback();
    }

    /**
     * What the handler sees of a page transaction: the connection guarded against ending the transaction,
     * and the source's resources
     */
    /** Thrown as the handler's own failure, so the page is retried and then parked like any failing page */
    private static void failIfRollbackOnly(PageTransactionSource.OpenPage open) {
        if (open.rollbackOnly()) {
            throw new IllegalStateException("The page handler marked the page's transaction rollback-only");
        }
    }

    private static PageTransaction handlerTransaction(PageTransactionSource.OpenPage open) {
        return PageTransaction.guarded(open);
    }

    /**
     * The progress update matched no row (not a handler failure), decided by
     * {@link #decide}: the row is gone - non-exclusive, recreate it and retry the page; exclusive, stop with an error.
     * Exclusive and the fence or owner differs - the lease was lost. Its epoch moved - a reset, reposition at its resume
     * point. Its resume point moved under the same epoch - another writer, reposition at its value
     */
    private void onProgressConflict() throws SQLException {
        var row = progress.read(key);
        switch (decide(lease != null, row, resetEpoch, lastSaved, ownedFence)) {
            case STOP -> {
                var error = new IllegalStateException("The progress row of exclusive subscription " + key + " is gone; it is never recreated, which would lose " +
                                                              "its resume point. The subscription stops");
                log.error(error.getMessage());
                reportError(error);
                lease.lost("progress row gone");
                ownedFence = null;
                running = false;
                throw new LeaseLostException(true);
            }
            case LOST -> {
                var stored = row.get();
                var reason = "fence " + (ownedFence == null ? null : ownedFence.fence()) + " -> " + stored.leaseFence() + " (owner " + stored.leaseOwner() + ")";
                if (lease.lost(reason)) {
                    log.warn("Exclusive subscription {} on {} lost its lease: ownership moved, {}", key.subscriber(), key.aggregateType(), reason);
                    observer.onLeaseLost(key.subscriber(), key.aggregateType(), reason);
                }
                throw new LeaseLostException(false);
            }
            case RECREATE -> {
                var current = resumePoint();
                log.warn("Progress row of {} is gone; recreating it at {}", key, current);
                var created = progress.start(key, false, mode.name(), model.name(), current, null, filterDigest);
                resetEpoch = created.resetEpoch();
                lastSaved = created.resumeFrom();
                // Whoever deleted the row (the admin's deleteRetiredInstance) deleted its parked rows with it: holding
                // aggregates behind heads that no longer exist would park their later events forever
                loadParked();
                if (created.resumeFrom() != current) {
                    repositionTo = created.resumeFrom();
                }
            }
            case RESET -> {
                log.info("Subscription {} on {} was reset (epoch {} -> {}); repositioning at {}", key.subscriber(), key.aggregateType(), resetEpoch,
                         row.get().resetEpoch(), row.get().resumeFrom());
                resetEpoch = row.get().resetEpoch();
                lastSaved = row.get().resumeFrom();
                repositionTo = row.get().resumeFrom();
            }
            case MOVED -> {
                log.error("Subscription {} on {} (instance '{}'): its resume point was moved from {} to {} by another writer - two subscriptions with the same " +
                                  "key? Repositioning at {}", key.subscriber(), key.aggregateType(), key.instanceName(), lastSaved, row.get().resumeFrom(),
                          row.get().resumeFrom());
                lastSaved = row.get().resumeFrom();
                repositionTo = row.get().resumeFrom();
            }
            case NONE -> {
                // nothing visible differs any more; the next page or save tries again
            }
        }
    }

    /**
     * One catch-up or deferred-bridge page {@code (lower, upper]}, or one {@code FRESH} bridge page with its re-read
     * holes, on a borrowed connection that is returned before any row is applied. Rows come back in {@code global_order}
     * order, each once.
     */
    private List<StoredEvent> read(boolean bridge, long lower, long upper, SortedSet<Long> holes, SortedMap<Long, Long> ranges) throws SQLException, InterruptedException {
        return read(bridge, lower, upper, holes, ranges, null);
    }

    /**
     * As {@link #read(boolean, long, long, SortedSet, SortedMap)}; with {@code typeIds} (a filtered {@code FRESH}
     * bridge) both branches carry the type predicate. Under a catch-up permit.
     */
    private List<StoredEvent> read(boolean bridge, long lower, long upper, SortedSet<Long> holes, SortedMap<Long, Long> ranges, Short[] typeIds)
            throws SQLException, InterruptedException {
        var started  = System.nanoTime();
        var rows     = new ArrayList<StoredEvent>();
        var plain    = holes == null || (holes.isEmpty() && ranges.isEmpty());
        var typed    = filtered;
        var f        = filterStatements;
        var sql      = typed ? plain ? f.rangeRead() : ranges.isEmpty() ? f.bridgePage() : f.bridgePageWithRanges()
                             : plain ? statements.rangeRead() : ranges.isEmpty() ? statements.bridgePage() : statements.bridgePageWithRanges();
        acquirePermit();
        try {
            try (var connection = loop.dataSource().getConnection(); var statement = connection.prepareStatement(sql)) {
                var p = 1;
                statement.setLong(p++, lower);
                statement.setLong(p++, upper);
                if (typed) {
                    p = bindFilter(connection, statement, p, typeIds);
                }
                statement.setInt(p++, settings.catchUpPageSize());
                if (!plain) {
                    statement.setArray(p++, connection.createArrayOf("bigint", holes.toArray(Long[]::new)));
                    if (typed) {
                        p = bindFilter(connection, statement, p, typeIds);
                    }
                    if (!ranges.isEmpty()) {
                        statement.setArray(p++, connection.createArrayOf("bigint", ranges.keySet().toArray(Long[]::new)));
                        statement.setArray(p++, connection.createArrayOf("bigint", ranges.values().toArray(Long[]::new)));
                        if (typed) {
                            bindFilter(connection, statement, p, typeIds);
                        }
                    }
                }
                try (var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        var row = mapper.map(resultSet);
                        // Re-read holes and ranges may overlap each other: each row once
                        if (rows.isEmpty() || rows.getLast().globalOrder() != row.globalOrder()) {
                            rows.add(row);
                        }
                    }
                }
            }
        } finally {
            releasePermit(false);
        }
        observer.onCatchUpRead(key.subscriber(), key.aggregateType(), bridge, System.nanoTime() - started, rows.size());
        return rows;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Filtered reads and the catch-up permit
    // ---------------------------------------------------------------------------------------------------------------

    /** One filtered read: the matching rows, and how far the position may advance (a final order) */
    private record FilteredPage(List<StoredEvent> rows, long advanceTo) {
    }

    /**
     * The filter's type ids: names with no id yet are left out; each lookup of an unknown name costs one reload.
     * Called after the ceiling is read, so every type that can occur at or below it has its id.
     */
    private Short[] resolveTypeIds() throws SQLException {
        if (!typeFiltered) {
            return null;
        }
        var ids = new TreeSet<Short>();
        for (var name : filter.eventTypes()) {
            var id = catchUp.eventTypes().knownIdOf(name);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids.toArray(Short[]::new);
    }

    /**
     * Early type ids: mutant {@link WatermarkMutant#TYPE_IDS_BEFORE_CEILING} resolves them before the watermark is read,
     * and the hold-point hook runs between the two steps; otherwise {@code null}
     */
    private Short[] typeIdsBeforeCeiling() throws Exception {
        if (!typeFiltered || !seams.has(TYPE_IDS_BEFORE_CEILING)) {
            return null;
        }
        var ids = resolveTypeIds();
        seams.hooks().afterCeilingBeforeTypeIds(key.subscriber(), -1);
        return ids;
    }

    /**
     * A filtered read of {@code (lower, min(final, lower + window)]}: {@code finalOrder} is a watermark (or
     * the deferred bridge's {@code min(W_rcv, C)}), so every order at or below it is final. A short page advances to the
     * ceiling, a full one to its last row; with a valid type index and a sparse type, a short page is followed by the
     * skip-ahead probe. A cancelled statement quarters the window and returns {@code null}: retry from {@code lower}.
     */
    private FilteredPage filteredRead(long lower, long finalOrder, Short[] earlyIds) throws Exception {
        var ceiling = window.ceiling(lower, finalOrder);
        Short[] ids;
        if (earlyIds != null) {
            ids = earlyIds;
        } else {
            seams.hooks().afterCeilingBeforeTypeIds(key.subscriber(), ceiling);
            ids = resolveTypeIds();
        }
        var dense     = window.dense();
        var usedIndex = !dense && ((typeFiltered && catchUp.indexValid().getAsBoolean()) || (tenant != null && catchUp.tenantIndexValid().getAsBoolean()));
        var rows      = List.<StoredEvent>of();
        // A type filter none of whose types has an id yet matches nothing at or below the ceiling
        var matchable = ids == null || ids.length > 0;
        if (matchable) {
            seams.hooks().beforeFilteredRead(key.subscriber(), lower, ceiling, ids == null ? Set.of() : Set.of(ids));
            try {
                rows = readFiltered(dense ? filterStatements.rangeReadPk() : filterStatements.rangeRead(), lower, ceiling, ids);
            } catch (SQLException e) {
                if ("57014".equals(e.getSQLState())) {
                    window.afterCancel();
                    log.debug("Subscription {} on {}: a filtered read of ({}, {}] was cancelled; retrying with a window of {}", key.subscriber(), key.aggregateType(), lower,
                              ceiling, window.window());
                    return null;
                }
                throw e;
            }
        }
        var full      = rows.size() >= settings.catchUpPageSize();
        var crawl     = !full && !rows.isEmpty() && seams.has(FILTERED_ADVANCE_TO_LAST_ROW);
        var advanceTo = full || crawl ? rows.getLast().globalOrder() : ceiling;
        if (!crawl) {
            window.afterRead(full, rows.size(), advanceTo - lower);
        }
        observer.onFilteredRead(key.subscriber(), key.aggregateType(), advanceTo - lower, rows.size(), window.window(), usedIndex && matchable);
        if (!full && !crawl) {
            if (seams.has(FILTERED_CATCH_UP_TO_CURSOR)) {
                var state = type.state();
                if (state != null) {
                    advanceTo = Math.max(advanceTo, state.cursor());
                }
            } else if (advanceTo < finalOrder && usedIndex && matchable) {
                var next = skipAhead(ids, advanceTo, finalOrder);
                advanceTo = next == null ? finalOrder : Math.max(advanceTo, next - 1);
            }
        }
        return new FilteredPage(rows, advanceTo);
    }

    private List<StoredEvent> readFiltered(String sql, long lower, long ceiling, Short[] ids) throws SQLException, InterruptedException {
        var started = System.nanoTime();
        var rows    = new ArrayList<StoredEvent>();
        acquirePermit();
        try (var connection = loop.dataSource().getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lower);
            statement.setLong(2, ceiling);
            var p = bindFilter(connection, statement, 3, ids);
            statement.setInt(p, settings.catchUpPageSize());
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
            }
        } finally {
            releasePermit(false);
        }
        observer.onCatchUpRead(key.subscriber(), key.aggregateType(), false, System.nanoTime() - started, rows.size());
        return rows;
    }

    /** The first matching order in {@code (lower, upper]}, or {@code null} */
    private Long skipAhead(Short[] ids, long lower, long upper) throws SQLException, InterruptedException {
        acquirePermit();
        try (var connection = loop.dataSource().getConnection(); var statement = connection.prepareStatement(filterStatements.skipAheadProbe())) {
            var p = bindFilter(connection, statement, 1, ids);
            statement.setLong(p++, lower);
            statement.setLong(p, upper);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        } finally {
            releasePermit(false);
        }
    }

    /**
     * Bind the filter predicate's parameters at {@code p}: the type ids when the filter has event types, then the tenant
     * when it has one ({@link EventLogStorage.FilterStatements})
     *
     * @return the next parameter index
     */
    private int bindFilter(java.sql.Connection connection, java.sql.PreparedStatement statement, int p, Short[] typeIds) throws SQLException {
        if (typeFiltered) {
            statement.setArray(p++, connection.createArrayOf("int2", typeIds));
        }
        if (tenant != null) {
            statement.setString(p++, tenant);
        }
        return p;
    }

    /** @return whether {@code row} passes the filter in memory: its event type, and its tenant */
    private boolean matches(StoredEvent row) {
        return filter.matches(row.eventType()) && (tenant == null || tenant.equals(row.tenant()));
    }

    /** Before a covered statement borrows its connection. Fair, interruptible */
    private void acquirePermit() throws InterruptedException {
        if (seams.has(NO_CATCH_UP_PERMIT)) {
            return;
        }
        if (permitHeld) {
            // PERMIT_HELD_THROUGH_PAGE: the previous read's permit is still held; never take a second one
            return;
        }
        var waited = permits.acquire();
        try {
            seams.hooks().onPermit(key.subscriber(), true);
        } catch (Exception e) {
            permits.release();
            throw new IllegalStateException("onPermit hook failed", e);
        }
        observer.onCatchUpPermit(key.subscriber(), key.aggregateType(), waited, permits.inUse());
    }

    /**
     * After the result set is closed and the connection returned. {@code applied}: the rows read were applied
     * (only mutant {@link WatermarkMutant#PERMIT_HELD_THROUGH_PAGE} waits for that)
     */
    private void releasePermit(boolean applied) {
        if (seams.has(NO_CATCH_UP_PERMIT)) {
            return;
        }
        if (seams.has(PERMIT_HELD_THROUGH_PAGE)) {
            if (!applied) {
                permitHeld = true;
                return;
            }
            if (!permitHeld) {
                return;
            }
        } else if (applied) {
            return;
        }
        permitHeld = false;
        try {
            seams.hooks().onPermit(key.subscriber(), false);
        } catch (Exception e) {
            log.debug("onPermit hook failed", e);
        }
        permits.release();
    }

    /**
     * {@code FRESH}: {@code P + 1}. {@code CONTIGUOUS}: {@code A + 1}
     */
    long resumePoint() {
        if (contiguous()) {
            return appliedPrefix() + 1;
        }
        if (seams.has(RESUME_AT_HIGHEST_HANDLED)) {
            return highestHandled >= 0 ? highestHandled + 1 : handled.resumeFrom();
        }
        var state = type == null ? null : type.state();
        if (seams.has(RESUME_AT_CURSOR)) {
            return state == null ? handled.resumeFrom() : Math.max(handled.resumeFrom(), state.cursor() + 1);
        }
        if (seams.has(RESUME_FROM_READER_WATERMARK)) {
            // The reader's live state: published inside its read, before the read's batch is offered
            var reader = type == null ? null : type.reader();
            var live   = reader == null ? null : reader.state();
            if (live == null || live.watermark() == null) {
                return handled.resumeFrom();
            }
            var resume = Math.max(handled.resumeFrom(), live.watermark() + 1);
            return pending.isEmpty() ? resume : Math.min(resume, pending.peekFirst().globalOrder());
        }
        return handled.resumeFrom();
    }

    private void maybeSave() throws Exception {
        if (System.nanoTime() - lastSaveNanos >= settings.progressSaveInterval().toNanos()) {
            saveProgressNow();
        }
    }

    /**
     * Save the resume point if it moved. Delivery thread only. In-transaction, this is a save without a page (the
     * received watermark moved), still guarded by the value last committed.
     */
    void saveProgressNow() throws Exception {
        lastSaveNanos = System.nanoTime();
        var resume = resumePoint();
        if (resume == lastSaved) {
            return;
        }
        boolean saved;
        if (model == ProgressModel.PERIODIC) {
            ownerCheck();
            if (!running && lease != null) {
                return;
            }
            seams.hooks().beforePeriodicSave(key.subscriber(), resume);
            saved = progress.save(key, resume, boundEpoch(), boundFence());
        } else {
            saved = progress.saveExpected(key, resume, boundEpoch(), boundFence(), seams.has(PROGRESS_WITHOUT_EXPECTED_RESUME) ? null : lastSaved);
        }
        if (saved) {
            lastSaved = resume;
            observer.onProgressSaved(key.subscriber(), key.aggregateType(), resume);
            return;
        }
        onProgressConflict();
    }
}
