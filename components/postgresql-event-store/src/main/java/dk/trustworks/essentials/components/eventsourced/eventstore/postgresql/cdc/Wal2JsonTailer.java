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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.handler.*;
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
import java.sql.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

public class Wal2JsonTailer implements Lifecycle {

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
    private       Counter                 connectAttemptsCounter;
    private       Counter                 connectSuccessCounter;
    private       Counter                 connectFailuresCounter;
    private       Counter                 messagesReceivedCounter;
    private       Counter                 inboxWritesCounter;
    private       Counter                 inboxDuplicatesCounter;
    private       Counter                 inboxWriteFailuresCounter;
    private       Counter                 handlerFailuresCounter;


    public Wal2JsonTailer(
            DataSource replicationDataSource,
            Jdbi jdbi,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            String slotName,
            CdcInboxRepository inboxRepository,
            Wal2JsonTailerProperties wal2JsonTailerProperties,
            PgSlotMode pgSlotMode,
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
        requireNonNull(wal2JsonTailerProperties.getPollInterval(), "pollInterval cannot be null");
        requireNonNull(wal2JsonTailerProperties.getPollBackoffInterval(), "pollBackoffInterval cannot be null");
        requireNonNull(wal2JsonTailerProperties.getMaxPollBackoffInterval(), "maxPollBackInterval cannot be null");
        requireNonNull(wal2JsonTailerProperties.getReplicationStatusInterval(), "replicationStatusInterval cannot be null");
        requireTrue(wal2JsonTailerProperties.getJitterRatio() > 0.0 && wal2JsonTailerProperties.getJitterRatio() < 0.5, "jitterRatio must be in [0.0..0.5]");
        requireTrue(wal2JsonTailerProperties.getBackOffFactor() > 1, "backOffFactor must be > 1");
        this.meterRegistry = meterRegistry.orElse(null);
        this.errorHandler = errorHandler.orElseGet(DefaultWal2JsonTailerErrorHandler::new);
        this.walMessageFilter = new Wal2JsonEventTableInsertFilter();
        initMetrics();
        unitOfWorkFactory.usingUnitOfWork(inboxRepository::createTableAndIndexes);
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
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        stopping.set(false);
        log.info("[{}] ⚙️ Starting Essentials Wal2JsonTailer", slotName);


        unitOfWorkFactory.usingUnitOfWork(uow -> {
            boolean logicalOk = PostgresqlUtil.isLogicalDecodingEnabled(uow.handle());
            if (!logicalOk) {
                log.warn("Logical decoding not enabled (wal_level/max_replication_slots/max_wal_senders). CDC disabled.");
                wal2jsonAvailable = false;
                return;
            }

            boolean usable = PostgresqlUtil.isOutputPluginUsable(uow.handle(), "wal2json");
            wal2jsonAvailable = usable;

            if (!usable) {
                log.warn("wal2json output plugin not usable (missing plugin or insufficient privileges). CDC disabled.");
            }
        });

        if (!wal2jsonAvailable) {
            started.set(false);
            log.info("wal2json CDC is not available - cannot start Wal2JsonTailer");
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
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        log.info("[{}] ⏹  Stopping Essentials Wal2JsonTailer", slotName);
        try {
            if (loopFuture != null) {
                loopFuture.cancel(true);
            }
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
            started.set(false);
        }

        log.info("[{}] 🛑 Stopped Essentials Wal2JsonTailer", slotName);
    }

    private void runPollLoop() {
        long backoffMs = wal2JsonTailerProperties.getPollBackoffInterval().toMillis();

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
    }

    private void streamOnce() throws SQLException, InterruptedException {
        jdbi.useHandle(handle -> PgReplicationSlots.ensureSlot(handle.getConnection(), slotName, pgSlotMode));

        log.info("[{}] Opening replication connection...", slotName);

        try (Connection replConn = replicationDataSource.getConnection()) {
            replConn.setAutoCommit(true);
            PGConnection pgConn = replConn.unwrap(PGConnection.class);

            if (log.isDebugEnabled()) {
                log.debug("[{}] Replication connection established url={} backendPid={}",
                          slotName,
                          replConn.getMetaData().getURL(),
                          pgConn.getBackendPID());
            }

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

                    String json = StandardCharsets.UTF_8.decode(msg).toString();

                    var    lsn    = stream.getLastReceiveLSN();
                    String lsnStr = (lsn != null ? lsn.asString() : null);

                    if (!walMessageFilter.shouldPersist(json)) {
                        if (log.isTraceEnabled()) {
                            log.trace("[{}] WAL message filtered out (slot='{}', lsn='{}', bytes='{}', payload='{}')", slotName, slotName, lsnStr,
                                      json.length(), json.length() > 800 ? json.substring(0, 800) + "..." : json);
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

                    lastMessagePreview.set(json.length() > 300 ? json.substring(0, 300) + "..." : json);

                    if (log.isTraceEnabled()) {
                        log.trace("[{}] WAL message #{} lsn='{}' bytes='{}' payload='{}'",
                                  slotName, m, lastReceiveLsn.get(), json.length(),
                                  json.length() > 800 ? json.substring(0, 800) + "..." : json);
                    } else if (m == 1) {
                        log.info("[{}] First WAL message received lsn='{}' bytes='{}' preview='{}'",
                                 slotName, lastReceiveLsn.get(), json.length(), lastMessagePreview.get());
                    }

                    boolean inserted;
                    try {
                        if (lsnStr == null) {
                            throw new IllegalStateException("PGReplicationStream returned null LSN for received message");
                        }

                        inserted = inboxRepository.insertIfAbsent(slotName, lsnStr, json);

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
