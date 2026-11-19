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

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The shared tail reader of one {@link EventLog} (design phase 3 §2, decision 1): one platform thread and one held
 * connection serve every active aggregate type, each with its own {@link TailReader} state machine and its own list of
 * attached {@link Inbox}es.
 * <ul>
 *     <li><b>Iteration</b> (§2.2): drain the request queue - the <b>quiescent point</b>, where no read is in flight and
 *     every row read has been offered, so attach and detach are race-free; read each due type and offer the
 *     {@link Batch} to its attached inboxes; park until the earliest due time or a request.</li>
 *     <li><b>Publishing</b> (§2.3): never waits. A refused offer is an overflow: the inbox is closed with
 *     {@link Inbox.CloseReason#OVERFLOW} and removed; a closed inbox is removed.</li>
 *     <li><b>Types</b> (§2.4): a type becomes active with its first subscription and inactive with its last; the thread
 *     and connection start with the first active type and end with the last. Every reader starts at the head (F4).</li>
 *     <li><b>Restart</b> (§2.4): a failed read or start closes the connection, closes every attached inbox with
 *     {@link Inbox.CloseReason#READER_RESTART}, discards every reader, and after one backstop interval reconnects and
 *     starts every active type at the head again.</li>
 * </ul>
 * Everything not marked otherwise is touched by the loop's thread only; others reach it through requests.
 */
final class ReaderLoop {
    private static final Logger log = LoggerFactory.getLogger(ReaderLoop.class);

    /** The {@code subscriber} reported to {@link EventLogObserver#onError} for a failure of the reader itself */
    static final String READER = "eventlog-reader";

    private final DataSource                         dataSource;
    private final EventLogSettings                   settings;
    private final EventLogObserver                   observer;
    private final ConcurrentLinkedQueue<Request>     requests  = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean                      woken     = new AtomicBoolean();
    /** The current activation of each active aggregate type; guarded by {@code this} */
    private final Map<String, ActiveType>            active    = new ConcurrentHashMap<>();
    /** Written under {@code this} */
    private volatile Thread                          thread;

    // ---- loop thread only ----
    private final List<ActiveType>                   types     = new ArrayList<>();
    private Connection                               connection;
    private long                                     reconnectAtNanos;
    /** Multi-type flush statements by text (phase 6 §4.2), least recently used first */
    private final LinkedHashMap<String, PreparedStatement> flushes = new LinkedHashMap<>(16, 0.75f, true);
    /** The loop's connection, published for tests that terminate its backend */
    private volatile Connection                      published;
    /** Told when the first type becomes active ({@code true}) and the last one inactive; under {@code this} (phase 6 §3.3) */
    private volatile java.util.function.Consumer<Boolean> activity = any -> {
    };

    ReaderLoop(DataSource dataSource, EventLogSettings settings, EventLogObserver observer) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.observer = requireNonNull(observer, "No observer provided");
    }

    /**
     * The reply to an attach request, given at the quiescent point.
     *
     * @param accepted  {@code false} when the type's watermark is unknown (F4) or its reader is not running
     * @param cursor    {@code C}, the reader's cursor: the bridge reads {@code (P, C]}
     * @param watermark {@code W}, the reader's watermark
     * @param holes     the unresolved holes, all in {@code (W, C]}
     * @param ranges    the unresolved ranges (low to high, inclusive), all in {@code (W, C]}
     * @param unknownWatermark {@code true} if attached while the watermark is unknown (design phase 5 §6.3): {@code watermark}
     *                  is then {@link Long#MIN_VALUE}, and the subscription hands its gaps off after the bridge
     */
    record AttachReply(boolean accepted, long cursor, long watermark, SortedSet<Long> holes, SortedMap<Long, Long> ranges, boolean unknownWatermark) {
        static final AttachReply REFUSED = new AttachReply(false, 0, 0, Collections.emptySortedSet(), Collections.emptySortedMap());

        AttachReply(boolean accepted, long cursor, long watermark, SortedSet<Long> holes, SortedMap<Long, Long> ranges) {
            this(accepted, cursor, watermark, holes, ranges, false);
        }
    }

    /** The answer to a hand-off (design phase 5 §6.3) */
    enum HandOffReply {
        /** The inbox is no longer attached (a reader restart closed it): nothing was handed off */
        REFUSED,
        /** The reader holds the gaps as holes of its restart's initial candidate */
        HELD,
        /** The initial candidate had retired: every handed gap is final */
        FINAL
    }

    private sealed interface Request permits Activate, Leave, Attach, Detach, HandOff {
    }

    private record HandOff(ActiveType type, Inbox inbox, String subscriber, TestSeams.Hooks hooks, List<HandledSet.Gap> gaps, CompletableFuture<HandOffReply> reply)
            implements Request {
    }

    private record Activate(ActiveType type) implements Request {
    }

    /** A subscription stopped; {@code last} deactivates the type */
    private record Leave(ActiveType type, Inbox inbox, boolean last) implements Request {
    }

    private record Attach(ActiveType type, Inbox inbox, String subscriber, TestSeams.Hooks hooks, boolean unknownAllowed, CompletableFuture<AttachReply> reply)
            implements Request {
    }

    private record Detach(ActiveType type, Inbox inbox) implements Request {
    }

    /**
     * One activation of an aggregate type: from its first subscription's start to its last one's stop. The reader,
     * statements, mapper and seams are those of the activating subscription.
     */
    static final class ActiveType {
        final String                         aggregateType;
        final EventLogStorage.TailStatements statements;
        final TailReader.RowMapper           mapper;
        final TestSeams                      seams;
        /** Subscriptions holding the activation; guarded by the loop */
        private int                          refs;
        /** The running reader, {@code null} while (re)starting; written by the loop thread */
        private volatile TailReader          reader;
        /** The last published reader state; survives a restart, since a watermark once read is final */
        private volatile TailReader.State    state;
        private final Object                 signal = new Object();
        /** Set by a wake (phase 6 §4.1), taken by the loop before it reads; any thread */
        private final AtomicBoolean          wake   = new AtomicBoolean();
        /** {@link WatermarkMutant#BACKSTOP_REARMED_ON_LISTENER_RESTART} only; any thread */
        private final AtomicBoolean          rearm  = new AtomicBoolean();
        /** Bumped whenever the watermark may have changed; guarded by {@link #signal} */
        private long                         version;

        // ---- loop thread only ----
        private final List<Inbox>            attached = new ArrayList<>();
        private long                         nextDueNanos;
        private Long                         lastPublishedWatermark;
        /** The probe clock (phase 6 §5): when the last running set was read, and what that read cost */
        private long                         lastProbeNanos;
        private long                         lastProbeCostNanos;
        /** A wake arrived since the last running-set read: the probe waits {@link EventLogSettings#wokenProbeWait} */
        private boolean                      wokenSinceProbe;
        /** The read about to run follows a wake */
        private boolean                      wokenRead;
        /** When the last read of this type started: the woken-read clock (phase 6 §10.8) */
        private long                         lastReadNanos;
        /** A wake was taken and its read deferred by {@link EventLogSettings#wokenReadMin()}; the next read serves it */
        private boolean                      wakePending;

        private ActiveType(String aggregateType, EventLogStorage.TailStatements statements, TailReader.RowMapper mapper, TestSeams seams) {
            this.aggregateType = aggregateType;
            this.statements = statements;
            this.mapper = mapper;
            this.seams = seams;
        }

        /** @return the reader's state after its last read, or {@code null} before the first reader started; any thread */
        TailReader.State state() {
            return state;
        }

        /** @return the current reader, or {@code null} while it (re)starts; for tests */
        TailReader reader() {
            return reader;
        }

        /** @return a value that changes whenever the watermark may have changed; any thread */
        long version() {
            synchronized (signal) {
                return version;
            }
        }

        /**
         * Wait until {@link #version()} differs from {@code seen}, at most {@code timeout}
         */
        void awaitChange(long seen, Duration timeout) throws InterruptedException {
            var deadline = System.nanoTime() + timeout.toNanos();
            synchronized (signal) {
                while (version == seen) {
                    var left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return;
                    }
                    TimeUnit.NANOSECONDS.timedWait(signal, left);
                }
            }
        }

        private void publish(TailReader.State newState) {
            var previous = state;
            state = newState;
            if (previous == null || newState == null || !Objects.equals(previous.watermark(), newState.watermark())) {
                bump();
            }
        }

        private void bump() {
            synchronized (signal) {
                version++;
                signal.notifyAll();
            }
        }
    }

    /**
     * @param activity told, under the loop's monitor and without blocking, when the first aggregate type becomes active
     *                 ({@code true}) and when the last one becomes inactive ({@code false}): the wake listener's lifetime
     */
    void onActivity(java.util.function.Consumer<Boolean> activity) {
        this.activity = requireNonNull(activity, "No activity provided");
    }

    DataSource dataSource() {
        return dataSource;
    }

    /**
     * A subscription starts: activate its aggregate type unless active. Never blocks on the loop.
     */
    ActiveType join(String aggregateType, EventLogStorage.TailStatements statements, TailReader.RowMapper mapper, TestSeams seams) {
        synchronized (this) {
            var type = active.get(aggregateType);
            if (type == null) {
                var first = active.isEmpty();
                type = new ActiveType(aggregateType, statements, mapper, seams);
                active.put(aggregateType, type);
                submit(new Activate(type));
                if (first) {
                    activity.accept(true);
                }
            }
            type.refs++;
            return type;
        }
    }

    /**
     * A subscription stops: detach its inbox (if any) and, for the last one, deactivate the type. Never blocks.
     */
    void leave(ActiveType type, Inbox inbox) {
        synchronized (this) {
            type.refs--;
            var last = type.refs == 0;
            if (last) {
                active.remove(type.aggregateType, type);
            }
            submit(new Leave(type, inbox, last));
            if (last && active.isEmpty()) {
                activity.accept(false);
            }
        }
    }

    /**
     * Ask to attach {@code inbox} at the next quiescent point.
     */
    CompletableFuture<AttachReply> attach(ActiveType type, Inbox inbox, String subscriber, TestSeams.Hooks hooks) {
        return attach(type, inbox, subscriber, hooks, false);
    }

    /**
     * Ask to attach {@code inbox} at the next quiescent point; with {@code unknownAllowed} also while the watermark is
     * unknown (design phase 5 §6.3, {@code attachUnknown}), the reply then saying so
     */
    CompletableFuture<AttachReply> attach(ActiveType type, Inbox inbox, String subscriber, TestSeams.Hooks hooks, boolean unknownAllowed) {
        var reply = new CompletableFuture<AttachReply>();
        submit(new Attach(type, inbox, subscriber, hooks, unknownAllowed, reply));
        return reply;
    }

    /**
     * Hand the gaps a bridge under the unknown watermark found to the reader, at the next quiescent point (design phase 5
     * §6.3)
     */
    CompletableFuture<HandOffReply> handOff(ActiveType type, Inbox inbox, String subscriber, TestSeams.Hooks hooks, List<HandledSet.Gap> gaps) {
        var reply = new CompletableFuture<HandOffReply>();
        submit(new HandOff(type, inbox, subscriber, hooks, List.copyOf(gaps), reply));
        return reply;
    }

    /** Remove {@code inbox} from the attached list at the next quiescent point */
    void detach(ActiveType type, Inbox inbox) {
        submit(new Detach(type, inbox));
    }

    /**
     * Read {@code type} now instead of at its next due time (phase 6 §4.1): set its wake flag and unpark the loop. Never
     * blocks, never starts the loop, moves no reader state; any thread
     */
    void wake(ActiveType type) {
        type.wake.set(true);
        woken.set(true);
        var t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /**
     * {@link #wake(ActiveType)} for {@code aggregateType} if it is active; nothing otherwise (tier 2, phase 6 §2.2)
     */
    void wake(String aggregateType) {
        var type = active.get(aggregateType);
        if (type != null) {
            wake(type);
        }
    }

    /** Wake every active type once: the wake listener restarted and may have missed notifications (phase 6 §3.3); any thread */
    void wakeAll() {
        for (var type : active.values()) {
            wake(type);
        }
    }

    /**
     * {@link WatermarkMutant#BACKSTOP_REARMED_ON_LISTENER_RESTART}: push every active type's next read a full backstop
     * interval out instead of waking it; any thread
     */
    void rearmBackstops() {
        for (var type : active.values()) {
            type.rearm.set(true);
        }
        woken.set(true);
        var t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /** @return the current reader of {@code aggregateType}, {@code null} if the type is inactive or restarting; for tests */
    TailReader reader(String aggregateType) {
        synchronized (this) {
            var type = active.get(aggregateType);
            return type == null ? null : type.reader;
        }
    }

    /** @return the loop's connection, {@code null} while it has none; for tests that terminate its backend */
    Connection connection() {
        return published;
    }

    /** @return whether the loop's thread runs */
    boolean isRunning() {
        synchronized (this) {
            return thread != null;
        }
    }

    /**
     * Wait for the thread to end, which it does once no type is active
     *
     * @return {@code true} if it ended
     */
    boolean awaitStopped(Duration timeout) throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t == null) {
            return true;
        }
        return t.join(timeout);
    }

    private void submit(Request request) {
        synchronized (this) {
            requests.add(request);
            if (thread == null) {
                thread = Thread.ofPlatform().name("eventlog-reader").daemon().start(this::run);
            }
        }
        woken.set(true);
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private void run() {
        log.debug("Reader loop starts");
        while (true) {
            try {
                drainRequests();
                synchronized (this) {
                    if (types.isEmpty() && requests.isEmpty()) {
                        closeConnection();
                        thread = null;
                        log.debug("Reader loop ends: no active aggregate type");
                        return;
                    }
                }
                if (connection == null && System.nanoTime() - reconnectAtNanos >= 0) {
                    connect();
                }
                var now  = System.nanoTime();
                var next = now + settings.backstopInterval().toNanos();
                if (connection == null) {
                    next = reconnectAtNanos;
                } else {
                    readDue(now);
                    for (var type : types) {
                        next = next - type.nextDueNanos > 0 ? type.nextDueNanos : next;
                    }
                    if (connection != null && next - System.nanoTime() > 0 && parkUntilWoken()) {
                        next = Long.MAX_VALUE;
                    }
                }
                park(next == Long.MAX_VALUE ? Long.MAX_VALUE : next - System.nanoTime());
            } catch (Exception e) {
                restart(e);
            }
        }
    }

    private void drainRequests() {
        Request request;
        while ((request = requests.poll()) != null) {
            switch (request) {
                case Activate activate -> {
                    var type = activate.type();
                    types.add(type);
                    type.nextDueNanos = System.nanoTime();
                    if (connection != null) {
                        try {
                            startReader(type);
                        } catch (Exception e) {
                            restart(e);
                        }
                    }
                }
                case Leave leave -> {
                    var type = leave.type();
                    if (leave.inbox() != null) {
                        type.attached.remove(leave.inbox());
                    }
                    if (leave.last()) {
                        types.remove(type);
                        for (var inbox : type.attached) {
                            inbox.close(Inbox.CloseReason.STOPPED);
                        }
                        type.attached.clear();
                        closeReader(type);
                    }
                }
                case Attach attach -> attach(attach);
                case HandOff handOff -> handOff(handOff);
                case Detach detach -> detach.type().attached.remove(detach.inbox());
            }
        }
    }

    private void attach(Attach request) {
        var type   = request.type();
        var reader = type.reader;
        var watermark = reader == null || !types.contains(type) ? null : reader.watermark();
        if (watermark == null && reader != null && types.contains(type) && type.seams.has(ATTACH_WITH_UNKNOWN_WATERMARK)) {
            watermark = reader.cursor();
        }
        var unknown = watermark == null && request.unknownAllowed() && reader != null && types.contains(type);
        if (watermark == null && !unknown) {
            request.reply().complete(AttachReply.REFUSED);
            return;
        }
        long published = unknown ? Long.MIN_VALUE : watermark;
        var  cursor    = reader.cursor();
        type.attached.add(request.inbox());
        try {
            request.hooks().onAttach(request.subscriber(), cursor, published);
        } catch (Exception e) {
            log.warn("[{}] onAttach hook failed", type.aggregateType, e);
        }
        observer.onAttach(request.subscriber(), type.aggregateType, cursor, published);
        request.reply().complete(new AttachReply(true, cursor, published, reader.holes(), reader.ranges(), unknown));
    }

    /**
     * Design phase 5 §6.3: the gaps become holes of the reader's initial candidate if it is outstanding; else they are
     * final and the subscription's own re-read answers them. Refused if the inbox was detached meanwhile.
     */
    private void handOff(HandOff request) {
        var type   = request.type();
        var reader = type.reader;
        if (reader == null || !types.contains(type) || !type.attached.contains(request.inbox())) {
            request.reply().complete(HandOffReply.REFUSED);
            return;
        }
        try {
            request.hooks().onHandOff(type.aggregateType, request.gaps());
        } catch (Exception e) {
            log.warn("[{}] onHandOff hook failed", type.aggregateType, e);
        }
        var held = reader.handOff(request.gaps());
        type.nextDueNanos = System.nanoTime();
        observer.onGapsHandedOff(request.subscriber(), type.aggregateType, request.gaps().size(), held);
        log.debug("[{}] {} handed off {} gaps ({})", type.aggregateType, request.subscriber(), request.gaps().size(), held ? "held" : "final");
        request.reply().complete(held ? HandOffReply.HELD : HandOffReply.FINAL);
    }

    /**
     * Step 2 of the iteration (phase 6 §4): take every wake flag before reading, so a wake during the read causes
     * another; then read the due types - one alone with the combined probe or the tail (§9 decision 2), two or more in
     * one flush with at most one running set, read last (§4.2)
     */
    private void readDue(long now) throws Exception {
        var clearAfterRead = types.stream().anyMatch(type -> type.seams.has(WAKE_CLEARED_AFTER_READ));
        var due            = new ArrayList<ActiveType>();
        for (var type : types) {
            if (type.rearm.getAndSet(false)) {
                type.nextDueNanos = now + settings.backstopInterval().toNanos();
            }
            var woken = clearAfterRead ? type.wake.get() : type.wake.getAndSet(false);
            if (woken) {
                type.wokenSinceProbe = true;
                var at = wokenReadDueNanos(type, now);
                if (at - now > 0 && type.seams.has(WOKEN_READ_DROPPED_INSIDE_INTERVAL)) {
                    woken = false;
                } else {
                    if (type.nextDueNanos - at > 0) {
                        type.nextDueNanos = at;
                    }
                    type.wakePending = true;
                }
            }
            if (type.reader != null && now - type.nextDueNanos >= 0) {
                type.wokenRead = type.wakePending;
                type.wakePending = false;
                type.lastReadNanos = now;
                due.add(type);
            }
        }
        var alone = due.size() > MAX_FLUSH_TYPES || !flushForTests || (due.size() == 1 && !singleTypeFlushForTests);
        if (alone) {
            for (var type : due) {
                if (connection == null) {
                    break;
                }
                readAlone(type);
            }
        } else if (!due.isEmpty()) {
            flush(due);
        }
        if (clearAfterRead) {
            for (var type : due) {
                type.wake.set(false);
            }
        }
    }

    /**
     * Wake coalescing (phase 6 §10.8): a woken read starts no sooner than {@link EventLogSettings#wokenReadMin()} after the
     * start of the type's last read of any kind - any read that starts after a wake serves it - and never later than the
     * due time the type already has, so the backstop still bounds it (I7). The wake is only deferred, never dropped: the
     * due time is lowered to this and {@link ActiveType#wakePending} carries the wake to that read. Per type, because the
     * due time is per type; types read in one flush share their read start, so they stay in step and keep sharing flushes.
     */
    private long wokenReadDueNanos(ActiveType type, long now) {
        var min = settings.wokenReadMin().toNanos();
        if (min == 0) {
            return now;
        }
        var at = type.lastReadNanos + min;
        return at - now > 0 ? at : now;
    }

    /** More due types than this are read one statement each, not in one flush: a guard against statement-cache churn */
    static final int MAX_FLUSH_TYPES = 16;

    // ---- test knobs (phase 6 §7.3 gate measurement only) ----
    /** {@code false} reads every due type alone, one statement each: §7.3's "flush off" arm */
    volatile boolean flushForTests = true;
    /** {@code true} reads a single due type through {@link #flush} (tail, then the running set if wanted) instead of the combined probe: §9 decision 2's figure */
    volatile boolean singleTypeFlushForTests;

    private void readAlone(ActiveType type) throws Exception {
        var reader  = type.reader;
        var allowed = probeAllowed(type, System.nanoTime());
        var names   = List.of(type.aggregateType);
        type.seams.hooks().beforeFlush(names, allowed && reader.wantsRunningSet());
        var read = reader.execute(allowed);
        type.seams.hooks().afterFlushBeforeApply(names);
        observer.onFlush(names, 1, read.nanos());
        finish(type, read);
    }

    /**
     * One pgjdbc flush (phase 6 §4.2): every due type's tail statement, then one {@code SELECT RUNNING_SET} if any of
     * them wants a running set and its probe clock allows. Statements before one Sync share an implicit transaction,
     * where PostgreSQL caches backend status (P0.2): so exactly one running set, read after every type's snapshot (I11)
     */
    private void flush(List<ActiveType> due) throws Exception {
        var now     = System.nanoTime();
        var running = false;
        for (var type : due) {
            running |= type.reader.wantsRunningSet() && probeAllowed(type, now);
        }
        var perType = running && due.stream().anyMatch(type -> type.seams.has(RUNNING_SET_PER_TYPE_IN_FLUSH));
        var first   = running && !perType && due.stream().anyMatch(type -> type.seams.has(RUNNING_SET_FIRST_IN_FLUSH));
        // Each part is a due type's index, or -1 for a running set
        var parts = new ArrayList<Integer>();
        var sql   = new StringBuilder();
        if (first) {
            parts.add(-1);
        }
        for (var k = 0; k < due.size(); k++) {
            parts.add(k);
            if (perType) {
                parts.add(-1);
            }
        }
        if (running && !first && !perType) {
            parts.add(-1);
        }
        for (var part : parts) {
            if (!sql.isEmpty()) {
                sql.append(";\n");
            }
            sql.append(part < 0 ? due.getFirst().statements.runningSet() : due.get(part).reader.flushStatement());
        }
        var names = due.stream().map(type -> type.aggregateType).toList();
        var hooks = new LinkedHashSet<TestSeams.Hooks>();
        due.forEach(type -> hooks.add(type.seams.hooks()));
        for (var hook : hooks) {
            hook.beforeFlush(names, running);
        }
        for (var type : due) {
            type.reader.beforeFlushRead(running);
        }

        var statement = flushStatement(sql.toString());
        var index     = 1;
        for (var type : due) {
            index = type.reader.bindFlushStatement(statement, index);
        }
        var rows = new ArrayList<List<StoredEvent>>();
        due.forEach(type -> rows.add(new ArrayList<>()));
        var sets    = new TailReader.RunningSet[due.size()];
        var started = System.nanoTime();
        var result  = statement.execute();
        var last    = -1;
        for (var part : parts) {
            if (!result) {
                throw new IllegalStateException("A flush of " + names + " returned fewer results than statements");
            }
            try (var resultSet = statement.getResultSet()) {
                if (part >= 0) {
                    due.get(part).reader.readRows(resultSet, rows.get(part));
                    last = part;
                } else {
                    resultSet.next();
                    var set = TailReader.RunningSet.of(resultSet.getArray(1));
                    for (var k = 0; k < due.size(); k++) {
                        // per type: the set after its own statement; otherwise one set for every type
                        if (!perType || k == last) {
                            sets[k] = set;
                        }
                    }
                }
            }
            result = statement.getMoreResults();
        }
        var nanos = System.nanoTime() - started;
        for (var hook : hooks) {
            hook.afterFlushBeforeApply(names);
        }
        observer.onFlush(names, parts.size(), nanos);
        for (var k = 0; k < due.size(); k++) {
            // a flush's wall time is every due type's tail read, not one probe's cost: only a flush of one type is costed
            finish(due.get(k), new TailReader.Read(rows.get(k), sets[k], nanos, sets[k] != null, due.size() == 1));
        }
    }

    private PreparedStatement flushStatement(String sql) throws SQLException {
        var statement = flushes.get(sql);
        if (statement == null) {
            statement = connection.prepareStatement(sql);
            statement.unwrap(org.postgresql.PGStatement.class).setPrepareThreshold(settings.genericPlans() ? 1 : 0);
            flushes.put(sql, statement);
            if (flushes.size() > MAX_FLUSH_TEXTS) {
                var eldest = flushes.entrySet().iterator();
                closeQuietly(eldest.next().getValue());
                eldest.remove();
            }
        }
        return statement;
    }

    /** Distinct flush texts kept prepared; the mixes of types and shapes that occur are few (phase 6 §4.2) */
    private static final int MAX_FLUSH_TEXTS = 64;

    private static void closeQuietly(Statement statement) {
        try {
            statement.close();
        } catch (SQLException e) {
            log.debug("Closing a flush statement failed", e);
        }
    }

    /**
     * The probe clock (phase 6 §5): a running set is read only once the last one is a probe wait old - the woken wait
     * if a wake arrived since, else the backstop-floored {@link EventLogSettings#probeWait}
     */
    private boolean probeAllowed(ActiveType type, long now) {
        if (type.wokenSinceProbe && type.seams.has(PROBE_ON_EVERY_WAKE)) {
            return true;
        }
        return now - probeDueNanos(type) >= 0;
    }

    private long probeDueNanos(ActiveType type) {
        var reader = type.reader;
        return type.lastProbeNanos + probeWait(settings, type.wokenSinceProbe, type.lastProbeCostNanos, reader.lastBackends(), reader.holesWaiting(), type.seams).toNanos();
    }

    /**
     * The wait before a type's next running set (the probe clock, phase 6 §5): the woken or the backstop-floored cost rule,
     * capped at {@link EventLogSettings#holeProbeWaitMax()} while a hole, range or candidate is outstanding (phase 10 gate):
     * such a hole retires only on a running-set read, and holds the watermark until it does
     * ({@link WatermarkMutant#HOLE_PROBE_WAIT_UNCAPPED})
     */
    static Duration probeWait(EventLogSettings settings, boolean woken, long lastProbeCostNanos, int backends, boolean holesWaiting, TestSeams seams) {
        var wait = woken ? settings.wokenProbeWait(lastProbeCostNanos, backends)
                         : settings.probeWait(lastProbeCostNanos, backends);
        return holesWaiting && !seams.has(HOLE_PROBE_WAIT_UNCAPPED) ? settings.holeProbeWait(wait) : wait;
    }

    /**
     * The probe cost a read leaves for the probe clock ({@link #probeDueNanos}): the read's own cost if it was a probe
     * measured on its own, else the previous cost. A flush of several types keeps the previous cost, as its wall time
     * multiplied by the cost rule's factor would push the next running set - and so every candidate's retirement and the
     * watermark - out by seconds under load ({@link WatermarkMutant#FLUSH_COUNTED_AS_PROBE_COST})
     */
    static long probeCostAfter(long previousCost, TailReader.Read read, TestSeams seams) {
        if (seams.has(FLUSH_COUNTED_AS_PROBE_COST) && read.combined()) {
            return read.nanos();
        }
        return read.probeCost(previousCost);
    }

    /** Apply one read of {@code type}, publish it, and set its next due time */
    private void finish(ActiveType type, TailReader.Read read) throws Exception {
        var reader = type.reader;
        var rows   = reader.apply(read, type.wokenRead);
        type.wokenRead = false;
        type.seams.hooks().afterReadBeforePublish(type.aggregateType, rows);
        offer(type, rows, reader.watermark());
        type.publish(reader.state());
        var now = System.nanoTime();
        if (read.combined()) {
            type.lastProbeNanos = now;
            type.lastProbeCostNanos = probeCostAfter(type.lastProbeCostNanos, read, type.seams);
            type.wokenSinceProbe = false;
        }
        if (read.rows().size() >= settings.tailPageSize()) {
            type.nextDueNanos = now;
        } else if (reader.wantsRunningSet()) {
            // A new hole, a hole or a candidate: the tail is read again when the probe clock allows a running set
            var probeDue = probeDueNanos(type);
            type.nextDueNanos = probeDue - now > 0 ? probeDue : now;
        } else {
            type.nextDueNanos = now + settings.backstopInterval().toNanos();
        }
    }

    /** {@link WatermarkMutant#PARK_UNTIL_WOKEN}: no deadline while no candidate is outstanding */
    private boolean parkUntilWoken() {
        return types.stream().anyMatch(type -> type.seams.has(PARK_UNTIL_WOKEN))
                && types.stream().noneMatch(type -> type.reader != null && type.reader.hasOutstandingCandidates());
    }

    /**
     * Offer one read's rows and watermark to every attached inbox, if there are rows or the watermark moved. Never
     * waits: an inbox that refuses overflows and is detached.
     */
    private void offer(ActiveType type, List<StoredEvent> rows, Long watermark) {
        if (rows.isEmpty() && Objects.equals(watermark, type.lastPublishedWatermark)) {
            return;
        }
        type.lastPublishedWatermark = watermark;
        if (type.attached.isEmpty()) {
            return;
        }
        var batch = new Batch(rows, watermark == null ? Long.MIN_VALUE : watermark);
        for (var it = type.attached.iterator(); it.hasNext(); ) {
            var inbox = it.next();
            var offered = inbox.offer(batch);
            while (offered == Inbox.Offer.FULL && type.seams.has(BACK_PRESSURE)) {
                LockSupport.parkNanos(this, 1_000_000);
                offered = inbox.offer(batch);
            }
            switch (offered) {
                case ACCEPTED -> {
                }
                case CLOSED -> it.remove();
                case FULL -> {
                    it.remove();
                    inbox.close(Inbox.CloseReason.OVERFLOW);
                    log.debug("[{}] An inbox overflowed ({} events held, batch of {}); detached", type.aggregateType, inbox.events(), batch.size());
                }
            }
        }
    }

    private void connect() {
        try {
            connection = dataSource.getConnection();
            published = connection;
            try (var statement = connection.createStatement()) {
                statement.execute("SET jit = off");
                if (settings.genericPlans()) {
                    statement.execute("SET plan_cache_mode = force_generic_plan");
                }
            }
            // The probe must run in autocommit (P0.2); the mutant keeps one transaction across probes
            connection.setAutoCommit(types.stream().noneMatch(type -> type.seams.has(CACHED_STAT_ACTIVITY)));
            for (var type : types) {
                startReader(type);
            }
        } catch (Exception e) {
            restart(e);
        }
    }

    private void startReader(ActiveType type) throws SQLException {
        var reader = new TailReader(type.aggregateType, connection, type.statements, settings, type.mapper, observer, type.seams,
                                    watermark -> offer(type, List.of(), watermark));
        reader.startAtHead();
        type.reader = reader;
        type.nextDueNanos = System.nanoTime();
        type.lastProbeNanos = type.nextDueNanos;
        type.lastProbeCostNanos = 0;
        type.wokenSinceProbe = false;
        type.lastReadNanos = type.nextDueNanos;
        type.publish(reader.state());
    }

    /**
     * §2.4: a reader restart detaches every attached subscription, since the new readers start at the head and have
     * forgotten the old holes
     */
    private void restart(Exception cause) {
        log.warn("The tail reader failed; detaching every attached subscription and restarting at the head after {}", settings.backstopInterval(), cause);
        closeConnection();
        for (var type : types) {
            observer.onError(READER, type.aggregateType, cause);
            if (!type.seams.has(KEEP_ATTACHED_ON_READER_RESTART)) {
                for (var inbox : type.attached) {
                    inbox.close(Inbox.CloseReason.READER_RESTART);
                }
                type.attached.clear();
                type.lastPublishedWatermark = null;
            }
            closeReader(type);
            type.bump();
        }
        reconnectAtNanos = System.nanoTime() + settings.backstopInterval().toNanos();
    }

    private void closeReader(ActiveType type) {
        var reader = type.reader;
        type.reader = null;
        if (reader != null) {
            reader.close();
        }
    }

    private void closeConnection() {
        for (var type : types) {
            closeReader(type);
        }
        published = null;
        flushes.values().forEach(ReaderLoop::closeQuietly);
        flushes.clear();
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                try (var statement = connection.createStatement()) {
                    statement.execute("RESET plan_cache_mode");
                    statement.execute("RESET jit");
                }
            }
        } catch (SQLException e) {
            log.debug("Resetting the reader loop's connection failed", e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Closing the reader loop's connection failed", e);
        }
        connection = null;
    }

    private void park(long nanos) {
        if (nanos <= 0 || woken.getAndSet(false)) {
            return;
        }
        if (nanos == Long.MAX_VALUE) {
            LockSupport.park(this);
        } else {
            LockSupport.parkNanos(this, nanos);
        }
        woken.set(false);
    }
}
