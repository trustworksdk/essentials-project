/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcHealthCheckProperties;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Background probe that detects failure modes the in-band {@link CdcAvailability} checks miss:
 * <ul>
 *   <li><b>Stuck delivery</b>: the {@link WalReplicationTailer} is receiving messages (e.g. pgoutput
 *       Begin/Commit envelopes) but the {@link CdcDispatcher} is publishing zero events to the
 *       CDC bus. Observed empirically when pgoutput silently filters Insert/Update/Delete messages
 *       due to slot-state quirks; availability stays ACTIVE yet subscribers get nothing.</li>
 *   <li><b>Dispatcher dead</b>: the dispatcher's tick counter isn't advancing — scheduler crashed
 *       or blocked. Protected by the recent lifecycle fix but worth defending against.</li>
 * </ul>
 * <p>
 * On either signal the monitor flips {@link CdcAvailability} to {@code FAILED}, which causes
 * {@link CdcEventStore#pollEvents CdcEventStore.pollEvents} on subsequent subscriber calls to
 * fall back to classic polling. The tailer's own reconnect loop may later restore {@code ACTIVE},
 * at which point the monitor resumes from a fresh baseline — unless
 * {@link CdcHealthCheckProperties#isAutoRecover()} is {@code false}, in which case the monitor
 * fires once then stays quiet (sticky fail-closed).
 * <p>
 * Only INBOX delivery mode is monitored. DIRECT mode has no dispatcher so the heuristics don't
 * apply; a separate bus-level check would be needed there.
 */
public final class CdcEffectivenessMonitor implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(CdcEffectivenessMonitor.class);

    private final WalReplicationTailer    tailer;
    private final CdcDispatcher           dispatcher;
    private final CdcAvailability         availability;
    private final CdcDeliveryMode         deliveryMode;
    private final CdcHealthCheckProperties config;
    private final String                  slotName;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ScheduledExecutorService executor;
    private Future<?>                scheduled;
    /**
     * Subscription to {@link CdcAvailability#stateChanges()} used to kick an immediate baseline
     * capture the moment availability first flips ACTIVE. Without this, a monitor that starts
     * before the tailer finishes handshaking wastes a whole interval on "first tick sets
     * baseline, second tick evaluates" — pushing detection out to 2×interval. With the listener,
     * the baseline is captured at the moment of ACTIVE transition and the first scheduled tick
     * at t=interval runs a full evaluation.
     */
    private Disposable               availabilityStateSubscription;

    // Snapshot of counters captured on the last ACTIVE evaluation. Null until the first tick
    // after an ACTIVE transition; reset whenever availability leaves ACTIVE.
    private Snapshot previousSnapshot;
    // When we last marked availability FAILED from this monitor. Used by autoRecover=false to
    // skip subsequent evaluations. Null while monitor is healthy.
    private Long monitorMarkedFailedAtNanos;
    // Tracks whether we previously observed ACTIVE — so we can detect ACTIVE→!ACTIVE transitions
    // and reset the baseline.
    private boolean prevObservedActive;

    public CdcEffectivenessMonitor(WalReplicationTailer tailer,
                                   CdcDispatcher dispatcher,
                                   CdcAvailability availability,
                                   CdcDeliveryMode deliveryMode,
                                   CdcHealthCheckProperties config,
                                   String slotName) {
        this.tailer = requireNonNull(tailer, "tailer cannot be null");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher cannot be null");
        this.availability = requireNonNull(availability, "availability cannot be null");
        this.deliveryMode = requireNonNull(deliveryMode, "deliveryMode cannot be null");
        this.config = requireNonNull(config, "config cannot be null");
        this.slotName = requireNonNull(slotName, "slotName cannot be null");
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        if (!config.isEnabled()) {
            started.set(false);
            log.info("[{}] CDC effectiveness monitor disabled via essentials.eventstore.cdc.health-check.enabled=false", slotName);
            return;
        }
        if (deliveryMode == CdcDeliveryMode.DIRECT) {
            started.set(false);
            log.info("[{}] CDC effectiveness monitor not started — DIRECT delivery mode bypasses the dispatcher, monitor heuristics do not apply", slotName);
            return;
        }

        long intervalMs = Math.max(1_000L, config.getInterval().toMillis());
        log.info("[{}] ⚙️ Starting CDC effectiveness monitor (interval={} ms, messagesReceivedThreshold={}, dispatcherIdleGracePeriod={} ms, autoRecover={})",
                 slotName, intervalMs, config.getMessagesReceivedThreshold(),
                 config.getDispatcherIdleGracePeriod().toMillis(), config.isAutoRecover());

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cdc-effectiveness-monitor-" + slotName);
            t.setDaemon(true);
            return t;
        });
        scheduled = executor.scheduleWithFixedDelay(this::evaluateSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // Listen for availability transitions. When we observe ACTIVE (either the replay sink's
        // initial emission on subscribe or a later transition), push an immediate evaluate()
        // onto the monitor's own single-threaded executor so the baseline snapshot gets captured
        // right away. The next scheduled tick at t=interval can then run a real evaluation
        // against a meaningful delta, instead of burning that tick on baseline setup.
        availabilityStateSubscription = availability.stateChanges()
                                                    .filter(s -> s == CdcAvailability.State.ACTIVE)
                                                    .subscribe(s -> {
                                                        var exec = executor;
                                                        if (exec == null || exec.isShutdown()) return;
                                                        try {
                                                            exec.execute(this::evaluateSafely);
                                                        } catch (RejectedExecutionException ignored) {
                                                            // stop() racing with a late emission — next scheduled tick handles it.
                                                        }
                                                    });
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        try {
            if (availabilityStateSubscription != null) availabilityStateSubscription.dispose();
            if (scheduled != null) scheduled.cancel(true);
        } finally {
            if (executor != null) executor.shutdownNow();
        }
        log.info("[{}] 🛑 CDC effectiveness monitor stopped", slotName);
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    private void evaluateSafely() {
        try {
            evaluate();
        } catch (Throwable t) {
            // Never let the scheduler suppress future ticks. The monitor's own failures must
            // not also take out the dispatcher it's supposed to be watching.
            log.warn("[{}] CDC effectiveness monitor evaluation failed — will retry next interval", slotName, t);
        }
    }

    // Package-private for test — drives one evaluation cycle.
    void evaluate() {
        boolean active = availability.isActive();

        if (!active) {
            // Not ACTIVE — reset baseline so the next ACTIVE transition starts fresh. If we were
            // previously ACTIVE and this is a transition, clear the "already fired" marker so
            // auto-recover can re-evaluate once the tailer reconnects.
            if (prevObservedActive) {
                log.debug("[{}] CDC effectiveness monitor: availability transitioned away from ACTIVE — resetting baseline", slotName);
            }
            previousSnapshot = null;
            monitorMarkedFailedAtNanos = null;
            prevObservedActive = false;
            return;
        }

        // If auto-recover is off AND we've already fired, stay quiet.
        if (!config.isAutoRecover() && monitorMarkedFailedAtNanos != null) {
            return;
        }

        var tailerStatus     = tailer.getStatus();
        var dispatcherStatus = dispatcher.getStatus();
        var current          = new Snapshot(System.nanoTime(),
                                            tailerStatus.messagesReceived(),
                                            dispatcherStatus.publishedEvents(),
                                            dispatcherStatus.ticks());

        // autoRecover=true and we previously fired: the tailer has flipped availability back to
        // ACTIVE, so the stuck-state is (at least for now) cleared. Reset the baseline from the
        // current snapshot and drop the "already fired" marker; the next full window determines
        // whether the problem has really gone away.
        if (monitorMarkedFailedAtNanos != null) {
            previousSnapshot = current;
            prevObservedActive = true;
            monitorMarkedFailedAtNanos = null;
            log.info("[{}] CDC effectiveness monitor: availability is ACTIVE again — resetting baseline and re-enabling checks", slotName);
            return;
        }

        // First tick after ACTIVE — just record the baseline.
        if (previousSnapshot == null || !prevObservedActive) {
            previousSnapshot = current;
            prevObservedActive = true;
            return;
        }

        long elapsedMs             = TimeUnit.NANOSECONDS.toMillis(current.capturedNs - previousSnapshot.capturedNs);
        long messagesReceivedDelta = current.tailerMessagesReceived - previousSnapshot.tailerMessagesReceived;
        long publishedDelta        = current.dispatcherPublished    - previousSnapshot.dispatcherPublished;
        long ticksDelta            = current.dispatcherTicks        - previousSnapshot.dispatcherTicks;

        String stuckReason         = checkStuckDelivery(messagesReceivedDelta, publishedDelta, elapsedMs);
        String dispatcherDeadReason = checkDispatcherDead(ticksDelta, elapsedMs);

        if (stuckReason != null) {
            flipFailed(stuckReason);
        } else if (dispatcherDeadReason != null) {
            flipFailed(dispatcherDeadReason);
        } else {
            // Healthy — advance baseline.
            previousSnapshot = current;
        }
    }

    private String checkStuckDelivery(long messagesReceivedDelta, long publishedDelta, long elapsedMs) {
        if (messagesReceivedDelta < config.getMessagesReceivedThreshold()) return null;
        if (publishedDelta > 0) return null;
        return String.format(
                "CDC appears stuck: WalReplicationTailer received %d messages in the last %d ms (threshold %d) "
                        + "but CdcDispatcher published 0 events to the CDC bus. "
                        + "This usually means pgoutput is filtering row changes (slot quirk), the slot's publication "
                        + "no longer includes the event-stream tables, or dispatcher conversion is silently dropping "
                        + "every row. Falling back to polling — check WAL slot state and publication membership.",
                messagesReceivedDelta, elapsedMs, config.getMessagesReceivedThreshold());
    }

    private String checkDispatcherDead(long ticksDelta, long elapsedMs) {
        long grace = Math.max(config.getInterval().toMillis(), config.getDispatcherIdleGracePeriod().toMillis());
        if (ticksDelta > 0) return null;
        if (elapsedMs < grace) return null;
        return String.format(
                "CDC dispatcher appears dead: its tick counter did not advance in the last %d ms (grace %d ms). "
                        + "Scheduler may be blocked or the executor has been shut down. Falling back to polling — "
                        + "check dispatcher logs for stack traces.",
                elapsedMs, grace);
    }

    private void flipFailed(String reason) {
        // Loud — operators should be paged on this. Include the slot and a hint that fallback
        // to polling is automatic so readers don't panic about data loss.
        log.error("[{}] ⚠️  CDC EFFECTIVENESS CHECK FAILED — {} Subscribers will transparently fall back to classic polling; no events are lost, but CDC's live-tail advantage is gone until the underlying cause is fixed.",
                  slotName, reason);
        availability.failed(slotName, reason);
        monitorMarkedFailedAtNanos = System.nanoTime();
        // Intentionally do NOT advance previousSnapshot — on the next tick, if the tailer has
        // already been flipped back to ACTIVE by reconnect, the baseline will reset via the
        // "first tick after ACTIVE" branch.
    }

    // Test hook — so unit tests can assert monitor state without poking private fields.
    boolean hasFiredAtLeastOnce() {
        return monitorMarkedFailedAtNanos != null;
    }

    // Test hook — lets tests inject a fake "now" via the scheduler instead of real wall-clock.
    // Used by CdcEffectivenessMonitorTest to drive multiple evaluate() calls back-to-back.
    private record Snapshot(long capturedNs,
                            long tailerMessagesReceived,
                            long dispatcherPublished,
                            long dispatcherTicks) {
    }
}
