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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDispatcherProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.DispatchedRowPolicy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.WalGlobalOrdersExtractor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * CdcDispatcher is responsible for orchestrating the Change Data Capture (CDC) lifecycle,
 * including polling the CDC inbox, decoding payloads via the configured
 * {@link LogicalDecodingPlugin}, handling poison rows, and dispatching events to downstream
 * subscribers via the {@code onEvents} consumer.
 * <p>
 * All plugin-specific payload handling (wal2json vs pgoutput, bytes vs string parsing,
 * row-change decoding) lives inside the {@link LogicalDecodingPlugin}. The dispatcher is
 * plugin-agnostic.
 */
public final class CdcDispatcher implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(CdcDispatcher.class);

    private final CdcInboxRepository                                            inbox;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final EventStreamGapHandler<?>                                      eventStreamGapHandler;
    private final LogicalDecodingPlugin                                         logicalDecodingPlugin;
    private final CdcPoisonNotifier                                             cdcPoisonNotifier;
    private final Consumer<List<PersistedEvent>>                                onEvents;
    private final String                                                        slotName;
    private final Duration                                                      pollInterval;
    private final int                                                           batchSize;
    /**
     * Per-statement timeout in seconds applied to {@link CdcInboxRepository#fetchNextBatch}.
     * {@code 0} means no framework-imposed timeout (defers to PG/JDBC/pool defaults).
     * Captured at construction from {@code CdcDispatcherProperties.queryTimeout}.
     */
    private final int                                                           queryTimeoutSeconds;
    private final PoisonPolicy                                                  poisonPolicy;
    private final DispatchedRowPolicy                                           dispatchedRowPolicy;
    private final CdcDeliveryMode                                               deliveryMode;
    private final CdcAvailability                                               availability;
    private final MeterRegistry                                                 meterRegistry;

    private final AtomicBoolean started  = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicLong    tickCount = new AtomicLong(0);
    private final AtomicLong    tickFailureCount = new AtomicLong(0);
    private final AtomicLong    conversionFailureCount = new AtomicLong(0);
    private final AtomicLong    poisonRowCount = new AtomicLong(0);
    private final AtomicLong    gapExtractionFailureCount = new AtomicLong(0);
    private final AtomicLong    publishedEventCount = new AtomicLong(0);
    /**
     * Inbox rows whose {@code plugin.decode()} returned an empty list — i.e. the row was a
     * legitimate non-data WAL message (BEGIN/COMMIT/RELATION/TRUNCATE), OR an INSERT that the
     * converter dropped silently. Split further via
     * {@link LogicalDecodingPlugin#diagnosticSummary()} so the monitor failure log can show
     * whether the zero-publish is "all B/C traffic" (benign) vs "INSERTs hitting unknown-
     * aggregate drops" (real bug).
     */
    private final AtomicLong    inboxRowsWithEmptyDecodeCount = new AtomicLong(0);
    private final AtomicLong    lastBatchSize = new AtomicLong(0);
    private final AtomicLong    lastTickEpochMs = new AtomicLong(0);
    /**
     * How many times the decoder's schema cache had to be rebuilt from the inbox mid-tick because a
     * row referenced a relation it didn't know. Expected to be {@code 0} in steady state and at most
     * a handful right after a restart; a climbing value means schema rows are being lost or pruned.
     */
    private final AtomicLong    schemaRePrimeCount = new AtomicLong(0);

    /**
     * Guards against re-priming once per row when a whole batch references an unknown relation:
     * priming is a DB round-trip, and if the first attempt in a tick didn't help, the rest won't
     * either. Only ever touched from the single dispatcher thread.
     */
    private boolean schemaRePrimedThisTick;

    private ScheduledExecutorService executor;
    private Future<?>                tickFuture;

    private Counter             ticksCounter;
    private Counter             tickFailuresCounter;
    private Counter             conversionFailuresCounter;
    private Counter             poisonRowsCounter;
    private Counter             gapExtractionFailuresCounter;
    private Counter             publishedEventsCounter;
    private Timer               pollTimer;
    private Timer               convertTimer;
    private Timer               publishTimer;
    private DistributionSummary fetchedBatchSizeSummary;

    /**
     * Constructs a new CdcDispatcher.
     *
     * @param inbox                    the repository handling CDC inbox operations
     * @param unitOfWorkFactory        the factory for creating unit of work instances
     * @param eventStreamGapHandler    the handler for addressing gaps in the event stream
     * @param logicalDecodingPlugin    the plugin that owns payload decoding and gap extraction
     * @param cdcPoisonNotifier        optional notifier for handling poisoned messages
     * @param onEvents                 the consumer to handle lists of persisted events
     * @param slotName                 the logical decoding replication slot name
     * @param cdcDispatcherProperties  properties and configuration for the CDC dispatcher
     * @param deliveryMode             the delivery mode for event dispatching
     * @param availability             the availability handler for the dispatcher
     * @param meterRegistry            optional metrics registry
     */
    public CdcDispatcher(CdcInboxRepository inbox,
                         HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                         EventStreamGapHandler<?> eventStreamGapHandler,
                         LogicalDecodingPlugin logicalDecodingPlugin,
                         Optional<CdcPoisonNotifier> cdcPoisonNotifier,
                         Consumer<List<PersistedEvent>> onEvents,
                         String slotName,
                         CdcDispatcherProperties cdcDispatcherProperties,
                         CdcDeliveryMode deliveryMode,
                         CdcAvailability availability,
                         Optional<MeterRegistry> meterRegistry) {
        this.inbox = requireNonNull(inbox, "inbox cannot be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null");
        this.eventStreamGapHandler = requireNonNull(eventStreamGapHandler, "eventStreamGapHandler cannot be null");
        this.logicalDecodingPlugin = requireNonNull(logicalDecodingPlugin, "logicalDecodingPlugin cannot be null");
        this.cdcPoisonNotifier = requireNonNull(cdcPoisonNotifier.orElse(new CdcPoisonNotifier.NoOpCdcPoisonNotifier()), "cdcPoisonNotifier cannot be null");
        this.onEvents = requireNonNull(onEvents, "onEvents cannot be null");
        this.slotName = requireNonNull(slotName, "slotName cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(slotName);
        this.pollInterval = requireNonNull(cdcDispatcherProperties.getPollInterval(), "pollInterval cannot be null");
        requireTrue(cdcDispatcherProperties.getBatchSize() >= 1, "batchSize has to be 1 or greater");
        this.batchSize = cdcDispatcherProperties.getBatchSize();
        // Round seconds *up* so any positive sub-second budget still applies a 1s timeout
        // rather than silently degrading to "no timeout". Zero (default) means no framework
        // timeout — the query inherits whatever PG/JDBC/pool defaults provide.
        var configuredQueryTimeout = cdcDispatcherProperties.getQueryTimeout();
        this.queryTimeoutSeconds = configuredQueryTimeout == null || configuredQueryTimeout.isZero() || configuredQueryTimeout.isNegative()
                                   ? 0
                                   : (int) Math.max(1L, (configuredQueryTimeout.toMillis() + 999L) / 1000L);
        this.poisonPolicy = requireNonNull(cdcDispatcherProperties.getPoisonPolicy(), "poisonPolicy cannot be null");
        this.dispatchedRowPolicy = requireNonNull(cdcDispatcherProperties.getDispatchedRowPolicy(), "dispatchedRowPolicy cannot be null");
        this.deliveryMode = requireNonNull(deliveryMode, "deliveryMode cannot be null");
        this.availability = requireNonNull(availability, "availability cannot be null");
        this.meterRegistry = meterRegistry.orElse(null);
        warnOnDispatcherKnobsIgnoredInDirectMode(cdcDispatcherProperties);
        initMetrics();
    }

    /**
     * In DIRECT delivery mode the dispatcher is not started — the tailer publishes straight to the
     * CDC bus. Any non-default {@link CdcDispatcherProperties} value therefore has no effect. Warn
     * loudly so that perf-tuned dispatcher settings don't silently evaporate when someone flips
     * {@code deliveryMode=DIRECT}.
     */
    private void warnOnDispatcherKnobsIgnoredInDirectMode(CdcDispatcherProperties props) {
        var ignored = ignoredDispatcherKnobsForMode(props, deliveryMode);
        if (!ignored.isEmpty()) {
            log.warn("[{}] deliveryMode=DIRECT — the following cdcDispatcher.* settings will have NO effect: {}. "
                             + "If you need these semantics, switch to deliveryMode=INBOX. If DIRECT is intentional, "
                             + "remove the overrides to silence this warning.",
                     slotName, ignored);
        }
    }

    /**
     * Returns the list of {@code cdcDispatcher.*} overrides that will be silently dropped for the
     * given {@link CdcDeliveryMode}. Returns an empty list when the dispatcher is actually in use
     * (i.e. {@link CdcDeliveryMode#INBOX}).
     * <p>
     * Package-private for unit-test verification — there's no need to instrument log capture just
     * to confirm the comparison.
     */
    static List<String> ignoredDispatcherKnobsForMode(CdcDispatcherProperties props, CdcDeliveryMode deliveryMode) {
        if (deliveryMode != CdcDeliveryMode.DIRECT) return List.of();
        var defaults = CdcDispatcherProperties.defaults();
        var ignored  = new ArrayList<String>();
        if (!props.getPollInterval().equals(defaults.getPollInterval())) {
            ignored.add("pollInterval=" + props.getPollInterval());
        }
        if (props.getBatchSize() != defaults.getBatchSize()) {
            ignored.add("batchSize=" + props.getBatchSize());
        }
        if (props.getPoisonPolicy() != defaults.getPoisonPolicy()) {
            ignored.add("poisonPolicy=" + props.getPoisonPolicy());
        }
        if (props.getDispatchedRowPolicy() != defaults.getDispatchedRowPolicy()) {
            ignored.add("dispatchedRowPolicy=" + props.getDispatchedRowPolicy());
        }
        return ignored;
    }

    private void initMetrics() {
        if (meterRegistry == null) return;
        ticksCounter = Counter.builder("essentials.cdc.dispatcher.ticks")
                              .tag("slot", slotName)
                              .register(meterRegistry);
        tickFailuresCounter = Counter.builder("essentials.cdc.dispatcher.tick.failures")
                                     .tag("slot", slotName)
                                     .register(meterRegistry);
        conversionFailuresCounter = Counter.builder("essentials.cdc.dispatcher.conversion.failures")
                                           .tag("slot", slotName)
                                           .register(meterRegistry);
        poisonRowsCounter = Counter.builder("essentials.cdc.dispatcher.poison.rows")
                                   .tag("slot", slotName)
                                   .register(meterRegistry);
        gapExtractionFailuresCounter = Counter.builder("essentials.cdc.dispatcher.gap_extraction.failures")
                                              .tag("slot", slotName)
                                              .register(meterRegistry);
        publishedEventsCounter = Counter.builder("essentials.cdc.dispatcher.published.events")
                                        .tag("slot", slotName)
                                        .register(meterRegistry);
        pollTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.dispatcher.poll.latency")
                                                       .tag("slot", slotName)
                                                       .register(meterRegistry);
        convertTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.dispatcher.convert.latency")
                                                          .tag("slot", slotName)
                                                          .register(meterRegistry);
        publishTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.dispatcher.publish.latency")
                                                          .tag("slot", slotName)
                                                          .register(meterRegistry);
        fetchedBatchSizeSummary = DistributionSummary.builder("essentials.cdc.dispatcher.poll.batch_size")
                                                     .tag("slot", slotName)
                                                     .register(meterRegistry);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        if (deliveryMode == CdcDeliveryMode.DIRECT) {
            started.set(false);
            log.info("[{}] CDC dispatcher not started because deliveryMode is DIRECT", slotName);
            return;
        }

        // NOTE: we deliberately do NOT check availability.isActive() here. Spring's Lifecycle
        // ordering is not guaranteed — in practice the dispatcher's start() runs before the
        // tailer has connected and transitioned availability to ACTIVE. Checking here strands
        // the dispatcher permanently in the "inactive at startup" case.
        //
        // Instead, the scheduler is always started and each tick() performs the liveness check.
        // Cost is one cheap availability read per pollInterval while CDC is inactive; correctness
        // gain is that any future transition to ACTIVE is picked up on the next tick.

        log.info("[{}] ⚙️ Starting CDC dispatcher, polling every '{}' ms, batch size '{}', poison policy '{}', plugin '{}'",
                 slotName, pollInterval.toMillis(), batchSize, poisonPolicy, logicalDecodingPlugin.pluginName());
        log.info("[{}] CDC dispatcher dispatched-row policy: {}", slotName, dispatchedRowPolicy);

        stopping.set(false);

        // Rebuild the decoder's schema cache before the first tick. The cache is in-memory, but the
        // messages that fill it are streamed once per replication session — so on restart it is empty
        // while inbox rows that depend on it may already be waiting.
        primeDecoderSchema();

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cdc-dispatcher-" + slotName);
            t.setDaemon(true);
            return t;
        });

        this.tickFuture = executor.scheduleWithFixedDelay(this::tick, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("[{}] CDC dispatcher started", slotName);
    }

    void tick() {
        if (stopping.get()) return;
        if (!availability.isActive()) {
            // CDC not (yet) ACTIVE — could be because the tailer hasn't connected yet (startup
            // race) or has transitioned to FAILED / INACTIVE after a reconnection issue. Quietly
            // skip; the next tick will retry. No counter bump — this is expected during startup
            // and not a tick "failure".
            return;
        }
        try {
            tickInternal();
        } catch (Throwable t) {
            // Intentional STOP (poisonPolicy=STOP path) sets stopping=true and re-throws; let it propagate
            // so the ScheduledExecutorService suppresses further ticks — that's the desired terminal state.
            if (stopping.get()) {
                throw t;
            }
            // Everything else (transient DB errors, unexpected decoder failures, gap-extraction throws, etc.)
            // must NOT suppress future ticks. scheduleWithFixedDelay will not retry after an uncaught throw,
            // so we swallow-and-log here to keep the dispatcher alive. Next tick retries the fetch.
            tickFailureCount.incrementAndGet();
            if (tickFailuresCounter != null) tickFailuresCounter.increment();
            log.error("[{}] Unexpected error in CDC dispatcher tick — will retry on next tick", slotName, t);
        }
    }

    private void tickInternal() {
        tickCount.incrementAndGet();
        lastTickEpochMs.set(System.currentTimeMillis());
        if (ticksCounter != null) ticksCounter.increment();

        long pollStartNs = System.nanoTime();
        var  batch       = inbox.fetchNextBatch(slotName, batchSize, queryTimeoutSeconds);
        lastBatchSize.set(batch.size());
        if (pollTimer != null) pollTimer.record(System.nanoTime() - pollStartNs, TimeUnit.NANOSECONDS);
        if (fetchedBatchSizeSummary != null) fetchedBatchSizeSummary.record(batch.size());
        if (log.isTraceEnabled()) {
            log.trace("[{}] CDC dispatcher fetched batch of '{}' rows", slotName, batch.size());
        }
        if (batch.isEmpty()) return;

        schemaRePrimedThisTick = false;

        for (var row : batch) {
            if (stopping.get()) return;
            var payloadBytes = row.payloadJsonBytes();

            try {
                long convertStartNs = System.nanoTime();
                var events = decodeWithSchemaRecovery(payloadBytes);
                if (convertTimer != null) convertTimer.record(System.nanoTime() - convertStartNs, TimeUnit.NANOSECONDS);
                if (!events.isEmpty()) {
                    long publishStartNs = System.nanoTime();
                    onEvents.accept(events);
                    if (publishTimer != null) publishTimer.record(System.nanoTime() - publishStartNs, TimeUnit.NANOSECONDS);
                    publishedEventCount.addAndGet(events.size());
                    if (publishedEventsCounter != null) publishedEventsCounter.increment(events.size());
                } else {
                    // Either legitimate non-data WAL (B/C/R/TRUNCATE) or an INSERT the converter
                    // silently dropped. The monitor's failure log correlates this with the
                    // plugin's diagnostic summary to distinguish benign from buggy.
                    inboxRowsWithEmptyDecodeCount.incrementAndGet();
                }
                acknowledgeDispatchedRow(row);
            } catch (CdcTransientEmitException transientEmit) {
                // Transient emit failure — NOT a conversion failure. The event decoded fine; the CDC
                // bus couldn't accept it right now, either because subscribers are behind producers
                // (FAIL_OVERFLOW) or a concurrent emitter held the serialized-access window
                // (FAIL_NON_SERIALIZED). We intentionally:
                //   - don't bump conversionFailureCount / poisonRowsCount
                //   - don't mark the row POISON (would skip the event forever in CDC live-tail)
                //   - don't advance to the next row in this batch (they'd likely hit the same
                //     condition; better to let the bus settle and retry the whole batch next tick)
                //   - leave the row as RECEIVED so the next tick re-processes it
                //
                // Subscribers meanwhile keep pulling from the bus at their own pace — once the bus
                // has headroom (or the contending emitter releases), the next tick pushes it through.
                log.warn("[{}] CDC bus transient emit failure — inboxId={} lsn={} will be retried next tick: {}",
                         slotName, row.inboxId(), row.lsn(), transientEmit.getMessage());
                return;
            } catch (Exception e) {
                conversionFailureCount.incrementAndGet();
                if (conversionFailuresCounter != null) conversionFailuresCounter.increment();
                log.warn("[{}] CDC conversion failed for inboxId={} lsn={} policy={}: {}",
                         slotName, row.inboxId(), row.lsn(), poisonPolicy, e.getMessage(), e);

                if (poisonPolicy == PoisonPolicy.QUARANTINE_AND_CONTINUE) {
                    // Gap extraction is best-effort. If it throws (malformed payload, decoder bug), we still
                    // mark the row POISON so the dispatcher makes forward progress. Without a registered gap,
                    // the event stream gap handler's transient→permanent promotion timeout (default 120s) is
                    // the fallback that eventually unblocks subscribers.
                    List<WalGlobalOrdersExtractor.Gap> poisonGaps;
                    try {
                        poisonGaps = logicalDecodingPlugin.extractGaps(payloadBytes);
                    } catch (Exception gapExtractionFailure) {
                        gapExtractionFailureCount.incrementAndGet();
                        if (gapExtractionFailuresCounter != null) gapExtractionFailuresCounter.increment();
                        log.error("[{}] Gap extraction failed for poisoned inboxId={} lsn={} — row will be quarantined without gap registration. "
                                          + "Subscribers may stall on the missing global_order until the EventStreamGapHandler promotes it from transient to permanent (default: 120s).",
                                  slotName, row.inboxId(), row.lsn(), gapExtractionFailure);
                        poisonGaps = List.of();
                    }

                    final List<WalGlobalOrdersExtractor.Gap> poisonGapsFinal = poisonGaps;
                    unitOfWorkFactory.usingUnitOfWork(uow -> {
                        log.warn("[{}] Poisoning inboxId={} lsn={}", slotName, row.inboxId(), row.lsn());
                        inbox.markPoison(slotName, row.lsn(), abbreviateExceptionMessage(e));
                        poisonRowCount.incrementAndGet();
                        if (poisonRowsCounter != null) poisonRowsCounter.increment();

                        // IMPORTANT: prevent subscribers stalling on this missing global_order
                        if (!poisonGapsFinal.isEmpty()) {
                            for (var gap : poisonGapsFinal) {
                                if (log.isDebugEnabled()) {
                                    log.debug("[{}] Poisoning gap for aggregateType={} global_order={}", slotName, gap.aggregateType(), gap.globalEventOrder());
                                }
                                eventStreamGapHandler.registerPermanentGaps(gap.aggregateType(), List.of(gap.globalEventOrder()), "cdc-poison:" + row.lsn());
                                cdcPoisonNotifier.onPoison(gap.aggregateType(), List.of(gap.globalEventOrder()), "cdc-poison:" + row.lsn());
                            }
                        }

                    });

                    continue;
                }

                // STOP
                log.warn("[{}] Stopping CDC dispatcher due to conversion failure", slotName);
                stopping.set(true);
                throw e;
            }
        }
    }

    /**
     * Decode a row, treating "the decoder doesn't know this relation" as recoverable rather than
     * poison.
     * <p>
     * The schema for a relation arrives in its own WAL message which the inbox retains, so a cache
     * miss usually means the in-memory cache is stale rather than the payload being bad — the
     * canonical case being a restart that emptied the cache while rows were still pending. Rebuilding
     * from the inbox and retrying once recovers those without dropping events. If the retry still
     * fails the payload is genuinely undecodable and the caller's {@link PoisonPolicy} takes over.
     */
    private List<PersistedEvent> decodeWithSchemaRecovery(byte[] payloadBytes) {
        try {
            return logicalDecodingPlugin.decode(payloadBytes);
        } catch (MissingRelationMetadataException missingSchema) {
            if (schemaRePrimedThisTick) {
                // Already rebuilt this tick and it didn't supply this relation — don't hammer the DB
                // once per row for the rest of the batch.
                throw missingSchema;
            }
            schemaRePrimedThisTick = true;
            schemaRePrimeCount.incrementAndGet();
            log.info("[{}] Decoder has no schema cached for relationId={} — rebuilding schema cache from the inbox and retrying",
                     slotName, missingSchema.getRelationId());
            primeDecoderSchema();
            return logicalDecodingPlugin.decode(payloadBytes);
        }
    }

    /**
     * Replay the slot's retained schema rows through the plugin's decoder so its schema cache is
     * populated. Decoding a schema message yields no events — the point is the caching side effect.
     * <p>
     * Best-effort: a failure here must not stop the dispatcher from starting or ticking, since the
     * live stream re-sends schema messages on every new replication session anyway.
     *
     * @return the number of schema rows successfully replayed
     */
    private int primeDecoderSchema() {
        var leadingBytes = logicalDecodingPlugin.schemaPayloadLeadingBytes();
        if (leadingBytes.isEmpty()) {
            return 0;
        }
        try {
            var schemaRows = inbox.fetchSchemaRows(slotName, leadingBytes);
            int primed = 0;
            for (var row : schemaRows) {
                try {
                    logicalDecodingPlugin.decode(row.payloadJsonBytes());
                    primed++;
                } catch (Exception e) {
                    log.warn("[{}] Could not replay schema row inboxId={} while priming the decoder — skipping it",
                             slotName, row.inboxId(), e);
                }
            }
            if (primed > 0) {
                log.info("[{}] Primed decoder with '{}' retained schema row(s) from the inbox", slotName, primed);
            } else {
                log.debug("[{}] No retained schema rows to prime the decoder with", slotName);
            }
            return primed;
        } catch (Exception e) {
            log.warn("[{}] Failed to prime the decoder's schema cache from the inbox — continuing; " +
                             "the replication stream re-sends schema messages on each new session",
                     slotName, e);
            return 0;
        }
    }

    private static String abbreviateExceptionMessage(Exception e) {
        var msg = e.getMessage();
        if (msg == null) {
            msg = e.getClass().getName();
        }
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }

    private void acknowledgeDispatchedRow(CdcInboxRepository.InboxRow row) {
        // Schema rows are exempt from DELETE: they are the only record of a relation's layout between
        // replication sessions, and deleting them leaves nothing to prime the decoder from after a
        // restart. There is at most one per relation, so retaining them costs nothing.
        if (dispatchedRowPolicy == DispatchedRowPolicy.DELETE && !isSchemaRow(row)) {
            inbox.deleteDispatched(row.inboxId());
        } else {
            inbox.markDispatched(row.inboxId());
        }
    }

    private boolean isSchemaRow(CdcInboxRepository.InboxRow row) {
        var payload = row.payloadJsonBytes();
        if (payload == null || payload.length == 0) {
            return false;
        }
        return logicalDecodingPlugin.schemaPayloadLeadingBytes().contains((int) (payload[0] & 0xFF));
    }

    @Override
    public void stop() {
        // Use `started` as the single idempotency guard, NOT `stopping`. The STOP poison-policy path
        // flips `stopping` true and re-throws (so the scheduler suppresses further ticks) but does NOT
        // shut the executor down. Guarding stop() on `stopping` would make this call a no-op in exactly
        // that case — leaking the ScheduledExecutorService and leaving `started` stuck true, so
        // isStarted()/getStatus() keep reporting a running dispatcher that is permanently dead. Gating
        // on the started→stopped CAS instead guarantees cleanup runs regardless of how `stopping` was set.
        if (!started.compareAndSet(true, false)) {
            return;
        }
        stopping.set(true);
        log.info("[{}] ⏹ Stopping CDC dispatcher", slotName);

        try {
            if (tickFuture != null) {
                tickFuture.cancel(true);
            }
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
        log.info("[{}] 🛑 CDC dispatcher stopped", slotName);
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    public CdcDispatcherStatus getStatus() {
        return new CdcDispatcherStatus(
                slotName,
                started.get(),
                stopping.get(),
                tickCount.get(),
                tickFailureCount.get(),
                conversionFailureCount.get(),
                poisonRowCount.get(),
                gapExtractionFailureCount.get(),
                publishedEventCount.get(),
                inboxRowsWithEmptyDecodeCount.get(),
                lastBatchSize.get(),
                lastTickEpochMs.get(),
                logicalDecodingPlugin.diagnosticSummary()
        );
    }

    /**
     * How many times the decoder's schema cache had to be rebuilt from the inbox because a row
     * referenced a relation it didn't know about.
     * <p>
     * Exposed as a getter rather than a {@link CdcDispatcherStatus} component to keep that public
     * record's shape stable. Expect {@code 0} in steady state; a small count after a restart is
     * normal recovery, while a climbing count means schema rows are being lost or pruned and events
     * are at risk of being quarantined.
     */
    public long getSchemaRePrimeCount() {
        return schemaRePrimeCount.get();
    }

    public record CdcDispatcherStatus(
            String slotName,
            boolean started,
            boolean stopping,
            long ticks,
            long tickFailures,
            long conversionFailures,
            long poisonRows,
            long gapExtractionFailures,
            long publishedEvents,
            long inboxRowsWithEmptyDecode,
            long lastBatchSize,
            long lastTickEpochMs,
            LogicalDecodingPlugin.DiagnosticSummary pluginDiagnostics
    ) {
    }
}
