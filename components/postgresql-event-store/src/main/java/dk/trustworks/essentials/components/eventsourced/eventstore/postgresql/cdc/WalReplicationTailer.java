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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalReplicationTailerProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalParserMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.DirectLogicalReplicationEventConverter;
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
import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.slf4j.*;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * The {@code WalReplicationTailer} class is responsible for tailing PostgreSQL's Write-Ahead Log (WAL)
 * using a configured logical decoding plugin. It extracts changes from the replication stream,
 * applies optional filtering, and processes them for delivery to an inbox repository or other downstream systems.
 * <p>
 * This class implements the {@link Lifecycle} interface to provide
 * lifecycle management methods for starting and stopping the tailer.
 */
public class WalReplicationTailer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(WalReplicationTailer.class);

    private final DataSource                                                    replicationDataSource;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String                                                        slotName;
    private final CdcInboxRepository                                            inboxRepository;
    private final WalMessageFilter                                              walMessageFilter;
    private final MeterRegistry                                                 meterRegistry;
    private final WalReplicationTailerErrorHandler                              errorHandler;
    private final WalReplicationTailerProperties                                      tailerProperties;
    private final PgSlotMode                                                    pgSlotMode;
    private final CdcMode                                                       cdcMode;
    private final CdcAvailability                                               availability;
    private final CdcDeliveryMode                                               deliveryMode;
    private final WalParserMode                                                 walParserMode;
    private final LogicalDecodingPlugin                                         logicalDecodingPlugin;
    private final DirectLogicalReplicationEventConverter                       directEventConverter;
    private final Consumer<List<PersistedEvent>>                                directOnEvents;
    private final PgOutputMessageDecoder                                        pgOutputMessageDecoder;
    private final PgOutputRowChangeDecoder                                      pgOutputRowChangeDecoder;

    private ExecutorService executor;
    private Future<?>       loopFuture;

    private final    AtomicBoolean started         = new AtomicBoolean(false);
    private final    AtomicBoolean stopping        = new AtomicBoolean(false);
    private volatile boolean       pluginAvailable = false;

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
     * Constructs a new instance of the WalReplicationTailer class.
     *
     * @param replicationDataSource The {@link DataSource} used for replication connection.
     * @param jdbi The {@link Jdbi} instance for database interaction.
     * @param unitOfWorkFactory The factory responsible for creating {@link HandleAwareUnitOfWork} instances.
     * @param slotName The name of the replication slot used for listening to WAL changes.
     * @param inboxRepository The repository for handling CDC inbox operations.
     * @param tailerProperties The configuration properties for the WAL replication tailer.
     * @param pgSlotMode The PostgreSQL slot mode to be used.
     * @param cdcMode The mode of change data capture (CDC) being employed.
     * @param availability The mechanism for checking CDC availability.
     * @param meterRegistry The optional {@link MeterRegistry} for collecting metrics.
     * @param errorHandler The optional {@link WalReplicationTailerErrorHandler} for handling errors.
     */
    public WalReplicationTailer(
            DataSource replicationDataSource,
            Jdbi jdbi,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            String slotName,
            CdcInboxRepository inboxRepository,
            WalReplicationTailerProperties tailerProperties,
            PgSlotMode pgSlotMode,
            CdcMode cdcMode,
            CdcAvailability availability,
            Optional<MeterRegistry> meterRegistry,
            Optional<WalReplicationTailerErrorHandler> errorHandler) {
        this(replicationDataSource,
             jdbi,
             unitOfWorkFactory,
             slotName,
             inboxRepository,
             tailerProperties,
             pgSlotMode,
             cdcMode,
             CdcDeliveryMode.INBOX,
             WalParserMode.STRING,
             Optional.empty(),
             Optional.empty(),
             Optional.empty(),
             Optional.empty(),
             availability,
             meterRegistry,
             errorHandler);
    }

    /**
     * Constructs a new instance of WalReplicationTailer, which processes logical replication entries
     * for change data capture (CDC) and manages the handling of events based on the specified configurations.
     */
    public WalReplicationTailer(
            DataSource replicationDataSource,
            Jdbi jdbi,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            String slotName,
            CdcInboxRepository inboxRepository,
            WalReplicationTailerProperties tailerProperties,
            PgSlotMode pgSlotMode,
            CdcMode cdcMode,
            CdcDeliveryMode deliveryMode,
            WalParserMode walParserMode,
            Optional<DirectLogicalReplicationEventConverter> directEventConverter,
            Optional<Consumer<List<PersistedEvent>>> directOnEvents,
            Optional<WalMessageFilter> walMessageFilter,
            Optional<LogicalDecodingPlugin> logicalDecodingPlugin,
            CdcAvailability availability,
            Optional<MeterRegistry> meterRegistry,
            Optional<WalReplicationTailerErrorHandler> errorHandler) {
        this.replicationDataSource = requireNonNull(replicationDataSource, "replicationDataSource cannot be null");
        requireNonNull(jdbi, "jdbi cannot be null");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null");
        this.slotName = requireNonNull(slotName, "slotName cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(slotName);
        this.inboxRepository = requireNonNull(inboxRepository, "inboxRepository cannot be null");
        this.tailerProperties = requireNonNull(tailerProperties, "tailerProperties cannot be null");
        this.pgSlotMode = requireNonNull(pgSlotMode, "pgSlotMode cannot be null");
        this.cdcMode = requireNonNull(cdcMode, "cdcMode cannot be null");
        this.deliveryMode = requireNonNull(deliveryMode, "deliveryMode cannot be null");
        this.walParserMode = requireNonNull(walParserMode, "walParserMode cannot be null");
        this.logicalDecodingPlugin = logicalDecodingPlugin.orElseGet(() -> new Wal2JsonLogicalDecodingPlugin(tailerProperties));
        this.directEventConverter = directEventConverter.orElse(null);
        this.directOnEvents = directOnEvents.orElse(null);
        this.pgOutputMessageDecoder = this.logicalDecodingPlugin instanceof PgOutputLogicalDecodingPlugin plugin
                                      ? new PgOutputMessageDecoder(plugin.protocolVersion())
                                      : null;
        this.pgOutputRowChangeDecoder = pgOutputMessageDecoder != null ? new PgOutputRowChangeDecoder() : null;
        this.availability = requireNonNull(availability, "availability cannot be null");
        if (this.deliveryMode == CdcDeliveryMode.DIRECT) {
            requireNonNull(this.directOnEvents, "directOnEvents cannot be null in DIRECT delivery mode");
            requireNonNull(this.directEventConverter, "directEventConverter cannot be null in DIRECT delivery mode");
        }
        requireNonNull(tailerProperties.getPollInterval(), "pollInterval cannot be null");
        requireNonNull(tailerProperties.getPollBackoffInterval(), "pollBackoffInterval cannot be null");
        requireNonNull(tailerProperties.getMaxPollBackoffInterval(), "maxPollBackInterval cannot be null");
        requireNonNull(tailerProperties.getReplicationStatusInterval(), "replicationStatusInterval cannot be null");
        requireTrue(tailerProperties.getJitterRatio() > 0.0 && tailerProperties.getJitterRatio() < 0.5, "jitterRatio must be in [0.0..0.5]");
        requireTrue(tailerProperties.getBackOffFactor() > 1, "backOffFactor must be > 1");
        this.meterRegistry = meterRegistry.orElse(null);
        this.errorHandler = errorHandler.orElseGet(DefaultWalReplicationTailerErrorHandler::new);
        this.walMessageFilter = walMessageFilter.orElseGet(RegexWalMessageFilter::new);
        initMetrics();
        if (deliveryMode == CdcDeliveryMode.INBOX && currentPipelineUnsupportedReason() == null) {
            unitOfWorkFactory.usingUnitOfWork(inboxRepository::createTableAndIndexes);
        }
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
        log.info("[{}] ⚙️ Starting Essentials WalReplicationTailer", slotName);

        if (!initializePluginAvailability()) return;

        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wal2json-tailer-" + slotName);
            t.setDaemon(true);
            return t;
        });

        this.loopFuture = executor.submit(this::runPollLoop);

        log.info("[{}] WalReplicationTailer started", slotName);
    }

    @Override
    public void stop() {
        if (!started.get()) {
            return;
        }
        boolean initiatedStop = stopping.compareAndSet(false, true);
        if (initiatedStop) {
            log.info("[{}] ⏹  Stopping Essentials WalReplicationTailer", slotName);
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

    /**
     * Runs the polling loop for the Change Data Capture (CDC) process. This method handles
     * connection attempts, retries, backoff intervals, and error handling during the streaming
     * process. It is designed to repeatedly attempt connecting to a data stream, process
     * the retrieved messages, and gracefully handle termination or interruptions.
     * <p>
     * The loop will continue running until either the thread is interrupted or a stopping
     * signal is received. In the event of connection failures or exceptions, the method
     * implements an exponential backoff strategy with jitter to manage reconnection intervals.
     * <p>
     * Key operational logic includes:
     * - Incrementing connection attempt counters for tracking.
     * - Logging informative messages about connection attempts and execution outcomes.
     * - Restoring backoff intervals based on configured properties after successful attempts.
     * - Handling exceptions such as cancellations, interruptions, and streaming failures.
     * - Safely transitioning to a stopped state once the loop exits.
     * <p>
     * Backoff strategy:
     * - Uses configurable initial backoff interval, backoff factor, and maximum backoff
     * interval to determine the delay between retries.
     * - Implements jitter in the backoff process to avoid synchronized retry cycles.
     */
    private void runPollLoop() {
        long backoffMs = tailerProperties.getPollBackoffInterval().toMillis();

        try {
            while (!Thread.currentThread().isInterrupted() && !stopping.get()) {
                long attempt = connectAttempt.incrementAndGet();
                long startNs = System.nanoTime();

                try {
                    incrementCounter(connectAttemptsCounter);
                    logConnectAttempt(attempt, backoffMs);
                    streamOnce();
                    logNormalExit(attempt, startNs);
                    backoffMs = tailerProperties.getPollBackoffInterval().toMillis();
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

                    incrementCounter(connectFailuresCounter);
                    logFailedAttempt(attempt, startNs, backoffMs, e);

                    try {
                        sleepBackoffWithJitter(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("[{}] CDC interrupted during backoff, shutting down", slotName);
                        return;
                    }

                    backoffMs = (long) Math.min(tailerProperties.getMaxPollBackoffInterval().toMillis(), backoffMs * tailerProperties.getBackOffFactor());
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
        log.info("[{}] 🛑 Stopped Essentials WalReplicationTailer", slotName);
    }

    /**
     * Establishes and manages a Logical Replication Stream to continuously consume changes
     * from a PostgreSQL replication slot. This method handles WAL (Write Ahead Log) messages
     * and applies filtering, processing, and acknowledgment logic based on the given
     * configurations and strategies.
     * <p>
     * Key functionality of the method includes:
     * - Establishing a replication connection using the configured replication slot name.
     * - Ensuring that another process does not already hold the slot lock.
     * - Starting a logical replication stream and processing WAL messages.
     * - Filtering and deciding whether or not to persist WAL messages.
     * - Writing WAL message data to an inbox or directly handling events based on the delivery mode.
     * - Acknowledging processed WAL messages by setting and flushing LSN (Log Sequence Numbers).
     * - Handling intermittent errors and deciding whether to continue, stop, or retry the connection.
     * - Maintaining metrics and logs to provide visibility into the replication behavior.
     */
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
                sleepQuietly(tailerProperties.getPollInterval());
                return;
            }

            ensureReplicationSlot();

            try (PGReplicationStream stream =
                         logicalStreamBuilder(pgConn)
                                 .withStatusInterval((int) tailerProperties.getReplicationStatusInterval().toMillis(), TimeUnit.MILLISECONDS)
                                 .start()) {

                onStreamStarted();

                while (!Thread.currentThread().isInterrupted() && !stopping.get()) {
                    ByteBuffer msg = stream.readPending();
                    if (msg == null) {
                        handleNullPoll();
                        continue;
                    }
                    if (!handleStreamMessage(stream, msg)) continue;
                }
            }
        } catch (Exception e) {
            availability.failed(slotName, e.getMessage());
            WalReplicationTailerErrorHandler.Decision decision = errorHandler.onStreamError(slotName, e);

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

    private boolean initializePluginAvailability() {
        var unsupportedReason = currentPipelineUnsupportedReason();
        if (unsupportedReason != null) {
            pluginAvailable = false;
            availability.failed(slotName, unsupportedReason);
            log.warn("{}", unsupportedReason);
            return handleUnavailablePlugin();
        }

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            boolean logicalOk = PostgresqlUtil.isLogicalDecodingEnabled(uow.handle());
            if (!logicalOk) {
                log.warn("Logical decoding not enabled (wal_level/max_replication_slots/max_wal_senders). CDC disabled.");
                pluginAvailable = false;
                availability.failed(slotName, "logical decoding not enabled");
                return;
            }

            var unusableReason = logicalDecodingPlugin.unusableReason(uow.handle());
            pluginAvailable = unusableReason.isEmpty();
            if (!pluginAvailable) {
                log.warn("{}", unusableReason.get());
                availability.failed(slotName, unusableReason.get());
            }
        });

        if (pluginAvailable) return true;
        return handleUnavailablePlugin();
    }

    private boolean handleUnavailablePlugin() {
        started.set(false);
        log.info("{} CDC is not available - cannot start WalReplicationTailer", logicalDecodingPlugin.pluginName());
        if (cdcMode == CdcMode.REQUIRE) {
            throw new IllegalStateException(logicalDecodingPlugin.pluginName() + " CDC is required but not available");
        }
        return false;
    }

    private void logConnectAttempt(long attempt, long backoffMs) {
        log.info("[{}] CDC connect attempt #{} (backoffMs={}, pollIntervalMs={})",
                 slotName, attempt, backoffMs, tailerProperties.getPollInterval().toMillis());
    }

    private void logNormalExit(long attempt, long startNs) {
        long durMs = (System.nanoTime() - startNs) / 1_000_000;
        log.info("[{}] CDC streamOnce exited normally (attempt #{}, durationMs={})",
                 slotName, attempt, durMs);
    }

    private void logFailedAttempt(long attempt, long startNs, long backoffMs, Exception e) {
        long durMs = (System.nanoTime() - startNs) / 1_000_000;
        long nextBackoffMs = (long) Math.min(tailerProperties.getMaxPollBackoffInterval().toMillis(),
                                             backoffMs * tailerProperties.getBackOffFactor());
        log.warn("[{}] CDC streamOnce failed (attempt #{}, durationMs={}, backoffMsNext={}, " +
                         "messages={}, inboxWrites={}, inboxWriteFailures={}, handlerFailures={}, " +
                         "lastReceiveLsn={}, lastAckedLsn={}, lastMsgPreview='{}')",
                 slotName,
                 attempt,
                 durMs,
                 nextBackoffMs,
                 messagesReceived.get(),
                 inboxWrites.get(),
                 inboxWriteFailures.get(),
                 handlerFailures.get(),
                 lastReceiveLsn.get(),
                 lastAckedLsn.get(),
                 lastMessagePreview.get(),
                 e);
    }

    private void ensureReplicationSlot() {
        unitOfWorkFactory.usingUnitOfWork(uow ->
                                                  PgReplicationSlots.ensureSlot(uow.handle().getConnection(), slotName, pgSlotMode, logicalDecodingPlugin.pluginName()));
    }

    private ChainedLogicalStreamBuilder logicalStreamBuilder(PGConnection pgConn) {
        var builder = pgConn.getReplicationAPI()
                            .replicationStream()
                            .logical()
                            .withSlotName(slotName);
        logicalDecodingPlugin.slotOptions().forEach((name, value) -> applySlotOption(builder, name, value));
        return builder;
    }

    private void applySlotOption(ChainedLogicalStreamBuilder builder, String name, Object value) {
        if (value instanceof Boolean bool) {
            builder.withSlotOption(name, bool);
        } else if (value instanceof Number number) {
            builder.withSlotOption(name, number.intValue());
        } else if (value != null) {
            builder.withSlotOption(name, value.toString());
        }
    }

    private void onStreamStarted() {
        log.info("[{}] Logical replication stream started", slotName);
        availability.active(slotName);
        incrementCounter(connectSuccessCounter);
        streamStartedLatch.countDown();
    }

    private void handleNullPoll() {
        long n = nullPolls.incrementAndGet();
        if (log.isTraceEnabled() && (n % 100 == 0)) {
            log.trace("[{}] No WAL message yet (null polls='{}')", slotName, n);
        }
        sleepQuietly(tailerProperties.getPollInterval());
    }

    private boolean handleStreamMessage(PGReplicationStream stream, ByteBuffer msg) throws Exception {
        var payload = new WalPayload(toByteArray(msg));
        var lsn     = stream.getLastReceiveLSN();
        var lsnStr  = lsn != null ? lsn.asString() : null;

        if (isPgOutputPlugin()) {
            recordReceivedMessage(payload, lsnStr);
            try {
                persistMessage(payload, lsnStr);
            } catch (ContinueStreamingException | StopStreamingException ignored) {
                return false;
            }
            acknowledge(stream, lsn);
            return true;
        }

        if (!walMessageFilter.shouldPersist(payload.bytes())) {
            logFilteredMessage(payload, lsnStr);
            acknowledge(stream, lsn);
            return false;
        }

        recordReceivedMessage(payload, lsnStr);
        try {
            persistMessage(payload, lsnStr);
        } catch (ContinueStreamingException | StopStreamingException ignored) {
            return false;
        }
        acknowledge(stream, lsn);
        return true;
    }

    private void logFilteredMessage(WalPayload payload, String lsnStr) {
        if (log.isTraceEnabled()) {
            log.trace("[{}] WAL message filtered out (slot='{}', lsn='{}', bytes='{}', payload='{}')",
                      slotName, slotName, lsnStr, payload.bytes().length, payload.preview(800));
        }
    }

    private void recordReceivedMessage(WalPayload payload, String lsnStr) {
        lastReceiveLsn.set(lsnStr != null ? lsnStr : "n/a");
        long m = messagesReceived.incrementAndGet();
        incrementCounter(messagesReceivedCounter);
        lastMessageEpochMs.set(System.currentTimeMillis());
        if (walParserMode == WalParserMode.STRING) {
            lastMessagePreview.set(payload.preview(300));
        }

        if (log.isTraceEnabled()) {
            log.trace("[{}] WAL message #{} lsn='{}' bytes='{}' payload='{}'",
                      slotName, m, lastReceiveLsn.get(), payload.bytes().length, payload.preview(800));
        }
    }

    private void persistMessage(WalPayload payload, String lsnStr) throws Exception {
        try {
            if (lsnStr == null) {
                throw new IllegalStateException("PGReplicationStream returned null LSN for received message");
            }

            boolean inserted = deliveryMode == CdcDeliveryMode.DIRECT
                               ? dispatchDirectly(payload)
                               : inboxRepository.insertIfAbsent(slotName, lsnStr, payload.bytes());
            recordPersistenceOutcome(inserted, lsnStr);
        } catch (Exception inboxEx) {
            handlePersistenceFailure(payload, inboxEx);
        }
    }

    private boolean dispatchDirectly(WalPayload payload) {
        List<PersistedEvent> events;
        if (isPgOutputDirectMode()) {
            events = dispatchPgOutputDirectly(payload);
        } else {
            events = directEventConverter.convertWal2Json(
                    payload.bytes(),
                    walParserMode == WalParserMode.BYTES ? null : payload.asString(),
                    walParserMode
            );
        }
        if (!events.isEmpty()) {
            directOnEvents.accept(events);
        }
        return true;
    }

    private List<PersistedEvent> dispatchPgOutputDirectly(WalPayload payload) {
        var decodedMessage = pgOutputMessageDecoder.decode(payload.bytes());
        var rowChanges     = pgOutputRowChangeDecoder.accept(decodedMessage);
        if (rowChanges.isEmpty()) return List.of();

        var events = new ArrayList<PersistedEvent>(rowChanges.size());
        for (var rowChange : rowChanges) {
            directEventConverter.convertPgOutputIfRelevant(rowChange).ifPresent(events::add);
        }
        return events;
    }

    private void recordPersistenceOutcome(boolean inserted, String lsnStr) {
        if (inserted) {
            inboxWrites.incrementAndGet();
            incrementCounter(inboxWritesCounter);
            return;
        }

        inboxDuplicateWrites.incrementAndGet();
        incrementCounter(inboxDuplicatesCounter);
        if (log.isDebugEnabled()) {
            log.debug("[{}] Inbox already had message (slot={}, lsn={}) -> acking anyway", slotName, slotName, lsnStr);
        }
    }

    private void handlePersistenceFailure(WalPayload payload, Exception inboxEx) throws Exception {
        inboxWriteFailures.incrementAndGet();
        handlerFailures.incrementAndGet();
        incrementCounter(inboxWriteFailuresCounter);
        incrementCounter(handlerFailuresCounter);

        var decision = errorHandler.onMessageError(slotName, payload.asString(), inboxEx);
        log.warn("[{}] CDC inbox write failed (decision='{}', lsn='{}', msgPreview='{}'): '{}'",
                 slotName, decision, lastReceiveLsn.get(), lastMessagePreview.get(),
                 inboxEx.getMessage(), inboxEx);

        switch (decision) {
            case CONTINUE -> throw new ContinueStreamingException();
            case STOP -> {
                stopping.set(true);
                throw new StopStreamingException();
            }
            case RETRY_CONNECTION -> throw inboxEx;
        }
    }

    private void acknowledge(PGReplicationStream stream, org.postgresql.replication.LogSequenceNumber lsn) throws SQLException {
        if (lsn == null) return;
        stream.setAppliedLSN(lsn);
        stream.setFlushedLSN(lsn);
        stream.forceUpdateStatus();
        lastAckedLsn.set(lsn.asString());
    }

    private static byte[] toByteArray(ByteBuffer msg) {
        byte[] bytes = new byte[msg.remaining()];
        msg.get(bytes);
        return bytes;
    }

    private static void incrementCounter(Counter counter) {
        if (counter != null) counter.increment();
    }

    private void sleepBackoffWithJitter(long baseMs) throws InterruptedException {
        long jitter = (long) (baseMs * tailerProperties.getJitterRatio());
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
            MessageDigest md     = MessageDigest.getInstance("SHA-256");
            byte[]        digest = md.digest(("essentials:cdc:slot:" + slotName).getBytes(StandardCharsets.UTF_8));
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

    /**
     * This class represents a write-ahead log (WAL) payload that encapsulates
     * a byte array and provides utility methods for text-based interpretation
     * of the payload data. The class supports lazy decoding of the byte array
     * into a UTF-8 string and offers a way to preview the string content with
     * optional truncation for brevity.
     * <p>
     * The class is designed to be immutable and is intended for internal use.
     */
    private static final class WalPayload {
        private final byte[] bytes;
        private       String decoded;

        private WalPayload(byte[] bytes) {
            this.bytes = bytes;
        }

        /**
         * Converts the byte array to its corresponding UTF-8 string representation.
         * The conversion is performed lazily and cached for subsequent calls.
         *
         * @return the UTF-8 string representation of the byte array
         */
        private String asString() {
            if (decoded == null) {
                decoded = new String(bytes, StandardCharsets.UTF_8);
            }
            return decoded;
        }

        /**
         * Generates a preview of the text representation of the current object,
         * truncating it to the specified maximum length and appending "..."
         * if the text exceeds this length.
         *
         * @param maxLength the maximum number of characters allowed for the preview
         * @return a truncated version of the text representation with "..." appended if the length exceeds {@code maxLength};
         * otherwise, the full text representation
         */
        private String preview(int maxLength) {
            var text = asString();
            return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
        }

        private byte[] bytes() {
            return bytes;
        }
    }

    private static final class ContinueStreamingException extends Exception {
    }

    private static final class StopStreamingException extends Exception {
    }

    public void startAndAwaitReady(Duration timeout) {
        start();
        boolean ok = awaitStreamStarted(timeout);
        if (!ok) {
            stop();
            throw new IllegalStateException("WalReplicationTailer did not become ready within " + timeout
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

    private String currentPipelineUnsupportedReason() {
        if (isPgOutputPlugin()) {
            if (pgOutputMessageDecoder == null || pgOutputRowChangeDecoder == null) {
                return "CDC plugin 'pgoutput' is configured, but pgoutput message decoding pipeline is not fully configured";
            }
            if (deliveryMode == CdcDeliveryMode.DIRECT && directEventConverter == null) {
                return "CDC plugin 'pgoutput' is configured, but direct conversion pipeline is not fully configured";
            }
            return null;
        }
        return logicalDecodingPlugin.supportsCurrentPayloadPipeline() ? null : logicalDecodingPlugin.unsupportedReason();
    }

    private boolean isPgOutputPlugin() {
        return logicalDecodingPlugin instanceof PgOutputLogicalDecodingPlugin;
    }

    private boolean isPgOutputDirectMode() {
        return deliveryMode == CdcDeliveryMode.DIRECT && isPgOutputPlugin();
    }

    public WalReplicationTailerStatus getStatus() {
        return new WalReplicationTailerStatus(
                slotName,
                slotLockAcquired.get(),
                started.get(),
                lastReceiveLsn.get(),
                lastAckedLsn.get(),
                lastMessageEpochMs.get(),
                messagesReceived.get(),
                inboxWrites.get(),
                inboxDuplicateWrites.get(),
                inboxWriteFailures.get(),
                handlerFailures.get()
        );
    }

    public record WalReplicationTailerStatus(
            String slotName,
            boolean slotLockAcquired,
            boolean started,
            String lastReceiveLsn,
            String lastAckedLsn,
            long lastMessageEpochMs,
            long messagesReceived,
            long inboxWrites,
            long inboxDuplicateWrites,
            long inboxWriteFailures,
            long handlerFailures
    ) {
    }

}
