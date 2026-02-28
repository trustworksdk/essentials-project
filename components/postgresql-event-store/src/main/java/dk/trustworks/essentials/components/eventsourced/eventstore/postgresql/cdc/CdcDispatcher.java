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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalParserMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import io.micrometer.core.instrument.*;
import dk.trustworks.essentials.shared.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * CdcDispatcher is responsible for orchestrating the Change Data Capture (CDC) lifecycle,
 * including polling WAL (Write Ahead Log), converting events, handling dispatch mechanisms,
 * and managing metrics for tracking system performance.
 * <p>
 * Fields involved in the operational lifecycle include:
 * - Definitions related to processing state (e.g., log, started, stopping, tickFuture).
 * - Metrics management such as timers and counters (e.g., ticksCounter, poisonRowsCounter).
 * - Dependencies injected during construction (e.g., inbox, unitOfWorkFactory, eventStreamGapHandler).
 * <p>
 * This class implements the {@link Lifecycle} interface, which
 * defines methods for starting and stopping the CDC dispatcher.
 */
public final class CdcDispatcher implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(CdcDispatcher.class);

    private final CdcInboxRepository                                            inbox;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final Wal2JsonToPersistedEventConverter                             converter;
    private final EventStreamGapHandler<?>                                      eventStreamGapHandler;
    private final WalGlobalOrdersExtractor                                      walGlobalOrdersExtractor;
    private final CdcPoisonNotifier                                             cdcPoisonNotifier;
    private final Consumer<List<PersistedEvent>>                                onEvents;
    private final String                                                        slotName;
    private final Duration                                                      pollInterval;
    private final int                                                           batchSize;
    private final PoisonPolicy                                                  poisonPolicy;
    private final DispatchedRowPolicy                                           dispatchedRowPolicy;
    private final WalParserMode                                                 walParserMode;
    private final CdcDeliveryMode                                               deliveryMode;
    private final CdcAvailability                                               availability;
    private final MeterRegistry                                                 meterRegistry;

    private final AtomicBoolean started  = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private ScheduledExecutorService executor;
    private Future<?>                tickFuture;

    private Counter             ticksCounter;
    private Counter             conversionFailuresCounter;
    private Counter             poisonRowsCounter;
    private Counter             publishedEventsCounter;
    private Timer               pollTimer;
    private Timer               convertTimer;
    private Timer               publishTimer;
    private DistributionSummary fetchedBatchSizeSummary;

    /**
     * Constructs a new instance of the CdcDispatcher class.
     *
     * @param inbox the repository handling CDC inbox operations
     * @param unitOfWorkFactory the factory for creating unit of work instances
     * @param eventStreamGapHandler the handler for addressing gaps in the event stream
     * @param converter the converter for transforming WAL2JSON to persisted events
     * @param walGlobalOrdersExtractor the extractor for WAL global orders
     * @param cdcPoisonNotifier optional notifier for handling poisoned messages
     * @param onEvents the consumer to handle lists of persisted events
     * @param slotName the logical decoding replication slot name
     * @param cdcDispatcherProperties properties and configuration for the CDC dispatcher
     * @param walParserMode the mode for parsing WAL logs
     * @param availability the availability handler for the dispatcher
     */
    public CdcDispatcher(CdcInboxRepository inbox,
                         HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                         EventStreamGapHandler<?> eventStreamGapHandler,
                         Wal2JsonToPersistedEventConverter converter,
                         WalGlobalOrdersExtractor walGlobalOrdersExtractor,
                         Optional<CdcPoisonNotifier> cdcPoisonNotifier,
                         Consumer<List<PersistedEvent>> onEvents,
                         String slotName,
                         CdcDispatcherProperties cdcDispatcherProperties,
                         WalParserMode walParserMode,
                         CdcAvailability availability) {
        this(inbox,
             unitOfWorkFactory,
             eventStreamGapHandler,
             converter,
             walGlobalOrdersExtractor,
             cdcPoisonNotifier,
             onEvents,
             slotName,
             cdcDispatcherProperties,
             walParserMode,
             CdcDeliveryMode.INBOX,
             availability,
             Optional.empty());
    }

    /**
     * Constructs a new instance of the CdcDispatcher class.
     *
     * @param inbox the repository handling CDC inbox operations
     * @param unitOfWorkFactory the factory for creating unit of work instances
     * @param eventStreamGapHandler the handler for addressing gaps in the event stream
     * @param converter the converter for transforming WAL2JSON to persisted events
     * @param walGlobalOrdersExtractor the extractor for WAL global orders
     * @param cdcPoisonNotifier optional notifier for handling poisoned messages
     * @param onEvents the consumer to handle lists of persisted events
     * @param slotName the logical decoding replication slot name
     * @param cdcDispatcherProperties properties and configuration for the CDC dispatcher
     * @param walParserMode the mode for parsing WAL logs
     * @param deliveryMode the delivery mode for event dispatching
     * @param availability the availability handler for the dispatcher
     * @param meterRegistry optional metrics registry
     */
    public CdcDispatcher(CdcInboxRepository inbox,
                         HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                         EventStreamGapHandler<?> eventStreamGapHandler,
                         Wal2JsonToPersistedEventConverter converter,
                         WalGlobalOrdersExtractor walGlobalOrdersExtractor,
                         Optional<CdcPoisonNotifier> cdcPoisonNotifier,
                         Consumer<List<PersistedEvent>> onEvents,
                         String slotName,
                         CdcDispatcherProperties cdcDispatcherProperties,
                         WalParserMode walParserMode,
                         CdcDeliveryMode deliveryMode,
                         CdcAvailability availability,
                         Optional<MeterRegistry> meterRegistry) {
        this.inbox = requireNonNull(inbox, "inbox cannot be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null");
        this.eventStreamGapHandler = requireNonNull(eventStreamGapHandler, "eventStreamGapHandler cannot be null");
        this.converter = requireNonNull(converter, "converter cannot be null");
        this.walGlobalOrdersExtractor = requireNonNull(walGlobalOrdersExtractor, "walGlobalOrdersExtract cannot be null");
        this.cdcPoisonNotifier = requireNonNull(cdcPoisonNotifier.orElse(new CdcPoisonNotifier.NoOpCdcPoisonNotifier()), "cdcPoisonNotifier cannot be null");
        this.onEvents = requireNonNull(onEvents, "onEvents cannot be null");
        this.slotName = requireNonNull(slotName, "slotName cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(slotName);
        this.pollInterval = requireNonNull(cdcDispatcherProperties.getPollInterval(), "pollInterval cannot be null");
        requireTrue(cdcDispatcherProperties.getBatchSize() >= 1, "batchSize has to be 1 or greater");
        this.batchSize = cdcDispatcherProperties.getBatchSize();
        this.poisonPolicy = requireNonNull(cdcDispatcherProperties.getPoisonPolicy(), "poisonPolicy cannot be null");
        this.dispatchedRowPolicy = requireNonNull(cdcDispatcherProperties.getDispatchedRowPolicy(), "dispatchedRowPolicy cannot be null");
        this.walParserMode = requireNonNull(walParserMode, "walParserMode cannot be null");
        this.deliveryMode = requireNonNull(deliveryMode, "deliveryMode cannot be null");
        this.availability = requireNonNull(availability, "availability cannot be null");
        this.meterRegistry = meterRegistry.orElse(null);
        initMetrics();
    }

    private void initMetrics() {
        if (meterRegistry == null) return;
        ticksCounter = Counter.builder("essentials.cdc.dispatcher.ticks")
                              .tag("slot", slotName)
                              .register(meterRegistry);
        conversionFailuresCounter = Counter.builder("essentials.cdc.dispatcher.conversion.failures")
                                           .tag("slot", slotName)
                                           .register(meterRegistry);
        poisonRowsCounter = Counter.builder("essentials.cdc.dispatcher.poison.rows")
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

        if (!availability.isActive()) {
            started.set(false);
            log.info("[{}] CDC dispatcher not started because CDC is not active (state={})", slotName, availability.getState());
            return;
        }

        log.info("[{}] ⚙️ Starting CDC dispatcher, polling every '{}' ms, batch size '{}', poison policy '{}', walParserMode '{}'",
                 slotName, pollInterval.toMillis(), batchSize, poisonPolicy, walParserMode);
        log.info("[{}] CDC dispatcher dispatched-row policy: {}", slotName, dispatchedRowPolicy);

        stopping.set(false);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cdc-dispatcher-" + slotName);
            t.setDaemon(true);
            return t;
        });

        this.tickFuture = executor.scheduleWithFixedDelay(this::tick, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("[{}] CDC dispatcher started", slotName);
    }

    private void tick() {
        if (stopping.get()) return;
        if (ticksCounter != null) ticksCounter.increment();

        long pollStartNs = System.nanoTime();
        var  batch       = inbox.fetchNextBatch(slotName, batchSize);
        if (pollTimer != null) pollTimer.record(System.nanoTime() - pollStartNs, TimeUnit.NANOSECONDS);
        if (fetchedBatchSizeSummary != null) fetchedBatchSizeSummary.record(batch.size());
        if (log.isTraceEnabled()) {
            log.trace("[{}] CDC dispatcher fetched batch of '{}' rows", slotName, batch.size());
        }
        if (batch.isEmpty()) return;

        for (var row : batch) {
            if (stopping.get()) return;
            var payloadBytes = row.payloadJsonBytes();

            try {
                long convertStartNs = System.nanoTime();
                var events = walParserMode == WalParserMode.BYTES
                             ? converter.convert(payloadBytes)
                             : converter.convert(payloadBytes == null ? null : new String(payloadBytes, StandardCharsets.UTF_8));
                if (convertTimer != null) convertTimer.record(System.nanoTime() - convertStartNs, TimeUnit.NANOSECONDS);
                if (!events.isEmpty()) {
                    long publishStartNs = System.nanoTime();
                    onEvents.accept(events);
                    if (publishTimer != null) publishTimer.record(System.nanoTime() - publishStartNs, TimeUnit.NANOSECONDS);
                    if (publishedEventsCounter != null) publishedEventsCounter.increment(events.size());
                }
                acknowledgeDispatchedRow(row.inboxId());
            } catch (Exception e) {
                if (conversionFailuresCounter != null) conversionFailuresCounter.increment();
                log.warn("[{}] CDC conversion failed for inboxId={} lsn={} policy={}: {}",
                         slotName, row.inboxId(), row.lsn(), poisonPolicy, e.getMessage(), e);

                if (poisonPolicy == PoisonPolicy.QUARANTINE_AND_CONTINUE) {
                    unitOfWorkFactory.usingUnitOfWork(uow -> {
                        log.warn("[{}] Poisoning inboxId={} lsn={}", slotName, row.inboxId(), row.lsn());
                        inbox.markPoison(slotName, row.lsn(), abbreviateExceptionMessage(e));
                        if (poisonRowsCounter != null) poisonRowsCounter.increment();

                        // IMPORTANT: prevent subscribers stalling on this missing global_order
                        // Extract (aggregateType, global_order list) from the WAL JSON without full conversion
                        var gaps = walParserMode == WalParserMode.BYTES
                                   ? walGlobalOrdersExtractor.extract(payloadBytes)
                                   : walGlobalOrdersExtractor.extract(payloadBytes == null ? null : new String(payloadBytes, StandardCharsets.UTF_8));
                        if (!gaps.isEmpty()) {
                            for (var gap : gaps) {
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

    private static String abbreviateExceptionMessage(Exception e) {
        var msg = e.getMessage();
        if (msg == null) {
            msg = e.getClass().getName();
        }
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }

    private void acknowledgeDispatchedRow(long inboxId) {
        if (dispatchedRowPolicy == DispatchedRowPolicy.DELETE) {
            inbox.deleteDispatched(inboxId);
        } else {
            inbox.markDispatched(inboxId);
        }
    }

    @Override
    public void stop() {
        if (!started.get()) {
            return;
        }
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        log.info("[{}] ⏹ Stopping CDC dispatcher", slotName);

        try {
            if (tickFuture != null) {
                tickFuture.cancel(true);
            }
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
            started.set(false);
        }
        log.info("[{}] 🛑 CDC dispatcher stopped", slotName);
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }
}
