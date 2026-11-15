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
    private final Map<String, ActiveType>            active    = new HashMap<>();
    /** Guarded by {@code this} */
    private Thread                                   thread;

    // ---- loop thread only ----
    private final List<ActiveType>                   types     = new ArrayList<>();
    private Connection                               connection;
    private long                                     reconnectAtNanos;
    /** The loop's connection, published for tests that terminate its backend */
    private volatile Connection                      published;

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
     */
    record AttachReply(boolean accepted, long cursor, long watermark, SortedSet<Long> holes, SortedMap<Long, Long> ranges) {
        static final AttachReply REFUSED = new AttachReply(false, 0, 0, Collections.emptySortedSet(), Collections.emptySortedMap());
    }

    private sealed interface Request permits Activate, Leave, Attach, Detach, Wake {
    }

    private record Activate(ActiveType type) implements Request {
    }

    /** A subscription stopped; {@code last} deactivates the type */
    private record Leave(ActiveType type, Inbox inbox, boolean last) implements Request {
    }

    private record Attach(ActiveType type, Inbox inbox, String subscriber, TestSeams.Hooks hooks, CompletableFuture<AttachReply> reply) implements Request {
    }

    private record Detach(ActiveType type, Inbox inbox) implements Request {
    }

    private record Wake(ActiveType type) implements Request {
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
        /** Bumped whenever the watermark may have changed; guarded by {@link #signal} */
        private long                         version;

        // ---- loop thread only ----
        private final List<Inbox>            attached = new ArrayList<>();
        private long                         nextDueNanos;
        private Long                         lastPublishedWatermark;

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
                type = new ActiveType(aggregateType, statements, mapper, seams);
                active.put(aggregateType, type);
                submit(new Activate(type));
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
        }
    }

    /**
     * Ask to attach {@code inbox} at the next quiescent point.
     */
    CompletableFuture<AttachReply> attach(ActiveType type, Inbox inbox, String subscriber, TestSeams.Hooks hooks) {
        var reply = new CompletableFuture<AttachReply>();
        submit(new Attach(type, inbox, subscriber, hooks, reply));
        return reply;
    }

    /** Remove {@code inbox} from the attached list at the next quiescent point */
    void detach(ActiveType type, Inbox inbox) {
        submit(new Detach(type, inbox));
    }

    /** Read {@code type} now instead of at its next due time */
    void wake(ActiveType type) {
        submit(new Wake(type));
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
                    for (var type : types) {
                        if (connection == null) {
                            break;
                        }
                        if (type.reader != null && now - type.nextDueNanos >= 0) {
                            read(type);
                        }
                    }
                    for (var type : types) {
                        next = next - type.nextDueNanos > 0 ? type.nextDueNanos : next;
                    }
                }
                park(next - System.nanoTime());
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
                case Detach detach -> detach.type().attached.remove(detach.inbox());
                case Wake wake -> wake.type().nextDueNanos = System.nanoTime();
            }
        }
    }

    private void attach(Attach request) {
        var type   = request.type();
        var reader = type.reader;
        var watermark = reader == null || !types.contains(type) ? null : reader.watermark();
        if (watermark == null) {
            request.reply().complete(AttachReply.REFUSED);
            return;
        }
        var cursor = reader.cursor();
        type.attached.add(request.inbox());
        try {
            request.hooks().onAttach(request.subscriber(), cursor, watermark);
        } catch (Exception e) {
            log.warn("[{}] onAttach hook failed", type.aggregateType, e);
        }
        observer.onAttach(request.subscriber(), type.aggregateType, cursor, watermark);
        request.reply().complete(new AttachReply(true, cursor, watermark, reader.holes(), reader.ranges()));
    }

    private void read(ActiveType type) throws Exception {
        var reader = type.reader;
        var rows   = reader.poll();
        type.seams.hooks().afterReadBeforePublish(type.aggregateType, rows);
        offer(type, rows, reader.watermark());
        type.publish(reader.state());
        var now = System.nanoTime();
        if (reader.readAgainNow(rows.size())) {
            type.nextDueNanos = now;
        } else if (reader.hasOutstandingCandidates()) {
            type.nextDueNanos = now + settings.probeWait(reader.lastReadNanos(), reader.lastBackends()).toNanos();
        } else {
            type.nextDueNanos = now + settings.backstopInterval().toNanos();
        }
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
            switch (inbox.offer(batch)) {
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
        LockSupport.parkNanos(this, nanos);
        woken.set(false);
    }
}
