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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The phase-2 subscription (design §1.2): one handler, {@code FRESH}, periodic progress, non-exclusive, backstop
 * wake-up only. <b>Internal and temporary</b>: the phase-8 subscription API replaces it.
 * <p>
 * One thread runs the {@link TailReader} and the handler in turn, so delivery is serial and the reader's state is
 * single-threaded. Every event the reader publishes is handed to the handler at least once; a late commit into a hole
 * arrives with the next read that sees it (F1), after higher orders of other aggregates. A handler that throws is
 * retried with the same event after the backstop interval; an interrupted handler is not handled.
 * <p>
 * The resume point saved is §4.7's {@code min(watermark + 1, lowest delivered-but-not-handled)}; while the restart's
 * watermark is unknown (F4) it stays at the point this incarnation started from. Saved at most every
 * {@link EventLogSettings#progressSaveInterval()}, with the reset-epoch compare-and-set; a superseding reset
 * repositions the subscription at the reset's resume point. {@link #stop()} saves once more.
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

    private final ProgressStore.Key key;
    private final long              initialResumeFrom;
    private final Handler           handler;
    private final TailReader        reader;
    private final ProgressStore     progress;
    private final EventLogSettings  settings;
    private final EventLogObserver  observer;
    private final TestSeams         seams;

    private final ArrayDeque<StoredEvent> pending = new ArrayDeque<>();
    private final Object                  monitor = new Object();
    private final CountDownLatch          started = new CountDownLatch(1);
    private volatile boolean              running;
    private volatile boolean              crashed;
    private volatile Throwable            startFailure;
    private boolean                       wakeRequested;
    private Thread                        thread;
    private long                          startResumeFrom;
    private long                          resetEpoch;
    private long                          lastSaved;
    private long                          lastSaveNanos;
    private long                          highestHandled = -1;
    private Long                          repositionTo;

    FreshSubscription(SubscriptionSpec spec, TailReader reader, ProgressStore progress, EventLogSettings settings, EventLogObserver observer) {
        this.key = spec.key();
        this.initialResumeFrom = spec.initialResumeFrom();
        this.handler = spec.handler();
        this.seams = spec.seams();
        this.reader = requireNonNull(reader, "No reader provided");
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
        thread = Thread.ofPlatform().name("eventlog-" + key.aggregateType() + "-" + key.subscriber()).daemon().start(this::run);
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
     * Stop delivering, save the resume point once more and close the reader's connection. Idempotent.
     */
    public void stop() {
        halt();
    }

    /**
     * Test-only: stop as a crash would - no further save. With the reader's backend terminated by the test, this is
     * the "connection kill + engine stop" of the I3 IT.
     */
    void crash() {
        crashed = true;
        halt();
    }

    private void halt() {
        running = false;
        wakeUp();
        var t = thread;
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running && thread != null && thread.isAlive();
    }

    public String subscriber() {
        return key.subscriber();
    }

    public String aggregateType() {
        return key.aggregateType();
    }

    /**
     * Read now instead of at the next backstop tick (later phases' wake-up tiers call this)
     */
    public void wakeUp() {
        synchronized (monitor) {
            wakeRequested = true;
            monitor.notifyAll();
        }
    }

    TailReader reader() {
        return reader;
    }

    private void run() {
        try {
            try {
                var row = progress.start(key, false, MODE, MODEL, initialResumeFrom);
                resetEpoch = row.resetEpoch();
                startResumeFrom = row.resumeFrom();
                lastSaved = row.resumeFrom();
                lastSaveNanos = System.nanoTime();
                reader.start(startResumeFrom);
            } catch (Throwable e) {
                startFailure = e;
                return;
            } finally {
                started.countDown();
            }
            log.info("Subscription {} on {} (instance '{}') started from {}", key.subscriber(), key.aggregateType(), key.instanceName(), startResumeFrom);
            loop();
        } finally {
            if (!crashed && startFailure == null) {
                Thread.interrupted();
                try {
                    saveProgressNow();
                } catch (Exception e) {
                    log.warn("Final progress save of {} failed", key, e);
                }
            }
            reader.close();
        }
    }

    private void loop() {
        while (running) {
            try {
                if (repositionTo != null) {
                    reposition(repositionTo);
                }
                var batch = reader.poll();
                pending.addAll(batch);
                var delivered = deliver();
                maybeSave();
                if (!running) {
                    return;
                }
                if (!delivered) {
                    park(settings.backstopInterval());
                } else if (!reader.readAgainNow(batch.size())) {
                    park(reader.hasOutstandingCandidates() ? settings.probeWait(reader.lastReadNanos(), reader.lastBackends()) : settings.backstopInterval());
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                observer.onError(key.subscriber(), key.aggregateType(), e);
                log.warn("Subscription {} on {}: the tail read failed, restarting the reader", key.subscriber(), key.aggregateType(), e);
                try {
                    park(settings.backstopInterval());
                    if (running) {
                        reposition(resumePoint());
                    }
                } catch (Exception again) {
                    log.warn("Subscription {} on {}: restarting the reader failed", key.subscriber(), key.aggregateType(), again);
                }
            }
        }
    }

    /**
     * Restart the reader at {@code resumeFrom}, which must satisfy §4.7 (everything below it final). Undelivered and
     * unhandled events are dropped: the reader returns them again.
     */
    private void reposition(long resumeFrom) throws Exception {
        repositionTo = null;
        pending.clear();
        highestHandled = -1;
        startResumeFrom = resumeFrom;
        reader.start(resumeFrom);
    }

    /**
     * @return {@code false} if the handler failed or the subscription stopped before the queue was empty
     */
    private boolean deliver() throws Exception {
        while (!pending.isEmpty()) {
            if (!running) {
                return false;
            }
            var event = pending.peekFirst();
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
                return false;
            }
            if (Thread.currentThread().isInterrupted() && !running) {
                // interrupted while handling: not handled
                return false;
            }
            pending.removeFirst();
            highestHandled = Math.max(highestHandled, event.globalOrder());
            maybeSave();
        }
        return true;
    }

    /**
     * §4.7: {@code min(watermark + 1, lowest delivered-but-not-handled)}
     */
    long resumePoint() {
        if (seams.has(RESUME_AT_HIGHEST_HANDLED)) {
            return highestHandled >= 0 ? highestHandled + 1 : startResumeFrom;
        }
        if (seams.has(RESUME_AT_CURSOR)) {
            return reader.cursor() + 1;
        }
        var watermark = reader.watermark();
        var resume    = watermark == null ? startResumeFrom : Math.max(startResumeFrom, watermark + 1);
        for (var event : pending) {
            resume = Math.min(resume, event.globalOrder());
        }
        return resume;
    }

    private void maybeSave() throws Exception {
        if (System.nanoTime() - lastSaveNanos >= settings.progressSaveInterval().toNanos()) {
            saveProgressNow();
        }
    }

    /**
     * Save §4.7's resume point if it moved. Runs on the subscription's thread (tests call it from a reader hook).
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

    /**
     * The last saved resume point, for tests
     */
    long lastSaved() {
        return lastSaved;
    }

    private void park(Duration duration) throws InterruptedException {
        synchronized (monitor) {
            if (!wakeRequested && running) {
                monitor.wait(Math.max(1, duration.toMillis()));
            }
            wakeRequested = false;
        }
    }
}
