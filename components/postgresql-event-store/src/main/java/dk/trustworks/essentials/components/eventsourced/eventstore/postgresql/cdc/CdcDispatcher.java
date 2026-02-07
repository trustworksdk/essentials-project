/*
 *  Copyright 2021-2025 the original author or authors.
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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.*;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;

public class CdcDispatcher implements Lifecycle {
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

    private final AtomicBoolean started  = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private ScheduledExecutorService executor;
    private Future<?>                tickFuture;

    public CdcDispatcher(CdcInboxRepository inbox,
                         HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                         EventStreamGapHandler<?> eventStreamGapHandler,
                         Wal2JsonToPersistedEventConverter converter,
                         WalGlobalOrdersExtractor walGlobalOrdersExtractor,
                         Optional<CdcPoisonNotifier> cdcPoisonNotifier,
                         Consumer<List<PersistedEvent>> onEvents,
                         String slotName,
                         CdcDispatcherProperties cdcDispatcherProperties) {
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
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        log.info("[{}] ⚙️ Starting CDC dispatcher, polling every '{}' ms, batch size '{}' and poison policy '{}'", slotName, pollInterval.toMillis(), batchSize, poisonPolicy);

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

        var batch = inbox.fetchNextBatch(slotName, batchSize);
        if (log.isTraceEnabled()) {
            log.trace("[{}] CDC dispatcher fetched batch of '{}' rows", slotName, batch.size());
        }
        if (batch.isEmpty()) return;

        for (var row : batch) {
            if (stopping.get()) return;

            try {
                var events = converter.convert(row.payloadJson());
                if (!events.isEmpty()) {
                    onEvents.accept(events);
                }
                inbox.markDispatched(row.inboxId());
            } catch (Exception e) {
                log.warn("[{}] CDC conversion failed for inboxId={} lsn={} policy={}: {}",
                         slotName, row.inboxId(), row.lsn(), poisonPolicy, e.getMessage(), e);

                if (poisonPolicy == PoisonPolicy.QUARANTINE_AND_CONTINUE) {
                    unitOfWorkFactory.usingUnitOfWork(uow -> {
                        log.warn("[{}] Poisoning inboxId={} lsn={}", slotName, row.inboxId(), row.lsn());
                        inbox.markPoison(slotName, row.lsn(), abbreviateExceptionMessage(e));

                        // IMPORTANT: prevent subscribers stalling on this missing global_order
                        // Extract (aggregateType, global_order list) from the WAL JSON without full conversion
                        var gaps = walGlobalOrdersExtractor.extract(row.payloadJson());
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
