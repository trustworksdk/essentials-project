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
import java.util.function.Supplier;

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
    private final LogicalDecodingPlugin                                         logicalDecodingPlugin;
    private final Consumer<List<PersistedEvent>>                                directOnEvents;
    /**
     * Live supplier of fully-qualified event-stream table names (e.g. {@code public.orders_events}).
     * Used by {@code onStreamStarted()} to verify each event-stream table is covered by the
     * configured pgoutput publication — the single most common cause of "CDC runs but publishes
     * no events". Typically wired from
     * {@code () -> eventStore.getPersistenceStrategy().getSeparateTablePerEventStreamTableNameAggregates().keySet()}.
     * Always returns a non-null set; may be empty when aggregates haven't been registered yet
     * (startup race).
     */
    private final Supplier<Set<String>>                                         eventStreamTableNamesSupplier;
    /**
     * Opt-in flag that, when {@code true}, causes the tailer to force-drop-and-recreate the
     * replication slot at first connection (terminating any attached backend). The new slot
     * starts at the current {@code pg_current_wal_lsn()} with no historical backlog. Applied
     * only on the very first {@code streamOnce()} invocation after {@code start()} — subsequent
     * reconnects reuse the freshly-created slot. Destructive; see
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcSlotProperties#isRecreateOnStart()}.
     */
    private final boolean                                                       recreateSlotOnStart;
    private final AtomicBoolean                                                 firstStreamAttempt = new AtomicBoolean(true);

    /**
     * How often the tailer emits a "CDC heartbeat" INFO log from inside the streamOnce inner
     * loop when no message has arrived. Makes it obvious in operator logs whether the tailer is
     * receiving or sitting in null-poll zombie-stream. Also controls the backoff-sleep chunk
     * size so long reconnect waits produce periodic progress logs. Kept at 15s — short enough
     * to surface problems fast, long enough that a healthy idle stream doesn't spam.
     */
    private static final long HEARTBEAT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(15);

    /**
     * How often the tailer force-ack's the stream's current receive LSN when idle (no messages
     * flowing). Prevents {@code confirmed_flush_lsn} from sticking at the slot's start position
     * when pgoutput has nothing publication-relevant to emit — without this push, Postgres
     * retains all WAL past the stuck confirmed_flush_lsn, which empirically correlates with
     * slot-sender throttling / disconnection under stress.
     */
    private static final long IDLE_LSN_PUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

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
     * Constructs a new WalReplicationTailer.
     *
     * @param replicationDataSource  the replication {@link DataSource}
     * @param jdbi                   the {@link Jdbi} instance for db interaction
     * @param unitOfWorkFactory      the {@link HandleAwareUnitOfWork} factory
     * @param slotName               the replication slot name
     * @param inboxRepository        the CDC inbox repository
     * @param tailerProperties       the tailer configuration properties
     * @param pgSlotMode             the PostgreSQL slot lifecycle mode
     * @param cdcMode                REQUIRE / AUTO semantics for startup failures
     * @param deliveryMode           INBOX (default) or DIRECT
     * @param logicalDecodingPlugin  the plugin that owns payload decoding and gap extraction
     * @param directOnEvents         consumer for decoded events in DIRECT mode (required when deliveryMode=DIRECT)
     * @param walMessageFilter       optional raw-payload filter (applied only when plugin opts in via {@link LogicalDecodingPlugin#preFiltersRawPayloads()})
     * @param availability           CDC availability state machine
     * @param meterRegistry          optional metrics registry
     * @param errorHandler           optional error handler
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
            LogicalDecodingPlugin logicalDecodingPlugin,
            Optional<Consumer<List<PersistedEvent>>> directOnEvents,
            Optional<WalMessageFilter> walMessageFilter,
            CdcAvailability availability,
            Optional<MeterRegistry> meterRegistry,
            Optional<WalReplicationTailerErrorHandler> errorHandler) {
        this(replicationDataSource, jdbi, unitOfWorkFactory, slotName, inboxRepository,
             tailerProperties, pgSlotMode, cdcMode, deliveryMode, logicalDecodingPlugin,
             directOnEvents, walMessageFilter, availability, meterRegistry, errorHandler,
             Optional.empty(), false);
    }

    /**
     * Extended constructor that also accepts an {@code eventStreamTableNamesSupplier} used by
     * the {@code onStreamStarted} diagnostic logging to verify publication membership against
     * the configured event-stream tables, and a {@code recreateSlotOnStart} flag that force-
     * drops-and-recreates the replication slot at first connection. Exists as a separate
     * overload to preserve backward-compat with existing test wiring; production auto-config
     * should call this form.
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
            LogicalDecodingPlugin logicalDecodingPlugin,
            Optional<Consumer<List<PersistedEvent>>> directOnEvents,
            Optional<WalMessageFilter> walMessageFilter,
            CdcAvailability availability,
            Optional<MeterRegistry> meterRegistry,
            Optional<WalReplicationTailerErrorHandler> errorHandler,
            Optional<Supplier<Set<String>>> eventStreamTableNamesSupplier,
            boolean recreateSlotOnStart) {
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
        this.logicalDecodingPlugin = requireNonNull(logicalDecodingPlugin, "logicalDecodingPlugin cannot be null");
        this.directOnEvents = directOnEvents.orElse(null);
        this.eventStreamTableNamesSupplier = eventStreamTableNamesSupplier.orElse(Set::of);
        this.recreateSlotOnStart = recreateSlotOnStart;
        this.availability = requireNonNull(availability, "availability cannot be null");
        if (this.deliveryMode == CdcDeliveryMode.DIRECT) {
            requireNonNull(this.directOnEvents, "directOnEvents cannot be null in DIRECT delivery mode");
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
        if (deliveryMode == CdcDeliveryMode.INBOX) {
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

            // Plugin-specific bootstrap (pgoutput publication auto-manage, etc.) happens in
            // initializePluginAvailability() at tailer start — before the unusableReason()
            // check that would otherwise fail on a missing-publication. Per-reconnect prepare
            // would also work (the plugin operations are idempotent) but there's no
            // correctness need for it and avoiding the extra DB roundtrip per reconnect is
            // cheaper. If the publication is externally dropped after startup, recovery will
            // need a tailer restart.

            ensureReplicationSlot();

            try (PGReplicationStream stream =
                         logicalStreamBuilder(pgConn)
                                 .withStatusInterval((int) tailerProperties.getReplicationStatusInterval().toMillis(), TimeUnit.MILLISECONDS)
                                 .start()) {

                onStreamStarted();

                // Track timing for two independent heartbeats:
                //  - Connected-heartbeat log every HEARTBEAT_INTERVAL_NANOS so operators can see
                //    whether the tailer is receiving messages or sitting in null-poll zombie-stream.
                //  - Idle LSN push every IDLE_LSN_PUSH_INTERVAL_NANOS so Postgres can advance
                //    confirmed_flush_lsn even when pgoutput has nothing to emit (publication
                //    quiet), preventing WAL retention from growing on idle slots.
                long lastHeartbeatNs = System.nanoTime();
                long lastIdleLsnPushNs = System.nanoTime();

                while (!Thread.currentThread().isInterrupted() && !stopping.get()) {
                    ByteBuffer msg = stream.readPending();
                    long nowNs = System.nanoTime();
                    if (msg == null) {
                        handleNullPoll();
                        if (nowNs - lastHeartbeatNs >= HEARTBEAT_INTERVAL_NANOS) {
                            logConnectedHeartbeat();
                            lastHeartbeatNs = nowNs;
                        }
                        if (nowNs - lastIdleLsnPushNs >= IDLE_LSN_PUSH_INTERVAL_NANOS) {
                            forceIdleLsnPush(stream);
                            lastIdleLsnPushNs = nowNs;
                        }
                        continue;
                    }
                    // We got data — a message arrival counts as liveness evidence. Reset both
                    // timers so we don't spam heartbeat logs on a healthy stream.
                    lastHeartbeatNs = nowNs;
                    lastIdleLsnPushNs = nowNs;
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
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            boolean logicalOk = PostgresqlUtil.isLogicalDecodingEnabled(uow.handle());
            if (!logicalOk) {
                log.warn("Logical decoding not enabled (wal_level/max_replication_slots/max_wal_senders). CDC disabled.");
                pluginAvailable = false;
                availability.failed(slotName, "logical decoding not enabled");
                return;
            }

            // Run the plugin's prepare() BEFORE checking usability — publication auto-manage
            // creates the publication here, so unusableReason()'s "publication exists?" check
            // on the next line sees the result. Without this ordering, a freshly-provisioned
            // Postgres (no pre-created publication) would fail startup with
            // "publication 'X' does not exist" even though auto-manage is enabled.
            logicalDecodingPlugin.prepare(uow.handle(), eventStreamTableNamesSupplier);

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

    /**
     * Externally-triggered, destructive re-creation of the replication slot. Invoked by the
     * {@link CdcEffectivenessMonitor} auto-healing path after the monitor has fired N
     * consecutive times without recovery — the working hypothesis at that point is that the
     * slot is in a bad state the tailer can't recover from by reconnecting alone.
     * <p>
     * Runs a full {@code pg_terminate_backend} + drop + create cycle on a control-plane
     * connection. The tailer's own streaming connection dies (by design) when the backend is
     * terminated; the outer reconnect loop then fires, re-handshakes with the freshly-created
     * slot starting at current WAL head, and resumes normal operation. Subscribers stay
     * connected throughout — their adaptive live source falls back to polling when
     * availability flips FAILED and cuts back to CDC once the stream recovers.
     * <p>
     * <b>Lossy for the slot:</b> any unacknowledged WAL changes on the discarded slot are
     * lost. This is acceptable for the calling pattern — if the slot was stuck, those
     * unacknowledged changes were never going to reach subscribers anyway. Events themselves
     * remain durable in the event store tables and subscribers will catch them via polling.
     * Any logic-level error handling (poison rows, gap registration, etc.) is bypassed for
     * rows on the discarded slot.
     */
    public void requestSlotRecreation() {
        log.warn("[{}] CDC auto-recreate requested — dropping replication slot (terminating any " +
                         "attached backend) and re-creating fresh at current WAL head. Subscribers " +
                         "stay served via polling fallback; unacked WAL changes on the discarded slot " +
                         "are lost (events themselves remain durable in the event store).",
                 slotName);
        try {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                boolean dropped = PgReplicationSlots.forceRecreateSlot(
                        uow.handle().getConnection(), slotName, logicalDecodingPlugin.pluginName());
                log.info("[{}] CDC auto-recreate complete (previousSlotExisted={})", slotName, dropped);
            });
        } catch (Exception e) {
            log.error("[{}] CDC auto-recreate failed: {}", slotName, e.getMessage(), e);
        }
    }

    /**
     * Delegate to {@link LogicalDecodingPlugin#prepare(Handle, Supplier)} on a control-plane
     * connection. Plugins use this hook to bootstrap server-side state they need (pgoutput,
     * for instance, optionally creates and maintains its publication here). Default plugin
     * behaviour is a no-op; exceptions inside the plugin should be caught and logged by the
     * plugin — we don't double-wrap here.
     */
    private void preparePlugin() {
        unitOfWorkFactory.usingUnitOfWork(uow -> logicalDecodingPlugin.prepare(uow.handle(), eventStreamTableNamesSupplier));
    }

    private void ensureReplicationSlot() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            if (recreateSlotOnStart && firstStreamAttempt.compareAndSet(true, false)) {
                // Opt-in destructive path — force-drop-and-recreate the slot on first connection
                // after start(). Terminates any attached backend (e.g. stray JVM from a prior
                // test run) and drops any unacknowledged WAL changes. Subsequent reconnects
                // reuse the freshly-created slot via the normal ensureSlot path below.
                log.warn("[{}] recreate-on-start=true — dropping any existing replication slot " +
                                 "(with backend termination if active) and re-creating it fresh at current WAL head. " +
                                 "Unacked changes on the previous slot are DISCARDED. " +
                                 "Flip essentials.eventstore.cdc.slot.recreate-on-start=false in non-dev environments.",
                         slotName);
                boolean dropped = PgReplicationSlots.forceRecreateSlot(
                        uow.handle().getConnection(), slotName, logicalDecodingPlugin.pluginName());
                log.info("[{}] recreate-on-start complete (previousSlotExisted={})", slotName, dropped);
                return;
            }
            PgReplicationSlots.ensureSlot(uow.handle().getConnection(), slotName, pgSlotMode, logicalDecodingPlugin.pluginName());
        });
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
        // Emit diagnostic logging: slot LSN freshness, publication configuration, and
        // event-stream-table coverage. Best-effort — any failure here only results in a
        // debug log; never interferes with the actual stream handshake.
        logSlotAndPublicationState();
    }

    /**
     * One-shot diagnostic logging at stream-start: slot freshness (LSN + lag), publication
     * configuration (FOR ALL TABLES or explicit member list), and event-stream-table coverage
     * (WARN if any registered aggregate tables aren't in an explicit-list publication). Runs
     * inside a unit of work on the control-plane JDBC pool, not the replication connection.
     * Any exception is swallowed at DEBUG — this is purely informational.
     */
    private void logSlotAndPublicationState() {
        try {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                var handle = uow.handle();

                // (3) Slot freshness — loud warning when a pre-existing slot has a huge backlog,
                // because the tailer will spend minutes replaying historical WAL before reaching
                // current activity, which looks identical to "pgoutput is stuck" for observers.
                PostgresqlUtil.getSlotLagInfo(handle, slotName).ifPresent(info -> {
                    long lagMb = info.lagBytes() / (1024L * 1024L);
                    if (lagMb >= 100) {
                        log.warn("[{}] CDC slot inherits a large WAL backlog: confirmed_flush_lsn={}, currentWalLsn={}, lag={} MB. " +
                                         "The tailer will replay historical WAL before reaching recent events; subscribers " +
                                         "may see 0 CDC-delivered events until catchup completes. Consider setting " +
                                         "essentials.eventstore.cdc.slot.recreate-on-start=true for dev/test environments.",
                                 slotName, info.confirmedFlushLsn(), info.currentWalLsn(), lagMb);
                    } else {
                        log.info("[{}] CDC slot state: confirmed_flush_lsn={}, currentWalLsn={}, lag={} MB",
                                 slotName, info.confirmedFlushLsn(), info.currentWalLsn(), lagMb);
                    }
                });

                // (1) + (2) are pgoutput-specific. Skip for other plugins (wal2json streams all
                // tables and doesn't have the publication-membership failure mode).
                if (!PgOutputLogicalDecodingPlugin.PLUGIN_NAME.equals(logicalDecodingPlugin.pluginName())) return;

                String publicationName = extractPublicationNameFromPlugin();
                if (publicationName == null) return;

                // (1) Publication contents — one-line summary so operators can immediately spot
                // a misconfigured publication. FOR ALL TABLES is a common-case fast path; an
                // explicit-list publication gets its members enumerated.
                var pubInfoOpt = PostgresqlUtil.getPublicationInfo(handle, publicationName);
                if (pubInfoOpt.isEmpty()) {
                    log.warn("[{}] pgoutput publication '{}' not found — CDC will fail to receive row changes. " +
                                     "Create it via 'CREATE PUBLICATION {} FOR ALL TABLES;' or enable " +
                                     "essentials.eventstore.cdc.pg-output.publication.auto-manage=true (opt-in).",
                             slotName, publicationName, publicationName);
                    return;
                }
                var pubInfo = pubInfoOpt.get();
                if (pubInfo.forAllTables()) {
                    log.info("[{}] pgoutput publication '{}' is FOR ALL TABLES — all tables' row changes will stream",
                             slotName, publicationName);
                } else {
                    log.info("[{}] pgoutput publication '{}' has explicit member list ({} tables): {}",
                             slotName, publicationName, pubInfo.tableMembers().size(), pubInfo.tableMembers());
                }

                // (2) Event-stream-table coverage — cross-check registered aggregate tables against
                // publication membership. Only meaningful for explicit-list publications; FOR ALL
                // TABLES implicitly covers any known aggregate table.
                if (!pubInfo.forAllTables()) {
                    var registeredTables = eventStreamTableNamesSupplier.get();
                    if (registeredTables.isEmpty()) {
                        log.debug("[{}] No event-stream tables yet registered; skipping publication membership check", slotName);
                    } else {
                        var missing = new java.util.TreeSet<String>();
                        for (String table : registeredTables) {
                            if (table == null || table.isBlank()) continue;
                            // Normalise lookup — publication_tables returns schema.table. If the
                            // registered name is unqualified, compare against the table portion.
                            boolean covered = pubInfo.tableMembers().stream()
                                                     .anyMatch(member -> memberMatchesRegistered(member, table));
                            if (!covered) missing.add(table);
                        }
                        if (!missing.isEmpty()) {
                            log.warn("[{}] pgoutput publication '{}' is MISSING {} event-stream table(s): {}. " +
                                             "pgoutput will NOT emit row changes for these tables and subscribers " +
                                             "will see 0 CDC-delivered events. Remediation: run 'ALTER PUBLICATION {} " +
                                             "ADD TABLE {};' or enable " +
                                             "essentials.eventstore.cdc.pg-output.publication.auto-manage=true.",
                                     slotName, publicationName, missing.size(), missing, publicationName,
                                     String.join(", ", missing));
                        } else {
                            log.info("[{}] pgoutput publication '{}' covers all {} registered event-stream tables",
                                     slotName, publicationName, registeredTables.size());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            log.debug("[{}] Slot/publication diagnostic logging failed — will continue: {}", slotName, t.toString());
        }
    }

    /**
     * Looks up the publication name the configured {@code pgoutput} plugin will use. Returns
     * null when the plugin doesn't advertise one (e.g. wal2json) or when the slot options
     * don't contain a {@code publication_names} entry.
     */
    private String extractPublicationNameFromPlugin() {
        var opts = logicalDecodingPlugin.slotOptions();
        if (opts == null) return null;
        Object val = opts.get("publication_names");
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isBlank() ? null : s;
    }

    /**
     * Loose match between a publication member (always fully-qualified {@code schema.table})
     * and a registered event-stream table name which may be qualified or not. Exists so a
     * registered entry of {@code orders_events} and a publication member of
     * {@code public.orders_events} are treated as the same table for coverage purposes.
     */
    private static boolean memberMatchesRegistered(String member, String registered) {
        if (member.equalsIgnoreCase(registered)) return true;
        int dot = member.indexOf('.');
        if (dot > 0 && member.substring(dot + 1).equalsIgnoreCase(registered)) return true;
        return false;
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

        if (logicalDecodingPlugin.preFiltersRawPayloads()
                && !walMessageFilter.shouldPersist(payload.bytes())) {
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
        if (logicalDecodingPlugin.preFiltersRawPayloads()) {
            // Text-format plugins (e.g. wal2json) — cheap to preview as UTF-8.
            // Binary plugins (e.g. pgoutput) skip the preview since the bytes aren't human-readable.
            lastMessagePreview.set(payload.preview(300));
        }

        if (log.isTraceEnabled()) {
            log.trace("[{}] WAL message #{} lsn='{}' bytes='{}' payload='{}'",
                      slotName, m, lastReceiveLsn.get(), payload.bytes().length,
                      logicalDecodingPlugin.preFiltersRawPayloads() ? payload.preview(800) : "(binary)");
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
        var events = logicalDecodingPlugin.decode(payload.bytes());
        if (!events.isEmpty()) {
            directOnEvents.accept(events);
        }
        return true;
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

    /**
     * Emitted from inside the streamOnce loop every {@link #HEARTBEAT_INTERVAL_NANOS} when no
     * message has arrived. Gives operators a clear signal distinguishing "tailer is connected
     * and idle" from "tailer is disconnected / stuck in reconnect loop" without needing to
     * enable TRACE logging.
     */
    private void logConnectedHeartbeat() {
        long lastMsgEpoch = lastMessageEpochMs.get();
        long idleMs       = lastMsgEpoch == 0 ? -1 : (System.currentTimeMillis() - lastMsgEpoch);
        log.info("[{}] CDC heartbeat: connected; messagesReceived={}, inboxWrites={}, " +
                         "nullPolls={}, idleMs={}, lastReceiveLsn='{}', lastAckedLsn='{}'",
                 slotName,
                 messagesReceived.get(),
                 inboxWrites.get(),
                 nullPolls.get(),
                 idleMs,
                 lastReceiveLsn.get(),
                 lastAckedLsn.get());
    }

    /**
     * When the stream has been idle (no messages arriving), still advance Postgres's view of
     * the slot's flushed LSN so {@code confirmed_flush_lsn} doesn't get stuck at the slot's
     * start position — which holds WAL indefinitely and has been observed to correlate with
     * sender throttling / disconnection. Safe to call even when the LSN hasn't changed:
     * in that case we skip the setter calls and just re-send the keepalive status.
     */
    private void forceIdleLsnPush(PGReplicationStream stream) {
        try {
            var currentLsn = stream.getLastReceiveLSN();
            if (currentLsn == null) return;
            String asString = currentLsn.asString();
            String prev     = lastAckedLsn.get();
            if (asString.equals(prev)) {
                // No new data since our last ack — just re-send status to keep server
                // aware we're still here. Skipping the LSN setters avoids a no-op write.
                stream.forceUpdateStatus();
                return;
            }
            stream.setAppliedLSN(currentLsn);
            stream.setFlushedLSN(currentLsn);
            stream.forceUpdateStatus();
            lastAckedLsn.set(asString);
            log.debug("[{}] Idle LSN push: advanced flushed LSN to '{}' (previous='{}')",
                      slotName, asString, prev);
        } catch (Exception e) {
            // Swallow — this is a best-effort liveness push. A failure here typically means
            // the connection is dying, which the main readPending loop will surface via
            // exception on the next iteration.
            log.warn("[{}] Idle LSN push failed: {}", slotName, e.getMessage());
        }
    }

    /**
     * Point-in-time snapshot of the slot's server-side state, queried via a fresh unit of work.
     * Used by {@link CdcEffectivenessMonitor} to include live slot info (active, LSN, lag) in
     * its failure log so the root cause of a stuck CDC run can be diagnosed without running
     * {@code pg_replication_slots} manually. Returns {@link Optional#empty()} on any failure so
     * the caller can log a plain message and move on.
     */
    public Optional<SlotState> getSlotStateSnapshot() {
        try {
            return unitOfWorkFactory.withUnitOfWork(uow -> {
                try (var stmt = uow.handle().getConnection().prepareStatement(
                        "SELECT active, " +
                                "       confirmed_flush_lsn::text AS flush_lsn, " +
                                "       pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) AS lag_bytes " +
                                "FROM pg_replication_slots WHERE slot_name = ?")) {
                    stmt.setString(1, slotName);
                    try (var rs = stmt.executeQuery()) {
                        if (!rs.next()) return Optional.<SlotState>empty();
                        return Optional.of(new SlotState(
                                slotName,
                                rs.getBoolean("active"),
                                rs.getString("flush_lsn"),
                                rs.getLong("lag_bytes")
                        ));
                    }
                }
            });
        } catch (Exception e) {
            log.debug("[{}] Could not query pg_replication_slots: {}", slotName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Immutable snapshot of a replication slot's server-side state. Returned by
     * {@link #getSlotStateSnapshot()}. {@code lagBytes} is the number of bytes of WAL the server
     * is retaining past {@code confirmedFlushLsn} — a steadily growing value indicates the
     * tailer isn't ack'ing LSN progress back to Postgres.
     */
    public record SlotState(String slotName,
                            boolean active,
                            String confirmedFlushLsn,
                            long lagBytes) {
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
        long delay  = Math.max(0, baseMs + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1));

        // Short backoff — single sleep, no need for progress heartbeats.
        long heartbeatChunkMs = TimeUnit.NANOSECONDS.toMillis(HEARTBEAT_INTERVAL_NANOS);
        if (delay <= heartbeatChunkMs) {
            Thread.sleep(delay);
            return;
        }

        // Long backoff — sleep in chunks and emit a progress log after each chunk so operators
        // can distinguish "tailer is in the middle of a long reconnect wait" from "tailer is
        // wedged for unrelated reasons". The last (partial) chunk is followed by the next
        // connect-attempt log in runPollLoop, so we skip the trailing log.
        long startNs = System.nanoTime();
        long remainingMs = delay;
        while (remainingMs > 0) {
            long sleepMs = Math.min(heartbeatChunkMs, remainingMs);
            Thread.sleep(sleepMs);
            remainingMs -= sleepMs;
            if (remainingMs > 0) {
                log.info("[{}] CDC reconnect backoff in progress: elapsedMs={}, remainingMs={}, plannedDelayMs={}",
                         slotName,
                         TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs),
                         remainingMs,
                         delay);
            }
        }
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
