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

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The subscription of phases 2 and 3: one handler, {@code FRESH}, periodic progress, non-exclusive, backstop wake-up
 * only. <b>Internal and temporary</b>: the phase-8 subscription API replaces it; its public surface is phase 2's.
 * <p>
 * Phase 3 (design {@code docs/plans/event-store-engine-phase3.md} §3): the tail reader is the {@link EventLog}'s shared
 * {@link ReaderLoop}; this subscription holds no connection and runs on its own virtual delivery thread:
 * <ol>
 *     <li><b>Catch-up</b> {@code (P, W]} below the reader's watermark {@code W}, paged, each page on a borrowed
 *     connection. While {@code W} is unknown (F4) it reads nothing and <b>waits for the watermark</b>.</li>
 *     <li><b>Attach</b>: once {@code P} reached {@code W}, a request to the loop, answered at its quiescent point with
 *     the cursor {@code C}, the watermark and the unresolved holes in {@code (W, C]}; from then on every batch reaches
 *     the inbox.</li>
 *     <li><b>Bridge</b> {@code (P, C]}, paged, each page re-reading the attach holes at or below its lower bound
 *     ({@code UNION ALL}), so per-aggregate order holds across pages (I2).</li>
 *     <li><b>Attached</b>: batches from the inbox. An overflow or a reader restart closes the inbox; the subscription
 *     <b>detaches</b> at the F5 position and catches up again.</li>
 * </ol>
 * Every row goes through one rule ({@link HandledSet}): a row already handled is dropped. The resume point is
 * {@code P + 1}, where {@code P = min(received watermark, lowest received-but-unhandled - 1)} (§3.2) - never the
 * reader's live watermark. Saved at most every {@link EventLogSettings#progressSaveInterval()}, with the reset-epoch
 * compare-and-set; a superseding reset repositions the subscription at the reset's resume point. A handler that throws
 * is retried with the same event after the backstop interval; an interrupted handler is not handled. {@link #stop()}
 * saves once more.
 */
public final class FreshSubscription {
    private static final Logger log = LoggerFactory.getLogger(FreshSubscription.class);

    static final String MODE  = "FRESH";
    static final String MODEL = "PERIODIC";

    /**
     * Handles one event. Returning normally marks it handled; throwing retries it.
     */
    @FunctionalInterface
    public interface Handler {
        void handle(StoredEvent event) throws Exception;
    }

    /** Where the delivery thread is in the lifecycle (§3.1); for tests and diagnostics */
    enum Phase {
        STARTING,
        CATCHING_UP,
        WAITING_FOR_WATERMARK,
        ATTACHING,
        BRIDGING,
        ATTACHED,
        STOPPED
    }

    private final ProgressStore.Key              key;
    private final long                           initialResumeFrom;
    private final Handler                        handler;
    private final TestSeams                      seams;
    private final ReaderLoop                     loop;
    private final EventLogStorage.TailStatements statements;
    private final TailReader.RowMapper           mapper;
    private final ProgressStore                  progress;
    private final EventLogSettings               settings;
    private final EventLogObserver               observer;

    private final CountDownLatch                 started = new CountDownLatch(1);
    private volatile boolean                     running;
    private volatile boolean                     crashed;
    private volatile Throwable                   startFailure;
    private volatile Thread                      thread;
    private volatile ReaderLoop.ActiveType       type;
    private volatile Inbox                       inbox;
    private volatile Phase                       phase   = Phase.STARTING;
    private volatile long                        lastSaved;

    // ---- delivery thread only ----
    private HandledSet                           handled;
    /** {@code W_rcv}: the watermark received - a page's ceiling, the bridge's progress (at most {@code W}), a batch's */
    private long                                 receivedWatermark;
    /** The received rows not yet handled, in {@code global_order} order */
    private final ArrayDeque<StoredEvent>        remaining = new ArrayDeque<>();
    private long                                 resetEpoch;
    private long                                 lastSaveNanos;
    private long                                 highestHandled = -1;
    private Long                                 repositionTo;
    private boolean                              waitReported;

    FreshSubscription(SubscriptionSpec spec, ReaderLoop loop, EventLogStorage.TailStatements statements, TailReader.RowMapper mapper, ProgressStore progress,
                      EventLogSettings settings, EventLogObserver observer) {
        this.key = spec.key();
        this.initialResumeFrom = spec.initialResumeFrom();
        this.handler = spec.handler();
        this.seams = spec.seams();
        this.loop = requireNonNull(loop, "No loop provided");
        this.statements = requireNonNull(statements, "No statements provided");
        this.mapper = requireNonNull(mapper, "No mapper provided");
        this.progress = requireNonNull(progress, "No progress provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.observer = requireNonNull(observer, "No observer provided");
    }

    /**
     * What a subscription is: its progress key, where a new one starts, its handler and test seams
     */
    record SubscriptionSpec(ProgressStore.Key key, long initialResumeFrom, Handler handler, TestSeams seams) {
        SubscriptionSpec {
            requireNonNull(key, "No key provided");
            requireNonNull(handler, "No handler provided");
            requireNonNull(seams, "No seams provided");
            if (initialResumeFrom < 1) {
                throw new IllegalArgumentException("initialResumeFrom must be >= 1");
            }
        }
    }

    void start() {
        running = true;
        thread = Thread.ofVirtual().name("eventlog-" + key.aggregateType() + "-" + key.subscriber()).start(this::run);
        try {
            if (!started.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Subscription " + key + " did not start within 60 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        var t = thread;
        return running && t != null && t.isAlive();
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

    /**
     * @return whether the subscription has worked through everything it received and saved what it would save: waiting
     * for the watermark, or attached with an empty inbox it waits on; for tests
     */
    boolean isIdle() {
        if (phase == Phase.WAITING_FOR_WATERMARK) {
            return true;
        }
        var current = inbox;
        return phase == Phase.ATTACHED && current != null && current.consumerIdle();
    }

    /**
     * The last saved resume point, for tests
     */
    long lastSaved() {
        return lastSaved;
    }

    private void run() {
        try {
            try {
                var row = progress.start(key, false, MODE, MODEL, initialResumeFrom);
                resetEpoch = row.resetEpoch();
                lastSaved = row.resumeFrom();
                lastSaveNanos = System.nanoTime();
                handled = new HandledSet(row.resumeFrom() - 1);
                receivedWatermark = handled.prefix();
                type = loop.join(key.aggregateType(), statements, mapper, seams);
            } catch (Throwable e) {
                startFailure = e;
                return;
            } finally {
                started.countDown();
            }
            log.info("Subscription {} on {} (instance '{}') started from {}", key.subscriber(), key.aggregateType(), key.instanceName(), handled.resumeFrom());
            lifecycle();
        } finally {
            phase = Phase.STOPPED;
            var current = inbox;
            if (current != null) {
                current.close(Inbox.CloseReason.STOPPED);
            }
            if (!crashed && startFailure == null) {
                Thread.interrupted();
                try {
                    saveProgressNow();
                } catch (Exception e) {
                    log.warn("Final progress save of {} failed", key, e);
                }
            }
            if (type != null) {
                loop.leave(type, current);
            }
        }
    }

    private void lifecycle() {
        while (running) {
            try {
                if (repositionTo != null) {
                    reposition();
                }
                var reply = catchUpAndAttach();
                if (reply == null || !bridge(reply)) {
                    continue;
                }
                attached();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                observer.onError(key.subscriber(), key.aggregateType(), e);
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

    /**
     * Catch up {@code (P, W]} and attach (§3.3, §3.4 step 1-2)
     *
     * @return the accepted attach, or {@code null} if stopped or a reset is pending
     */
    private ReaderLoop.AttachReply catchUpAndAttach() throws Exception {
        while (running && repositionTo == null) {
            var version   = type.version();
            var state     = type.state();
            var watermark = state == null ? null : state.watermark();
            if (watermark == null) {
                waitForWatermark(version, state);
                continue;
            }
            phase = Phase.CATCHING_UP;
            if (handled.prefix() < watermark) {
                var rows = read(false, handled.prefix(), watermark, null, null);
                var full = rows.size() >= settings.catchUpPageSize();
                receivedWatermark = Math.max(receivedWatermark, full ? rows.getLast().globalOrder() : watermark);
                if (!work(rows)) {
                    return null;
                }
                if (full) {
                    continue;
                }
            }
            seams.hooks().beforeAttachRequest(key.subscriber());
            phase = Phase.ATTACHING;
            var candidate = new Inbox(settings.subscriptionBufferSize());
            inbox = candidate;
            var reply = loop.attach(type, candidate, key.subscriber(), seams.hooks()).get();
            if (reply.accepted()) {
                endWait();
                return reply;
            }
            inbox = null;
            waitForWatermark(version, type.state());
        }
        return null;
    }

    /**
     * WaitingForWatermark (§3.1): read nothing until the type's watermark may have changed (F4, decision 2)
     */
    private void waitForWatermark(long version, TailReader.State state) throws InterruptedException {
        phase = Phase.WAITING_FOR_WATERMARK;
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
        if (waitReported) {
            waitReported = false;
            observer.onWaitingForWatermark(key.subscriber(), key.aggregateType(), false, Set.of());
        }
    }

    /**
     * Bridge {@code (P, C]} (§3.4 step 3): each page also re-reads the attach holes at or below its lower bound that the
     * bridge has not returned yet, so a late commit into one is handled before its successors in later pages
     *
     * @return {@code true} once the bridge reached {@code C}; {@code false} if stopped, reset or detached
     */
    private boolean bridge(ReaderLoop.AttachReply reply) throws Exception {
        phase = Phase.BRIDGING;
        var cursor = reply.cursor();
        var holes  = new TreeSet<>(reply.holes());
        var ranges = new TreeMap<>(reply.ranges());
        var lower  = seams.has(ATTACH_WITHOUT_BRIDGE) ? cursor : handled.prefix();
        var first  = true;
        while (lower < cursor) {
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
            var rereadHoles  = seams.has(BRIDGE_WITHOUT_HOLE_REREAD) ? new TreeSet<Long>() : new TreeSet<>(holes.headSet(lower, true));
            var rereadRanges = new TreeMap<Long, Long>();
            if (!seams.has(BRIDGE_WITHOUT_HOLE_REREAD)) {
                for (var range : ranges.headMap(lower, true).entrySet()) {
                    rereadRanges.put(range.getKey(), Math.min(range.getValue(), lower));
                }
            }
            var rows    = read(true, lower, cursor, rereadHoles, rereadRanges);
            var newRows = 0;
            var highest = lower;
            for (var row : rows) {
                holes.remove(row.globalOrder());
                if (row.globalOrder() > lower) {
                    newRows++;
                    highest = Math.max(highest, row.globalOrder());
                }
            }
            var ceiling = newRows >= settings.catchUpPageSize() ? highest : cursor;
            receivedWatermark = Math.max(receivedWatermark, Math.min(ceiling, reply.watermark()));
            if (!work(rows)) {
                return false;
            }
            lower = ceiling;
        }
        if (inbox.closeReason() != null) {
            detach(inbox.closeReason());
            return false;
        }
        return true;
    }

    /**
     * Attached (§3.5): take batches until the inbox closes, the subscription stops or a reset is pending
     */
    private void attached() throws Exception {
        phase = Phase.ATTACHED;
        var poll = settings.backstopInterval().toNanos();
        while (running && repositionTo == null) {
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
     * Detach (§3.6): {@code P} is already {@code min(W_rcv, lowest unhandled received - 1)} (F5); the unhandled rows
     * and the inbox are discarded, the runs kept, and the subscription catches up from {@code P}
     */
    private void detach(Inbox.CloseReason reason) throws Exception {
        if (seams.has(DETACH_RESUMES_AT_WATERMARK)) {
            handled.advanceTo(receivedWatermark);
        }
        remaining.clear();
        receivedWatermark = handled.prefix();
        var current = inbox;
        inbox = null;
        if (current != null && current.close(reason)) {
            loop.detach(type, current);
        }
        phase = Phase.CATCHING_UP;
        log.debug("Subscription {} on {} detached ({}); catching up from {}", key.subscriber(), key.aggregateType(), reason, handled.resumeFrom());
        observer.onDetach(key.subscriber(), key.aggregateType(), reason.name(), handled.resumeFrom());
        seams.hooks().onDetach(key.subscriber(), reason.name(), handled.resumeFrom());
    }

    /**
     * A superseding reset (§3.6): detach and reposition at the reset's resume point; what was handled no longer counts
     */
    private void reposition() throws Exception {
        var resumeFrom = repositionTo;
        repositionTo = null;
        if (inbox != null) {
            detach(Inbox.CloseReason.RESET);
        }
        handled.reset(resumeFrom - 1);
        receivedWatermark = handled.prefix();
        remaining.clear();
        highestHandled = -1;
    }

    /**
     * Work through received rows in order, dropping those already handled (§3.2)
     *
     * @return {@code false} if the subscription stopped, a reset is pending or, attached, the inbox closed before all
     * were handled
     */
    private boolean work(List<StoredEvent> rows) throws Exception {
        remaining.clear();
        for (var row : rows) {
            if (seams.has(NO_DEDUPE) || !handled.contains(row.globalOrder())) {
                remaining.addLast(row);
            }
        }
        advance();
        while (!remaining.isEmpty()) {
            if (!running || repositionTo != null || attachedInboxClosed()) {
                return false;
            }
            var event = remaining.peekFirst();
            if (!handle(event)) {
                return false;
            }
            remaining.removeFirst();
            handled.add(event.globalOrder());
            highestHandled = Math.max(highestHandled, event.globalOrder());
            advance();
            maybeSave();
        }
        return true;
    }

    private boolean attachedInboxClosed() {
        var current = inbox;
        return phase == Phase.ATTACHED && current != null && current.closeReason() != null;
    }

    /** {@code P = min(W_rcv, lowest received-but-unhandled - 1)}; never lowered */
    private void advance() {
        var prefix = receivedWatermark;
        if (!remaining.isEmpty()) {
            prefix = Math.min(prefix, remaining.peekFirst().globalOrder() - 1);
        }
        handled.advanceTo(prefix);
    }

    /**
     * Run the handler until it returns normally
     *
     * @return {@code false} if the subscription stopped, or an attached inbox closed while the handler failed
     */
    private boolean handle(StoredEvent event) throws Exception {
        while (true) {
            if (!running) {
                return false;
            }
            try {
                handler.handle(event);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (!running) {
                    return false;
                }
                observer.onError(key.subscriber(), key.aggregateType(), e);
                log.warn("Subscription {} on {}: handler failed on global_order {}; retrying after {}", key.subscriber(), key.aggregateType(), event.globalOrder(),
                         settings.backstopInterval(), e);
                Thread.sleep(settings.backstopInterval());
                if (attachedInboxClosed()) {
                    return false;
                }
                continue;
            }
            // interrupted while handling: not handled
            return !(Thread.currentThread().isInterrupted() && !running);
        }
    }

    /**
     * One catch-up page {@code (lower, upper]} or one bridge page with its re-read holes, on a borrowed connection that
     * is returned before any row is handled
     */
    private List<StoredEvent> read(boolean bridge, long lower, long upper, SortedSet<Long> holes, SortedMap<Long, Long> ranges) throws SQLException {
        var started = System.nanoTime();
        var rows    = new ArrayList<StoredEvent>();
        var plain   = !bridge || (holes.isEmpty() && ranges.isEmpty());
        var sql     = plain ? statements.rangeRead() : ranges.isEmpty() ? statements.bridgePage() : statements.bridgePageWithRanges();
        try (var connection = loop.dataSource().getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lower);
            statement.setLong(2, upper);
            statement.setInt(3, settings.catchUpPageSize());
            if (!plain) {
                statement.setArray(4, connection.createArrayOf("bigint", holes.toArray(Long[]::new)));
                if (!ranges.isEmpty()) {
                    statement.setArray(5, connection.createArrayOf("bigint", ranges.keySet().toArray(Long[]::new)));
                    statement.setArray(6, connection.createArrayOf("bigint", ranges.values().toArray(Long[]::new)));
                }
            }
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
            }
        }
        observer.onCatchUpRead(key.subscriber(), key.aggregateType(), bridge, System.nanoTime() - started, rows.size());
        return rows;
    }

    /**
     * §3.2: {@code P + 1}
     */
    long resumePoint() {
        if (seams.has(RESUME_AT_HIGHEST_HANDLED)) {
            return highestHandled >= 0 ? highestHandled + 1 : handled.resumeFrom();
        }
        var state = type == null ? null : type.state();
        if (seams.has(RESUME_AT_CURSOR)) {
            return state == null ? handled.resumeFrom() : Math.max(handled.resumeFrom(), state.cursor() + 1);
        }
        if (seams.has(RESUME_FROM_READER_WATERMARK) && state != null && state.watermark() != null) {
            var resume = Math.max(handled.resumeFrom(), state.watermark() + 1);
            return remaining.isEmpty() ? resume : Math.min(resume, remaining.peekFirst().globalOrder());
        }
        return handled.resumeFrom();
    }

    private void maybeSave() throws Exception {
        if (System.nanoTime() - lastSaveNanos >= settings.progressSaveInterval().toNanos()) {
            saveProgressNow();
        }
    }

    /**
     * Save the resume point if it moved. Delivery thread only.
     */
    void saveProgressNow() throws Exception {
        lastSaveNanos = System.nanoTime();
        var resume = resumePoint();
        if (resume == lastSaved) {
            return;
        }
        if (progress.save(key, resume, resetEpoch)) {
            lastSaved = resume;
            observer.onProgressSaved(key.subscriber(), key.aggregateType(), resume);
            return;
        }
        var row = progress.read(key);
        if (row.isEmpty()) {
            log.warn("Progress row of {} is gone; recreating it at {}", key, resume);
            var created = progress.start(key, false, MODE, MODEL, resume);
            resetEpoch = created.resetEpoch();
            lastSaved = created.resumeFrom();
        } else if (row.get().resetEpoch() != resetEpoch) {
            log.info("Subscription {} on {} was reset (epoch {} -> {}); repositioning at {}", key.subscriber(), key.aggregateType(), resetEpoch,
                     row.get().resetEpoch(), row.get().resumeFrom());
            resetEpoch = row.get().resetEpoch();
            lastSaved = row.get().resumeFrom();
            repositionTo = row.get().resumeFrom();
        }
    }
}
