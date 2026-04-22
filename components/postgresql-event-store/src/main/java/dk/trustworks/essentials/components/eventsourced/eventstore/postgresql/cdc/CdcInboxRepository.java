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

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.ttl.TTLJob;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import org.slf4j.*;

import java.util.*;

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
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.meterRegistry = meterRegistry.orElse(null);
        this.cdcSql = new CdcSql(CdcSql.DEFAULT_CDC_TABLE_NAME);
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
            uow.handle().execute(cdcSql.buildCreateCdcTableSql());
            log.info("Ensured Table '{}' exists", cdcSql.getCdcTableName());
            uow.handle().execute(cdcSql.getCreateCdcIndexSql());
            log.info("Ensured Cdc indexes exists");
        });
    }

    /**
     * idempotent insert; returns true if inserted, false if already existed
     */
    public boolean insertIfAbsent(String slotName, String lsn, String payloadJson) {
        return insertIfAbsent(slotName, lsn, payloadJson == null ? null : payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * idempotent insert; returns true if inserted, false if already existed
     */
    public boolean insertIfAbsent(String slotName, String lsn, byte[] payloadBytes) {
        long startNs = System.nanoTime();
        boolean inserted = unitOfWorkFactory.withUnitOfWork(uow -> {
            int updated = uow.handle().createUpdate("""
                                                    insert into eventstore_cdc_inbox(slot_name, lsn, payload_bytes, status)
                                                    values (:slot, :lsn, :payloadBytes, 'RECEIVED')
                                                    on conflict (slot_name, lsn) do nothing
                                                    """)
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
        unitOfWorkFactory.usingUnitOfWork(uowh -> uowh.handle().createUpdate("""
                                                                             update eventstore_cdc_inbox
                                                                             set status='POISON', error=:err
                                                                             where slot_name=:slot and lsn=:lsn
                                                                             """)
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
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                           update eventstore_cdc_inbox
                                                                           set status='DISPATCHED'
                                                                           where inbox_id=:id
                                                                           """)
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
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                                 delete from eventstore_cdc_inbox
                                                                                 where slot_name=:slot
                                                                                 """)
                                                          .bind("slot", slotName)
                                                          .execute());
    }

    /**
     * Deletes an already dispatched row from the CDC inbox.
     */
    public void deleteDispatched(long inboxId) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                           delete from eventstore_cdc_inbox
                                                                           where inbox_id=:id
                                                                           """)
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
        long startNs = System.nanoTime();
        var rows = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("""
                                                                                    SELECT inbox_id,
                                                                                           slot_name,
                                                                                           lsn,
                                                                                           received_at,
                                                                                           payload_bytes,
                                                                                           status,
                                                                                           error
                                                                                                FROM eventstore_cdc_inbox
                                                                                                WHERE slot_name = :slot
                                                                                                  AND status = 'RECEIVED'
                                                                                                ORDER BY inbox_id
                                                                                                limit :limit
                                                                                                FOR UPDATE skip locked
                                                                                    """)
                                                              .bind("slot", slotName)
                                                              .bind("limit", batchSize)
                                                              .map((rs, ctx) -> new InboxRow(
                                                                      rs.getLong("inbox_id"),
                                                                      rs.getString("lsn"),
                                                                      rs.getBytes("payload_bytes")
                                                              ))
                                                              .list());
        if (meterRegistry != null) {
            fetchNextBatchLatencyTimer.record(System.nanoTime() - startNs, java.util.concurrent.TimeUnit.NANOSECONDS);
            fetchNextBatchSizeSummary.record(rows.size());
        }
        return rows;
    }

    /**
     * Counts the number of entries in the CDC inbox table with the specified slot name and status.
     * <p>
     * For testing purposes.
     *
     * @param slotName the name of the slot used to filter records
     * @param status   the status value used to filter records
     * @return the count of entries matching the specified slot name and status
     */
    public long countByStatus(String slotName, String status) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("""
                                                                                select count(*) from eventstore_cdc_inbox
                                                                                where slot_name=:slot and status=:status
                                                                                """)
                                                          .bind("slot", slotName)
                                                          .bind("status", status)
                                                          .mapTo(long.class)
                                                          .one());
    }

    /**
     * Retrieves the status of a specific event in the CDC inbox table based on the provided slot name and Log Sequence Number (LSN).
     * <p>
     * For testing purposes.
     *
     * @param slotName the name of the slot associated with the event
     * @param lsn      the Log Sequence Number (LSN) identifying the event
     * @return an {@code Optional<String>} containing the status if the event exists, or an empty {@code Optional} if not found
     */
    public Optional<String> statusForLsn(String slotName, String lsn) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("""
                                                                                select status from eventstore_cdc_inbox
                                                                                where slot_name=:slot and lsn=:lsn
                                                                                """)
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
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                           insert into eventstore_cdc_inbox(slot_name, lsn, payload_bytes, status)
                                                                           values (:slot, :lsn, :payloadBytes, :status)
                                                                           on conflict (slot_name, lsn) do nothing
                                                                           """)
                                                    .bind("slot", slotName)
                                                    .bind("lsn", lsn)
                                                    .bind("payloadBytes", payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                                    .bind("status", status)
                                                    .execute());
    }

    public record InboxRow(long inboxId, String lsn, byte[] payloadJsonBytes) {
    }

    public enum InboxStatus {
        RECEIVED, POISON, DISPATCHED
    }
}
