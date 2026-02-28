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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.Wal2JsonTailerProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalParserMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.Wal2JsonToPersistedEventConverter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.handler.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.Lifecycle;
import io.micrometer.core.instrument.*;
import org.jdbi.v3.core.Jdbi;
import org.postgresql.PGConnection;
import org.postgresql.replication.PGReplicationStream;
import org.slf4j.*;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The {@code Wal2JsonTailer} class is responsible for tailing PostgreSQL's Write-Ahead Log (WAL)
 * using the `wal2json` logical decoding plugin. It extracts changes from the replication stream,
 * applies optional filtering, and processes them for delivery to an inbox repository or other downstream systems.
 * <p>
 * This class implements the {@link Lifecycle} interface to provide
 * lifecycle management methods for starting and stopping the tailer.
 */
public final class Wal2JsonTailer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(Wal2JsonTailer.class);

    private final DataSource                                                    replicationDataSource;
    private final Jdbi                                                          jdbi;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String                                                        slotName;
    private final CdcInboxRepository                                            inboxRepository;
    private final WalMessageFilter                                              walMessageFilter;
    private final MeterRegistry                                                 meterRegistry;
    private final Wal2JsonTailerErrorHandler                                    errorHandler;
    private final Wal2JsonTailerProperties                                      wal2JsonTailerProperties;
    private final PgSlotMode                                                    pgSlotMode;
    private final CdcMode                                                       cdcMode;
    private final CdcAvailability                                               availability;
    private final CdcDeliveryMode                                               deliveryMode;
    private final WalParserMode                                                 walParserMode;
    private final Wal2JsonToPersistedEventConverter                             directConverter;
    private final Consumer<List<PersistedEvent>>                                directOnEvents;

    private ExecutorService executor;
    private Future<?>       loopFuture;

    private final    AtomicBoolean started           = new AtomicBoolean(false);
    private final    AtomicBoolean stopping          = new AtomicBoolean(false);
    private volatile boolean       wal2jsonAvailable = false;

    private final CountDownLatch streamStartedLatch = new CountDownLatch(1);

    private final AtomicLong              nullPolls            = new AtomicLong(0);
    private final AtomicLong              connectAttempt       = new AtomicLong(0);
    private final AtomicLong              messagesReceived     = new AtomicLong(0);
    private final AtomicLong              inboxWrites          = new AtomicLong(0);
    private final AtomicLong              inboxDuplicateWrites = new AtomicLong(0);
    private final AtomicLong              inboxWriteFailures   = new AtomicLong(0);
    private final AtomicLong              handlerFailures      = new AtomicLong(0);
    private final AtomicLong              lastMessageEpochMs   = new AtomicLong(0);
    private final AtomicReference<String> lastReceiveLsn       = new AtomicReference<>("n/a");
    private final AtomicReference<String> lastAckedLsn         = new AtomicReference<>("n/a");
    private final AtomicReference<String> lastMessagePreview   = new AtomicReference<>("");
    private final AtomicBoolean           slotLockAcquired     = new AtomicBoolean(false);
    private       Counter                 connectAttemptsCounter;
    private       Counter                 connectSuccessCounter;
    private       Counter                 connectFailuresCounter;
    private       Counter                 messagesReceivedCounter;
    private       Counter                 inboxWritesCounter;
    private       Counter                 inboxDuplicatesCounter;
    private       Counter                 inboxWriteFailuresCounter;
    private       Counter                 handlerFailuresCounter;

    /**
     * Constructs a new instance of the Wal2JsonTailer class.
     *
     * @param replicationDataSource The {@link DataSource} used for replication connection.
     * @param jdbi The {@link Jdbi} instance for database interaction.
     * @param unitOfWorkFactory The factory responsible for creating {@link HandleAwareUnitOfWork} instances.
     * @param slotName The name of the replication slot used for listening to WAL changes.
     * @param inboxRepository The repository for handling CDC inbox operations.
     * @param wal2JsonTailerProperties The configuration properties for the WAL2JSON tailer.
     * @param pgSlotMode The PostgreSQL slot mode to be used.
     * @param cdcMode The mode of change data capture (CDC) being employed.
     * @param availability The mechanism for checking CDC availability.
     * @param meterRegistry The optional {@link MeterRegistry} for collecting metrics.
     * @param errorHandler The optional {@link Wal2JsonTailerErrorHandler} for handling errors.
     */
    public Wal2JsonTailer(
            DataSource replicationDataSource,
            Jdbi jdbi,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            String slotName,
            CdcInboxRepository inboxRepository,
            Wal2JsonTailerProperties wal2JsonTailerProperties,
            PgSlotMode pgSlotMode,
            CdcMode cdcMode,
            CdcAvailability availability,
            Optional<MeterRegistry> meterRegistry,
            Optional<Wal2JsonTailerErrorHandler> errorHandler) {
        this(replicationDataSource,
             jdbi,
             unitOfWorkFactory,
             slotName,
             inboxRepository,
             wal2JsonTailerProperties,
             pgSlotMode,
             cdcMode,
             CdcDeliveryMode.INBOX,
             WalParserMode.STRING,
             Optional.empty(),
             Optional.empty(),
             Optional.empty(),
             availability,
             meterRegistry,
             errorHandler);
    }

    /**
     * Constructs a new instance of Wal2JsonTailer, which processes WAL (Write-Ahead Log) entries in JSON format
     * for change data capture (CDC) and manages the handling of events based on the specified configurations.
     *
     * @param replicationDataSource the DataSource used for PostgreSQL replication; must not be null.
     * @param jdbi the Jdbi database access object; must not be null.
     * @param unitOfWorkFactory the factory for creating unit of work instances; must not be null.
     * @param slotName the name of the logical replication slot to use; must not be null and must be a valid name.
     * @param inboxRepository the repository for persisting events; must not be null.
     * @param wal2JsonTailerProperties configuration properties for the WAL tailer; must not be null.
     * @param pgSlotMode the mode of handling logical replication slots in PostgreSQL; must not be null.
     * @param cdcMode the mode of change data capture; must not be null.
     * @param deliveryMode the delivery mode for processed events (e.g., DIRECT or INBOX); must not be null.
     * @param walParserMode the mode for parsing WAL entries; must not be null.
     * @param directConverter an optional converter to transform WAL entries into persisted events; required if delivery mode is DIRECT.
     * @param directOnEvents an optional consumer for processed events; required if delivery mode is DIRECT.
     * @param walMessageFilter an optional filter for filtering WAL messages; if absent, a default filter is used.
     * @param availability an object that manages availability checks; must not be null.
     * @param meterRegistry an optional registry for recording metrics.
     * @param errorHandler an optional error handler for processing errors during WAL tailing; a default handler is used if absent.
     */
    public Wal2JsonTailer(
            DataSource replicationDataSource,
            Jdbi jdbi,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            String slotName,
            CdcInboxRepository inboxRepository,
            Wal2JsonTailerProperties wal2JsonTailerProperties,
            PgSlotMode pgSlotMode,
            CdcMode cdcMode,
            CdcDeliveryMode deliveryMode,
            WalParserMode walParserMode,
            Optional<Wal2JsonToPersistedEventConverter> directConverter,
            Optional<Consumer<List<PersistedEvent>>> directOnEvents,
            Optional<WalMessageFilter> walMessageFilter,
            CdcAvailability availability,
            Optional<MeterRegistry> meterRegistry,
            Optional<Wal2JsonTailerErrorHandler> errorHandler) {
        this.replicationDataSource = requireNonNull(replicationDataSource, "replicationDataSource cannot be null");
        this.jdbi = requireNonNull(jdbi, "jdbi cannot be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null");
        this.slotName = requireNonNull(slotName, "slotName cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(slotName);
        this.inboxRepository = requireNonNull(inboxRepository, "inboxRepository cannot be null");
        this.wal2JsonTailerProperties = requireNonNull(wal2JsonTailerProperties, "properties cannot be null");
        this.pgSlotMode = requireNonNull(pgSlotMode, "pgSlotMode cannot be null");
        this.cdcMode = requireNonNull(cdcMode, "cdcMode cannot be null");
        this.deliveryMode = requireNonNull(deliveryMode, "deliveryMode cannot be null");
        this.walParserMode = requireNonNull(walParserMode, "walParserMode cannot be null");
        this.directConverter = directConverter.orElse(null);
        this.directOnEvents = directOnEvents.orElse(null);
        this.availability = requireNonNull(availability, "availability cannot be null");
        if (this.deliveryMode == CdcDeliveryMode.DIRECT) {
            requireNonNull(this.directConverter, "directConverter cannot be null in DIRECT delivery mode");
            requireNonNull(this.directOnEvents, "directOnEvents cannot be null in DIRECT delivery mode");
        }
        requireNonNull(wal2JsonTailerProperties.getPollInterval(), "pollInterval cannot be null");
        requireNonNull(wal2JsonTailerProperties.getPollBackoffInterval(), "pollBackoffInterval cannot be null");
        requireNonNull(wal2JsonTailerProperties.getMaxPollBackoffInterval(), "maxPollBackInterval cannot be null");
        requireNonNull(wal2JsonTailerProperties.getReplicationStatusInterval(), "replicationStatusInterval cannot be null");
        requireTrue(wal2JsonTailerProperties.getJitterRatio() > 0.0 && wal2JsonTailerProperties.getJitterRatio() < 0.5, "jitterRatio must be in [0.0..0.5]");
        requireTrue(wal2JsonTailerProperties.getBackOffFactor() > 1, "backOffFactor must be > 1");
        this.meterRegistry = meterRegistry.orElse(null);
        this.errorHandler = errorHandler.orElseGet(DefaultWal2JsonTailerErrorHandler::new);
        this.walMessageFilter = walMessageFilter.orElseGet(RegexWalMessageFilter::new);
        initMetrics();
        if (deliveryMode == CdcDeliveryMode.INBOX) {
            unitOfWorkFactory.usingUnitOfWork(inboxRepository::createTableAndIndexes);
        }
    }

    /**
     * Constructs an instance of the Wal2JsonTailer class, which processes
     * PostgreSQL Write-Ahead Log (WAL) entries in JSON format for Change Data Capture (CDC)
     * and coordinates event handling based on the provided configuration settings.
     *
     * @param replicationDataSource The DataSource used to establish a replication connection; must not be null.
     * @param jdbi The Jdbi instance for database operations; must not be null.
     * @param unitOfWorkFactory The factory for creating instances of HandleAwareUnitOfWork; must not be null.
     * @param slotName The name of the logical replication slot to connect to; must not be null.
     * @param inboxRepository The repository for managing persisted CDC inbox events; must not be null.
     * @param wal2JsonTailerProperties The configuration properties specific to the WAL2JSON tailer; must not be null.
     * @param pgSlotMode The mode used to manage logical replication slots in PostgreSQL; must not be null.
     * @param cdcMode The Change Data Capture (CDC) mode being used (e.g., incremental, full); must not be null.
     * @param deliveryMode The delivery mode for processing events (e.g., DIRECT or INBOX); must not be null.
     * @param walParserMode The mode used for parsing the WAL entries; must not be null.
     * @param directConverter A converter to translate WAL entries into persisted events, required when delivery mode is DIRECT; can be empty.
     * @param directOnEvents A consumer for managing processed events when delivery mode is DIRECT; can be empty.
     * @param availability The component responsible for monitoring CDC availability and readiness; must not be null.
     * @param meterRegistry A registry for collecting and managing metrics; can be empty.
     * @param errorHandler An optional error handler to manage errors during WAL tailing; can be empty.
     */
    public Wal2JsonTailer(
            DataSource replicationDataSource,
            Jdbi jdbi,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            String slotName,
            CdcInboxRepository inboxRepository,
            Wal2JsonTailerProperties wal2JsonTailerProperties,
            PgSlotMode pgSlotMode,
            CdcMode cdcMode,
            CdcDeliveryMode deliveryMode,
            WalParserMode walParserMode,
            Optional<Wal2JsonToPersistedEventConverter> directConverter,
            Optional<Consumer<List<PersistedEvent>>> directOnEvents,
            CdcAvailability availability,
            Optional<MeterRegistry> meterRegistry,
            Optional<Wal2JsonTailerErrorHandler> errorHandler) {
        this(replicationDataSource,
             jdbi,
             unitOfWorkFactory,
             slotName,
             inboxRepository,
             wal2JsonTailerProperties,
             pgSlotMode,
             cdcMode,
             deliveryMode,
             walParserMode,
             directConverter,
             directOnEvents,
             Optional.empty(),
             availability,
             meterRegistry,
             errorHandler);
    }

    private void initMetrics() {
        if (meterRegistry == null) return;

        connectAttemptsCounter = Counter.builder("essentials.cdc.wal2json.connect.attempts").tag("slot", slotName).register(meterRegistry);
        connectSuccessCounter = Counter.builder("essentials.cdc.wal2json.connect.success").tag("slot", slotName).register(meterRegistry);
        connectFailuresCounter = Counter.builder("essentials.cdc.wal2json.connect.failures").tag("slot", slotName).register(meterRegistry);
        messagesReceivedCounter = Counter.builder("essentials.cdc.wal2json.messages").tag("slot", slotName).register(meterRegistry);
        inboxWritesCounter = Counter.builder("essentials.cdc.wal2json.inbox.writes").tag("slot", slotName).register(meterRegistry);
        inboxWriteFailuresCounter = Counter.builder("essentials.cdc.wal2json.inbox.write_failures").tag("slot", slotName).register(meterRegistry);
        inboxDuplicatesCounter = Counter.builder("essentials.cdc.wal2json.inbox.duplicates").tag("slot", slotName).register(meterRegistry);
        handlerFailuresCounter = Counter.builder("essentials.cdc.wal2json.handler.failures").tag("slot", slotName).register(meterRegistry);

        Gauge.builder("essentials.cdc.wal2json.last_message_age_ms", lastMessageEpochMs, v ->
                     v.get() == 0 ? Double.POSITIVE_INFINITY : (System.currentTimeMillis() - v.get()))
             .tag("slot", slotName)
             .register(meterRegistry);

        Gauge.builder("essentials.cdc.wal2json.null_polls", nullPolls, AtomicLong::get).tag("slot", slotName).register(meterRegistry);

        Gauge.builder("essentials.cdc.wal2json.inbox_write_failures", inboxWriteFailures, AtomicLong::get).tag("slot", slotName).register(meterRegistry);

        Gauge.builder("essentials.cdc.wal2json.slot_lock_acquired", slotLockAcquired, v -> v.get() ? 1.0 : 0.0)
             .tag("slot", slotName)
             .register(meterRegistry);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        stopping.set(false);
        availability.inactive(slotName, "starting");
        log.info("[{}] ⚙️ Starting Essentials Wal2JsonTailer", slotName);


        unitOfWorkFactory.usingUnitOfWork(uow -> {
            boolean logicalOk = PostgresqlUtil.isLogicalDecodingEnabled(uow.handle());
            if (!logicalOk) {
                log.warn("Logical decoding not enabled (wal_level/max_replication_slots/max_wal_senders). CDC disabled.");
                wal2jsonAvailable = false;
                availability.failed(slotName, "logical decoding not enabled");
                return;
            }

            boolean usable = PostgresqlUtil.isOutputPluginUsable(uow.handle(), "wal2json");
            wal2jsonAvailable = usable;

            if (!usable) {
                log.warn("wal2json output plugin not usable (missing plugin or insufficient privileges). CDC disabled.");
                availability.failed(slotName, "wal2json plugin not usable");
            }
        });

        if (!wal2jsonAvailable) {
            started.set(false);
            log.info("wal2json CDC is not available - cannot start Wal2JsonTailer");
            if (cdcMode == CdcMode.REQUIRE) {
                throw new IllegalStateException("wal2json CDC is required but not available");
            }
            return;
        }

        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wal2json-tailer-" + slotName);
            t.setDaemon(true);
            return t;
        });

        this.loopFuture = executor.submit(this::runPollLoop);

        log.info("[{}] Wal2JsonTailer started", slotName);
    }

    @Override
    public void stop() {
        if (!started.get()) {
            return;
        }
        boolean initiatedStop = stopping.compareAndSet(false, true);
        if (initiatedStop) {
            log.info("[{}] ⏹  Stopping Essentials Wal2JsonTailer", slotName);
            try {
                if (loopFuture != null) {
                    loopFuture.cancel(true);
                }
            } finally {
                if (executor != null) {
                    executor.shutdownNow();
                }
            }
        }
        transitionToStoppedState("stopped");
    }

    private void runPollLoop() {
        long backoffMs = wal2JsonTailerProperties.getPollBackoffInterval().toMillis();

        try {
            while (!Thread.currentThread().isInterrupted() && !stopping.get()) {
                long attempt = connectAttempt.incrementAndGet();
                long startNs = System.nanoTime();

                try {
                    if (connectAttemptsCounter != null) connectAttemptsCounter.increment();

                    log.info("[{}] CDC connect attempt #{} (backoffMs={}, pollIntervalMs={})",
                             slotName, attempt, backoffMs, wal2JsonTailerProperties.getPollInterval().toMillis());

                    streamOnce();

                    long durMs = (System.nanoTime() - startNs) / 1_000_000;
                    log.info("[{}] CDC streamOnce exited normally (attempt #{}, durationMs={})",
                             slotName, attempt, durMs);

                    backoffMs = wal2JsonTailerProperties.getPollBackoffInterval().toMillis();
                } catch (CancellationException ignored) {
                    log.info("[{}] CDC loop cancelled", slotName);
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("[{}] CDC loop interrupted", slotName);
                    return;
                } catch (Exception e) {
                    if (stopping.get() || Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    if (connectFailuresCounter != null) connectFailuresCounter.increment();

                    long durMs = (System.nanoTime() - startNs) / 1_000_000;

                    log.warn("[{}] CDC streamOnce failed (attempt #{}, durationMs={}, backoffMsNext={}, " +
                                     "messages={}, inboxWrites={}, inboxWriteFailures={}, handlerFailures={}, " +
                                     "lastReceiveLsn={}, lastAckedLsn={}, lastMsgPreview='{}')",
                             slotName,
                             attempt,
                             durMs,
                             Math.min(wal2JsonTailerProperties.getMaxPollBackoffInterval().toMillis(), backoffMs * wal2JsonTailerProperties.getBackOffFactor()),
                             messagesReceived.get(),
                             inboxWrites.get(),
                             inboxWriteFailures.get(),
                             handlerFailures.get(),
                             lastReceiveLsn.get(),
                             lastAckedLsn.get(),
                             lastMessagePreview.get(),
                             e);

                    try {
                        sleepBackoffWithJitter(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("[{}] CDC interrupted during backoff, shutting down", slotName);
                        return;
                    }

                    backoffMs = (long) Math.min(wal2JsonTailerProperties.getMaxPollBackoffInterval().toMillis(), backoffMs * wal2JsonTailerProperties.getBackOffFactor());
                }
            }
        } finally {
            transitionToStoppedState("stopped");
        }
    }

    private void transitionToStoppedState(String reason) {
        slotLockAcquired.set(false);
        availability.inactive(slotName, reason);
        started.set(false);
        log.info("[{}] 🛑 Stopped Essentials Wal2JsonTailer", slotName);
    }

    private void streamOnce() throws SQLException, InterruptedException {
        log.info("[{}] Opening replication connection...", slotName);

        Connection replConn = null;
        try {
            replConn = replicationDataSource.getConnection();
            replConn.setAutoCommit(true);
            PGConnection pgConn = replConn.unwrap(PGConnection.class);

            if (log.isDebugEnabled()) {
                log.debug("[{}] Replication connection established url={} backendPid={}",
                          slotName,
                          replConn.getMetaData().getURL(),
                          pgConn.getBackendPID());
            }

            if (!tryAcquireSlotLock(replConn, slotName)) {
                log.info("[{}] CDC slot lock not acquired; another tailer is active for this slot", slotName);
                availability.inactive(slotName, "slot lock not acquired");
                sleepQuietly(wal2JsonTailerProperties.getPollInterval());
                return;
            }

            jdbi.useHandle(handle -> PgReplicationSlots.ensureSlot(handle.getConnection(), slotName, pgSlotMode));

            try (PGReplicationStream stream =
                         pgConn.getReplicationAPI()
                               .replicationStream()
                               .logical()
                               .withSlotName(slotName)
                               .withSlotOption("include-xids", wal2JsonTailerProperties.isIncludeXids())
                               .withSlotOption("include-timestamp", wal2JsonTailerProperties.isIncludeTimestamp())
                               .withSlotOption("include-lsn", wal2JsonTailerProperties.isIncludeLsn())
                               .withSlotOption("pretty-print", wal2JsonTailerProperties.isPrettyPrint())
                               .withStatusInterval((int) wal2JsonTailerProperties.getReplicationStatusInterval().toMillis(), TimeUnit.MILLISECONDS)
                               .start()) {

                log.info("[{}] Logical replication stream started", slotName);
                availability.active(slotName);

                if (connectSuccessCounter != null) connectSuccessCounter.increment();

                streamStartedLatch.countDown();

                while (!Thread.currentThread().isInterrupted() && !stopping.get()) {
                    ByteBuffer msg = stream.readPending();
                    if (msg == null) {
                        long n = nullPolls.incrementAndGet();
                        if (log.isTraceEnabled() && (n % 100 == 0)) {
                            log.trace("[{}] No WAL message yet (null polls='{}')", slotName, n);
                        }
                        sleepQuietly(wal2JsonTailerProperties.getPollInterval());
                        continue;
                    }

                    byte[] jsonBytes = new byte[msg.remaining()];
                    msg.get(jsonBytes);

                    var    lsn    = stream.getLastReceiveLSN();
                    String lsnStr = (lsn != null ? lsn.asString() : null);

                    if (!walMessageFilter.shouldPersist(jsonBytes)) {
                        if (log.isTraceEnabled()) {
                            String filteredPayload = new String(jsonBytes, StandardCharsets.UTF_8);
                            log.trace("[{}] WAL message filtered out (slot='{}', lsn='{}', bytes='{}', payload='{}')", slotName, slotName, lsnStr,
                                      jsonBytes.length, filteredPayload.length() > 800 ? filteredPayload.substring(0, 800) + "..." : filteredPayload);
                        }
                        // still ACK so we don't clog the slot with irrelevant WAL
                        if (lsn != null) {
                            stream.setAppliedLSN(lsn);
                            stream.setFlushedLSN(lsn);
                            stream.forceUpdateStatus();
                            lastAckedLsn.set(lsn.asString());
                        }
                        continue;
                    }
                    lastReceiveLsn.set(lsnStr != null ? lsnStr : "n/a");

                    long m = messagesReceived.incrementAndGet();
                    if (messagesReceivedCounter != null) messagesReceivedCounter.increment();
                    lastMessageEpochMs.set(System.currentTimeMillis());

                    String json = new String(jsonBytes, StandardCharsets.UTF_8);
                    lastMessagePreview.set(json.length() > 300 ? json.substring(0, 300) + "..." : json);

                    if (log.isTraceEnabled()) {
                        log.trace("[{}] WAL message #{} lsn='{}' bytes='{}' payload='{}'",
                                  slotName, m, lastReceiveLsn.get(), jsonBytes.length,
                                  json.length() > 800 ? json.substring(0, 800) + "..." : json);
                    } else if (m == 1) {
                        log.info("[{}] First WAL message received lsn='{}' bytes='{}' preview='{}'",
                                 slotName, lastReceiveLsn.get(), jsonBytes.length, lastMessagePreview.get());
                    }

                    boolean inserted;
                    try {
                        if (lsnStr == null) {
                            throw new IllegalStateException("PGReplicationStream returned null LSN for received message");
                        }

                        if (deliveryMode == CdcDeliveryMode.DIRECT) {
                            var events = walParserMode == WalParserMode.BYTES
                                         ? directConverter.convert(jsonBytes)
                                         : directConverter.convert(json);
                            if (!events.isEmpty()) {
                                directOnEvents.accept(events);
                            }
                            inserted = true;
                        } else {
                            inserted = inboxRepository.insertIfAbsent(slotName, lsnStr, jsonBytes);
                        }

                        if (inserted) {
                            inboxWrites.incrementAndGet();
                            if (inboxWritesCounter != null) inboxWritesCounter.increment();
                        } else {
                            inboxDuplicateWrites.incrementAndGet();
                            if (inboxDuplicatesCounter != null) inboxDuplicatesCounter.increment();

                            if (log.isDebugEnabled()) {
                                log.debug("[{}] Inbox already had message (slot={}, lsn={}) -> acking anyway",
                                          slotName, slotName, lsnStr);
                            }
                        }
                    } catch (Exception inboxEx) {
                        inboxWriteFailures.incrementAndGet();
                        handlerFailures.incrementAndGet();
                        if (inboxWriteFailuresCounter != null) inboxWriteFailuresCounter.increment();
                        if (handlerFailuresCounter != null) handlerFailuresCounter.increment();

                        var decision = errorHandler.onMessageError(slotName, json, inboxEx);
                        log.warn("[{}] CDC inbox write failed (decision='{}', lsn='{}', msgPreview='{}'): '{}'",
                                 slotName, decision, lastReceiveLsn.get(), lastMessagePreview.get(),
                                 inboxEx.getMessage(), inboxEx);

                        switch (decision) {
                            case CONTINUE -> {
                                // IMPORTANT: do NOT ack; we want at-least-once delivery into inbox.
                                // Continuing means we keep polling; the same WAL may be redelivered.
                                continue;
                            }
                            case STOP -> {
                                stopping.set(true);
                                return;
                            }
                            case RETRY_CONNECTION -> throw inboxEx;
                        }
                    }

                    // ACK only after inbox write succeeded
                    stream.setAppliedLSN(lsn);
                    stream.setFlushedLSN(lsn);
                    stream.forceUpdateStatus();
                    lastAckedLsn.set(lsnStr);
                }
            }
        } catch (Exception e) {
            availability.failed(slotName, e.getMessage());
            Wal2JsonTailerErrorHandler.Decision decision = errorHandler.onStreamError(slotName, e);

            log.warn("[{}] CDC stream error (decision={}): '{}'", slotName, decision, e.getMessage(), e);

            switch (decision) {
                case CONTINUE -> {
                    return;
                }
                case STOP -> {
                    stopping.set(true);
                    return;
                }
                case RETRY_CONNECTION -> {
                    if (e instanceof InterruptedException ie) throw ie;
                    if (e instanceof SQLException se) throw se;
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException(msg("[{}] Retry wal message poll", slotName), e);
                }
            }
        } finally {
            if (replConn != null) {
                try {
                    if (slotLockAcquired.get()) {
                        releaseSlotLock(replConn, slotName);
                    }
                } catch (Exception e) {
                    log.warn("[{}] Failed to release advisory slot lock: {}", slotName, e.getMessage(), e);
                } finally {
                    slotLockAcquired.set(false);
                    try {
                        replConn.close();
                    } catch (SQLException closeEx) {
                        log.debug("[{}] Failed to close replication connection: {}", slotName, closeEx.getMessage(), closeEx);
                    }
                }
            } else {
                slotLockAcquired.set(false);
            }
        }
    }

    private void sleepBackoffWithJitter(long baseMs) throws InterruptedException {
        long jitter = (long) (baseMs * wal2JsonTailerProperties.getJitterRatio());
        long delay  = baseMs + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        Thread.sleep(Math.max(0, delay));
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static long slotLockKey(String slotName) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("essentials:cdc:slot:" + slotName).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute advisory lock key for slot '" + slotName + "'", e);
        }
    }

    private boolean tryAcquireSlotLock(Connection c, String slotName) throws SQLException {
        try (var ps = c.prepareStatement("select pg_try_advisory_lock(?)")) {
            ps.setLong(1, slotLockKey(slotName));
            try (var rs = ps.executeQuery()) {
                rs.next();
                boolean acquired = rs.getBoolean(1);
                slotLockAcquired.set(acquired);
                return acquired;
            }
        }
    }

    private void releaseSlotLock(Connection c, String slotName) throws SQLException {
        try (var ps = c.prepareStatement("select pg_advisory_unlock(?)")) {
            ps.setLong(1, slotLockKey(slotName));
            ps.execute();
        }
    }

    public void startAndAwaitReady(Duration timeout) {
        start();
        boolean ok = awaitStreamStarted(timeout);
        if (!ok) {
            stop();
            throw new IllegalStateException("Wal2JsonTailer did not become ready within " + timeout
                                                    + " (slot=" + slotName + ", lastReceiveLsn=" + lastReceiveLsn.get()
                                                    + ", lastAckedLsn=" + lastAckedLsn.get() + ")");
        }
    }

    private boolean awaitStreamStarted(Duration timeout) {
        try {
            return streamStartedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    public Wal2JsonTailerStatus getStatus() {
        return new Wal2JsonTailerStatus(
                slotName,
                slotLockAcquired.get(),
                started.get(),
                lastReceiveLsn.get(),
                lastAckedLsn.get(),
                lastMessageEpochMs.get()
        );
    }

    public record Wal2JsonTailerStatus(
            String slotName,
            boolean slotLockAcquired,
            boolean started,
            String lastReceiveLsn,
            String lastAckedLsn,
            long lastMessageEpochMs
    ) {
    }

    final class PgReplicationSlots {

        static final String EXPECTED_PLUGIN = "wal2json";

        static final class SlotInfo {
            final String slotName;
            final String slotType;    // "logical" or "physical"
            final String plugin;      // wal2json, pgoutput, ...
            final String database;    // db name for logical slot
            final Integer activePid;  // null when not active
            final boolean temporary;

            SlotInfo(String slotName, String slotType, String plugin, String database, Integer activePid, boolean temporary) {
                this.slotName = slotName;
                this.slotType = slotType;
                this.plugin = plugin;
                this.database = database;
                this.activePid = activePid;
                this.temporary = temporary;
            }

            boolean isLogical() {
                return "logical".equalsIgnoreCase(slotType);
            }

            boolean isActive() {
                return activePid != null;
            }

            @Override
            public String toString() {
                return "SlotInfo{" +
                        "slotName='" + slotName + '\'' +
                        ", slotType='" + slotType + '\'' +
                        ", plugin='" + plugin + '\'' +
                        ", database='" + database + '\'' +
                        ", activePid=" + activePid +
                        ", temporary=" + temporary +
                        '}';
            }
        }

        static Integer backendPid(Connection c) throws SQLException {
            try (var ps = c.prepareStatement("select pg_backend_pid()")) {
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }

        static SlotInfo findSlot(Connection c, String slotName) throws SQLException {
            // Note: active_pid != null means some backend is actively using the slot (walsender).
            // For logical slots, only one consumer can be active at a time.
            String sql = """
            select slot_name,
                   slot_type,
                   plugin,
                   database,
                   active_pid,
                   temporary
              from pg_replication_slots
             where slot_name = ?
            """;

            try (var ps = c.prepareStatement(sql)) {
                ps.setString(1, slotName);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) return null;

                    return new SlotInfo(
                            rs.getString("slot_name"),
                            rs.getString("slot_type"),
                            rs.getString("plugin"),
                            rs.getString("database"),
                            (Integer) rs.getObject("active_pid"),
                            rs.getBoolean("temporary")
                    );
                }
            }
        }

        static void createLogicalWal2JsonSlot(Connection c, String slotName) throws SQLException {
            try (var ps = c.prepareStatement("select * from pg_create_logical_replication_slot(?, ?)")) {
                ps.setString(1, slotName);
                ps.setString(2, EXPECTED_PLUGIN);
                ps.execute();
            }
        }

        static void dropSlot(Connection c, String slotName) throws SQLException {
            try (var ps = c.prepareStatement("select pg_drop_replication_slot(?)")) {
                ps.setString(1, slotName);
                ps.execute();
            }
        }

        /**
         * Ensure slot exists according to PgSlotMode.
         *
         * Rules:
         * - Always validate: slot_type must be logical, plugin must be wal2json (if slot exists).
         * - "Owned by another logical consumer" == active_pid != null (slot currently in use).
         */
        static void ensureSlot(Connection c, String slotName, PgSlotMode mode) throws SQLException {
            requireNonNull(c, "connection cannot be null");
            requireNonNull(slotName, "slotName cannot be null");
            requireNonNull(mode, "mode cannot be null");

            SlotInfo slot = findSlot(c, slotName);

            switch (mode) {
                case EXTERNAL -> {
                    // Never create/drop. But we should still validate if it exists.
                    if (slot == null) {
                        // up to you if you want this to be warn vs fail; EXTERNAL often wants fail-fast.
                        throw new SQLException("Replication slot '" + slotName + "' missing (mode=EXTERNAL)");
                    }
                    validateSlotOrThrow(slotName, slot);
                    if (slot.isActive()) {
                        // In EXTERNAL mode, being active might be expected (another process tailing).
                        // But if *we* are starting a tailer too, we should fail to avoid double consumers.
                        throw new SQLException("Replication slot '" + slotName + "' is already active (active_pid=" +
                                                       slot.activePid + ") (mode=EXTERNAL)");
                    }
                }

                case REQUIRE_EXISTING -> {
                    if (slot == null) {
                        throw new SQLException("Replication slot '" + slotName + "' does not exist (mode=REQUIRE_EXISTING)");
                    }
                    validateSlotOrThrow(slotName, slot);
                    if (slot.isActive()) {
                        throw new SQLException("Replication slot '" + slotName + "' is already active (active_pid=" +
                                                       slot.activePid + ") — owned by another logical consumer");
                    }
                }

                case CREATE_IF_MISSING -> {
                    if (slot == null) {
                        createLogicalWal2JsonSlot(c, slotName);
                        return;
                    }
                    validateSlotOrThrow(slotName, slot);
                    if (slot.isActive()) {
                        throw new SQLException("Replication slot '" + slotName + "' is already active (active_pid=" +
                                                       slot.activePid + ") — owned by another logical consumer");
                    }
                }

                case RECREATE -> {
                    if (slot != null) {
                        validateSlotOrThrow(slotName, slot);
                        if (slot.isActive()) {
                            throw new SQLException("Replication slot '" + slotName + "' is active (active_pid=" +
                                                           slot.activePid + "); refusing to drop while in use (mode=RECREATE)");
                        }
                        dropSlot(c, slotName);
                    }
                    createLogicalWal2JsonSlot(c, slotName);
                }

                default -> throw new SQLException("Unsupported PgSlotMode: " + mode);
            }
        }

        private static void validateSlotOrThrow(String slotName, SlotInfo slot) throws SQLException {
            if (!slot.isLogical()) {
                throw new SQLException("Replication slot '" + slotName + "' is not logical (slot_type=" + slot.slotType + ")");
            }
            if (slot.plugin == null || !EXPECTED_PLUGIN.equalsIgnoreCase(slot.plugin)) {
                throw new SQLException("Replication slot '" + slotName + "' uses unexpected plugin '" + slot.plugin +
                                               "' (expected '" + EXPECTED_PLUGIN + "')");
            }
            // database is typically non-null for logical slots; useful sanity check
            if (slot.database == null || slot.database.isBlank()) {
                throw new SQLException("Replication slot '" + slotName + "' has no database set (unexpected for logical slot)");
            }
            // temporary slots are usually not what we want here
            if (slot.temporary) {
                throw new SQLException("Replication slot '" + slotName + "' is temporary; expected a persistent slot");
            }
        }
    }

}
