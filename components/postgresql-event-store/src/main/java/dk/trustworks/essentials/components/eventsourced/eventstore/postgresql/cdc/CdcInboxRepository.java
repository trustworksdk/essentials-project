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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.ttl.TTLJob;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.*;

import java.util.*;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

/**
 * This repository class provides operations to manage the CDC (Change Data Capture) inbox table,
 * which is responsible for storing event messages.
 */
@TTLJob(name = "eventstore_cdc_inbox_ttl",
        tableName = "eventstore_cdc_inbox",
        tableNameProperty = "essentials.eventstore.cdc.inbox-table-name",
        timestampColumn = "received_at",
        cronExpression = "30 0 * * *",
        ttlDurationProperty = "essentials.eventstore.cdc.inbox-ttl-duration",
        defaultTtlDays = 90
)
public class CdcInboxRepository {

    private static final Logger log = LoggerFactory.getLogger(CdcInboxRepository.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final CdcSql                                                        cdcSql;
    private final MeterRegistry                                                 meterRegistry;
    private final Timer                                                         insertLatencyTimer;
    private final Timer                                                         markPoisonLatencyTimer;
    private final Timer                                                         markDispatchedLatencyTimer;
    private final Timer                                                         fetchNextBatchLatencyTimer;
    private final DistributionSummary                                           fetchNextBatchSizeSummary;
    private final Counter                                                       insertSuccessCounter;
    private final Counter                                                       insertDuplicateCounter;
    private final Counter                                                       markPoisonCounter;
    private final Counter                                                       markDispatchedCounter;
    private final Counter                                                       deleteDispatchedCounter;

    public CdcInboxRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(unitOfWorkFactory, Optional.empty());
    }

    public CdcInboxRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                              Optional<MeterRegistry> meterRegistry) {
        this(unitOfWorkFactory, meterRegistry, CdcSql.DEFAULT_CDC_TABLE_NAME);
    }

    public CdcInboxRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                              String cdcInboxTableName) {
        this(unitOfWorkFactory, Optional.empty(), cdcInboxTableName);
    }

    public CdcInboxRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                              Optional<MeterRegistry> meterRegistry,
                              String cdcInboxTableName) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.meterRegistry = meterRegistry.orElse(null);
        // CdcSql validates the name via PostgresqlUtil.checkIsValidTableOrColumnName, so it is safe to
        // interpolate getCdcTableName() into the statements below (see sql()).
        this.cdcSql = new CdcSql(cdcInboxTableName);
        if (this.meterRegistry != null) {
            insertLatencyTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.inbox.insert.latency").register(this.meterRegistry);
            markPoisonLatencyTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.inbox.mark_poison.latency").register(this.meterRegistry);
            markDispatchedLatencyTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.inbox.mark_dispatched.latency").register(this.meterRegistry);
            fetchNextBatchLatencyTimer = io.micrometer.core.instrument.Timer.builder("essentials.cdc.inbox.fetch_next_batch.latency").register(this.meterRegistry);
            fetchNextBatchSizeSummary = DistributionSummary.builder("essentials.cdc.inbox.fetch_next_batch.size").register(this.meterRegistry);
            insertSuccessCounter = Counter.builder("essentials.cdc.inbox.insert.success").register(this.meterRegistry);
            insertDuplicateCounter = Counter.builder("essentials.cdc.inbox.insert.duplicate").register(this.meterRegistry);
            markPoisonCounter = Counter.builder("essentials.cdc.inbox.mark_poison.count").register(this.meterRegistry);
            markDispatchedCounter = Counter.builder("essentials.cdc.inbox.mark_dispatched.count").register(this.meterRegistry);
            deleteDispatchedCounter = Counter.builder("essentials.cdc.inbox.delete_dispatched.count").register(this.meterRegistry);
        } else {
            insertLatencyTimer = null;
            markPoisonLatencyTimer = null;
            markDispatchedLatencyTimer = null;
            fetchNextBatchLatencyTimer = null;
            fetchNextBatchSizeSummary = null;
            insertSuccessCounter = null;
            insertDuplicateCounter = null;
            markPoisonCounter = null;
            markDispatchedCounter = null;
            deleteDispatchedCounter = null;
        }
        createTableAndIndexes();
    }

    public void createTableAndIndexes() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            PostgresqlUtil.acquireBootstrapLock(uow.handle());
            uow.handle().execute(cdcSql.buildCreateCdcTableSql());
            log.info("Ensured Table '{}' exists", cdcSql.getCdcTableName());
            uow.handle().execute(cdcSql.getCreateCdcIndexSql());
            log.info("Ensured Cdc indexes exists");
        });
    }

    /**
     * Interpolate the configured (and validated) inbox table name into a statement template. Templates
     * use the {@code {:table}} placeholder; Jdbi named parameters ({@code :slot} etc.) are left intact
     * because {@link dk.trustworks.essentials.shared.MessageFormatter#bind} only replaces {@code {:name}}.
     */
    private String sql(String template) {
        return bind(template, arg("table", cdcSql.getCdcTableName()));
    }

    /**
     * idempotent insert; returns true if inserted, false if already existed
     */
    public boolean insertIfAbsent(String slotName, String lsn, String payloadJson) {
        return insertIfAbsent(slotName, lsn, payloadJson == null ? null : payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Idempotent insert keyed by {@code (slot_name, lsn)}; returns {@code true} if a row was inserted,
     * {@code false} if one already existed.
     * <p>
     * <b>Invariant: each persisted WAL message must carry a distinct {@code lsn} for a given slot.</b>
     * The {@code unique(slot_name, lsn)} dedup key (see {@link CdcSql#buildCreateCdcTableSql()}) exists
     * so that a WAL message re-streamed after a reconnect — before its LSN was acked — is recognised as
     * already-received and acked again rather than re-dispatched. It relies on the {@code lsn} (the
     * tailer's {@code PGReplicationStream#getLastReceiveLSN()}) being unique per persisted message: if
     * two <em>distinct</em> messages ever reported the same LSN, the second would be treated as a
     * duplicate and silently dropped. The risk area is pgoutput, whose pre-filter persists both
     * {@code 'R'} (RELATION) and {@code 'I'} (INSERT) messages (a RELATION is emitted before the first
     * INSERT for a relation so the dispatcher can cache its schema), so the invariant must hold across
     * the RELATION→INSERT boundary, not just between INSERTs. It does: every message — RELATION and
     * row-change alike — sits at its own WAL position. Only BEGIN/COMMIT framing is dropped, and those
     * are never persisted, so they cannot collide with a kept row. Verified by
     * {@code WalReplicationWithEssentialsAggregatePgOutputIT} (distinct LSNs across a multi-statement
     * transaction, including the RELATION-message boundary).
     */
    public boolean insertIfAbsent(String slotName, String lsn, byte[] payloadBytes) {
        long startNs = System.nanoTime();
        boolean inserted = unitOfWorkFactory.withUnitOfWork(uow -> {
            int updated = uow.handle().createUpdate(sql("""
                                                    insert into {:table}(slot_name, lsn, payload_bytes, status)
                                                    values (:slot, :lsn, :payloadBytes, 'RECEIVED')
                                                    on conflict (slot_name, lsn) do nothing
                                                    """))
                             .bind("slot", slotName)
                             .bind("lsn", lsn)
                             .bind("payloadBytes", payloadBytes)
                             .execute();
            return updated == 1;
        });
        if (meterRegistry != null) {
            insertLatencyTimer.record(System.nanoTime() - startNs, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (inserted) {
                insertSuccessCounter.increment();
            } else {
                insertDuplicateCounter.increment();
            }
        }
        return inserted;
    }

    /**
     * Marks an event as "POISON" in the CDC inbox table by updating its status and recording the associated error.
     *
     * @param slotName the name of the slot associated with the event
     * @param lsn      the Log Sequence Number (LSN) identifying the event
     * @param error    a description of the error that caused the event to be marked as "POISON"
     */
    public void markPoison(String slotName, String lsn, String error) {
        long startNs = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(uowh -> uowh.handle().createUpdate(sql("""
                                                                             update {:table}
                                                                             set status='POISON', error=:err
                                                                             where slot_name=:slot and lsn=:lsn
                                                                             """))
                                                      .bind("slot", slotName)
                                                      .bind("lsn", lsn)
                                                      .bind("err", error)
                                                      .execute());
        if (meterRegistry != null) {
            markPoisonLatencyTimer.record(System.nanoTime() - startNs, java.util.concurrent.TimeUnit.NANOSECONDS);
            markPoisonCounter.increment();
        }
    }

    /**
     * Marks a CDC (Change Data Capture) inbox event as dispatched by updating its status in the database.
     *
     * @param inboxId the unique identifier of the inbox event to be marked as dispatched
     */
    public void markDispatched(long inboxId) {
        long startNs = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate(sql("""
                                                                           update {:table}
                                                                           set status='DISPATCHED'
                                                                           where inbox_id=:id
                                                                           """))
                                                    .bind("id", inboxId)
                                                    .execute());
        if (meterRegistry != null) {
            markDispatchedLatencyTimer.record(System.nanoTime() - startNs, java.util.concurrent.TimeUnit.NANOSECONDS);
            markDispatchedCounter.increment();
        }
    }

    /**
     * Destructively remove every row for the given slot. Intended only for the
     * {@code slot.recreate-on-start} path — when the replication slot is dropped and
     * re-created, any inbox rows keyed to that slot reference now-lost WAL positions AND
     * carry relation metadata whose corresponding {@code RELATION} messages may have already
     * been processed by a prior JVM session (leaving the new JVM's decoder without the cached
     * pgoutput relation metadata). Leaving those rows around would cause
     * "Missing cached pgoutput relation metadata for relationId=X" failures as soon as the
     * new dispatcher gets to them.
     * <p>
     * Returns the number of rows deleted. Logs at INFO since this fires at most once per JVM
     * and operators need to see how much was discarded.
     */
    public int deleteAllForSlot(String slotName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate(sql("""
                                                                                 delete from {:table}
                                                                                 where slot_name=:slot
                                                                                 """))
                                                          .bind("slot", slotName)
                                                          .execute());
    }

    /**
     * Deletes an already dispatched row from the CDC inbox.
     */
    public void deleteDispatched(long inboxId) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate(sql("""
                                                                           delete from {:table}
                                                                           where inbox_id=:id
                                                                           """))
                                                    .bind("id", inboxId)
                                                    .execute());
        if (meterRegistry != null) {
            deleteDispatchedCounter.increment();
        }
    }

    /**
     * Fetches the next batch of events from the CDC inbox table based on the specified slot name and batch size.
     * The events are filtered by their "RECEIVED" status and are locked using "FOR UPDATE SKIP LOCKED" for concurrent processing.
     *
     * @param slotName  the name of the slot whose events are to be fetched
     * @param batchSize the maximum number of events to include in the fetched batch
     * @return a list of {@link InboxRow} objects representing the events in the requested batch
     */
    public List<InboxRow> fetchNextBatch(String slotName, int batchSize) {
        return fetchNextBatch(slotName, batchSize, 0);
    }

    /**
     * Same as {@link #fetchNextBatch(String, int)} but with a per-statement timeout in
     * seconds. {@code 0} means no timeout (defers to PG / JDBC / pool defaults — see
     * {@link CdcProperties.CdcDispatcherProperties#getQueryTimeout()}); any positive value
     * is applied to the underlying {@link java.sql.Statement} via
     * {@link org.jdbi.v3.core.statement.SqlStatement#setQueryTimeout(int)}.
     * <p>
     * Server-side cancellation surfaces as a {@code PSQLException} with SQL state
     * {@code 57014} ("query canceled"). The dispatcher's tick-error handler treats it as a
     * normal tick failure and retries on the next {@code pollInterval}, so no special
     * client-side handling is needed here.
     */
    public List<InboxRow> fetchNextBatch(String slotName, int batchSize, int queryTimeoutSeconds) {
        long startNs = System.nanoTime();
        var rows = unitOfWorkFactory.withUnitOfWork(uow -> {
            var query = uow.handle().createQuery(sql("""
                                                  SELECT inbox_id,
                                                         slot_name,
                                                         lsn,
                                                         received_at,
                                                         payload_bytes,
                                                         status,
                                                         error
                                                              FROM {:table}
                                                              WHERE slot_name = :slot
                                                                AND status = 'RECEIVED'
                                                              ORDER BY inbox_id
                                                              limit :limit
                                                              FOR UPDATE skip locked
                                                  """))
                                    .bind("slot", slotName)
                                    .bind("limit", batchSize);
            if (queryTimeoutSeconds > 0) {
                // Jdbi setQueryTimeout(seconds) delegates to PreparedStatement#setQueryTimeout,
                // which on pgjdbc translates to a server-side cancel request when the budget
                // expires. Resulting PSQLException (SQLState 57014) propagates up to the
                // dispatcher's tick-error handler.
                query.setQueryTimeout(queryTimeoutSeconds);
            }
            return query.map((rs, ctx) -> new InboxRow(
                                  rs.getLong("inbox_id"),
                                  rs.getString("lsn"),
                                  rs.getBytes("payload_bytes")
                          ))
                          .list();
        });
        if (meterRegistry != null) {
            fetchNextBatchLatencyTimer.record(System.nanoTime() - startNs, java.util.concurrent.TimeUnit.NANOSECONDS);
            fetchNextBatchSizeSummary.record(rows.size());
        }
        return rows;
    }

    /**
     * Counts the number of entries in the CDC inbox table with the specified slot name and status.
     * Used by {@code CdcInboxMetrics} to publish backlog / poison-row gauges, and by tests for
     * direct assertions.
     * <p>
     * The query is index-supported by the {@code (slot_name, status, inbox_id)} index created in
     * {@link CdcSql#getCreateCdcIndexSql()}, so the call is cheap even on large inboxes.
     *
     * @param slotName the name of the slot used to filter records
     * @param status   the status value used to filter records (e.g. {@code "RECEIVED"}, {@code "POISON"})
     * @return the count of entries matching the specified slot name and status
     */
    public long countByStatus(String slotName, String status) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(sql("""
                                                                                select count(*) from {:table}
                                                                                where slot_name=:slot and status=:status
                                                                                """))
                                                          .bind("slot", slotName)
                                                          .bind("status", status)
                                                          .mapTo(long.class)
                                                          .one());
    }

    /**
     * Registers two on-demand Micrometer gauges that expose the inbox depth for {@code slotName}:
     * <ul>
     *   <li>{@code essentials.cdc.inbox.received_backlog} — rows in {@code RECEIVED} status,
     *       i.e. WAL messages persisted by the tailer but not yet dispatched. Steady growth
     *       indicates the dispatcher is falling behind.</li>
     *   <li>{@code essentials.cdc.inbox.poison_rows} — rows in {@code POISON} status, i.e.
     *       WAL messages quarantined after decode failure. Non-zero warrants investigation.</li>
     * </ul>
     * Both gauges sample {@link #countByStatus} at scrape time. The query is served by the
     * existing {@code (slot_name, status, inbox_id)} index as an index-only scan, so the
     * per-scrape cost is negligible — there is no separate background sampler.
     * <p>
     * No-ops when the repository was constructed without a {@link MeterRegistry} or when
     * the slot name is blank. Callers should not invoke this in DIRECT delivery mode, where
     * the gauges would read 0 forever and mislead operators. Micrometer deduplicates by
     * meter identity, so a duplicate call is safe but redundant.
     */
    public void registerInboxBacklogGauges(String slotName) {
        if (meterRegistry == null) return;
        if (slotName == null || slotName.isBlank()) return;

        Gauge.builder("essentials.cdc.inbox.received_backlog",
                      this,
                      repo -> repo.countByStatus(slotName, InboxStatus.RECEIVED.name()))
             .description("Number of inbox rows in RECEIVED status — WAL messages persisted by the tailer but not yet dispatched. Steady growth = dispatcher falling behind.")
             .baseUnit("rows")
             .tag("slot", slotName)
             .register(meterRegistry);

        Gauge.builder("essentials.cdc.inbox.poison_rows",
                      this,
                      repo -> repo.countByStatus(slotName, InboxStatus.POISON.name()))
             .description("Number of inbox rows in POISON status — WAL messages quarantined after decode failure. Non-zero warrants investigation.")
             .baseUnit("rows")
             .tag("slot", slotName)
             .register(meterRegistry);

        log.info("Registered CDC inbox backlog gauges for slot '{}'", slotName);
    }

    /**
     * Retrieves the status associated with a given logical slot name (LSN).
     * <p>
     * This method executes a database query to fetch the status for a particular slot name and LSN.
     *
     * @param slotName the name of the logical replication slot
     * @param lsn the log sequence number (LSN) to look up
     * @return an Optional containing the status if found, or an empty Optional if no status matches the slot name and LSN
     */
    public Optional<String> statusForLsn(String slotName, String lsn) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(sql("""
                                                                                select status from {:table}
                                                                                where slot_name=:slot and lsn=:lsn
                                                                                """))
                                                          .bind("slot", slotName)
                                                          .bind("lsn", lsn)
                                                          .mapTo(String.class)
                                                          .findOne());
    }

    /**
     * Inserts a raw event into the CDC inbox table. If a record with the same slot name
     * and Log Sequence Number (LSN) already exists, the insertion is ignored.
     * <p>
     * For testing purposes.
     *
     * @param slotName    the name of the slot associated with the event
     * @param lsn         the Log Sequence Number (LSN) uniquely identifying the event
     * @param payloadJson the JSON payload of the event to be stored
     * @param status      the status of the event to be recorded in the table
     */
    public void insertRaw(String slotName, String lsn, String payloadJson, String status) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate(sql("""
                                                                           insert into {:table}(slot_name, lsn, payload_bytes, status)
                                                                           values (:slot, :lsn, :payloadBytes, :status)
                                                                           on conflict (slot_name, lsn) do nothing
                                                                           """))
                                                    .bind("slot", slotName)
                                                    .bind("lsn", lsn)
                                                    .bind("payloadBytes", payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                                    .bind("status", status)
                                                    .execute());
    }

    /**
     * Represents a row in an inbox structure, encapsulating the unique identifier of the inbox,
     * a log sequence number (LSN), and the JSON payload in byte array format.
     *
     * @param inboxId           The unique identifier for the inbox.
     * @param lsn               The log sequence number associated with this inbox row.
     * @param payloadJsonBytes  The JSON payload stored as a byte array.
     */
    public record InboxRow(long inboxId, String lsn, byte[] payloadJsonBytes) {
    }

    public enum InboxStatus {
        RECEIVED, POISON, DISPATCHED
    }
}
