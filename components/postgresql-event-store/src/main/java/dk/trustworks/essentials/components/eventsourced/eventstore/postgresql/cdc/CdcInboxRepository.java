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
import org.slf4j.*;

import java.util.*;

/**
 * This repository class provides operations to manage the CDC (Change Data Capture) inbox table,
 * which is responsible for storing event messages.
 */
@TTLJob(name = "eventstore_cdc_inbox_ttl",
        tableNameProperty = "essentials.cdc.inbox-table-name",
        timestampColumn = "received_at",
        ttlDurationProperty = "essentials.cdc.inbox-ttl-duration"
)
public class CdcInboxRepository {

    private static final Logger log = LoggerFactory.getLogger(CdcInboxRepository.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final CdcSql                                                        cdcSql;

    public CdcInboxRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.cdcSql = new CdcSql(CdcSql.DEFAULT_CDC_TABLE_NAME);
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
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            int updated = uow.handle().createUpdate("""
                                                    insert into eventstore_cdc_inbox(slot_name, lsn, payload_json, status)
                                                    values (:slot, :lsn, :payload, 'RECEIVED')
                                                    on conflict (slot_name, lsn) do nothing
                                                    """)
                             .bind("slot", slotName)
                             .bind("lsn", lsn)
                             .bind("payload", payloadJson)
                             .execute();
            return updated == 1;
        });
    }

    /**
     * Marks an event as "POISON" in the CDC inbox table by updating its status and recording the associated error.
     *
     * @param slotName the name of the slot associated with the event
     * @param lsn the Log Sequence Number (LSN) identifying the event
     * @param error a description of the error that caused the event to be marked as "POISON"
     */
    public void markPoison(String slotName, String lsn, String error) {
        unitOfWorkFactory.usingUnitOfWork(uowh -> uowh.handle().createUpdate("""
                                                                             update eventstore_cdc_inbox
                                                                             set status='POISON', error=:err
                                                                             where slot_name=:slot and lsn=:lsn
                                                                             """)
                                                      .bind("slot", slotName)
                                                      .bind("lsn", lsn)
                                                      .bind("err", error)
                                                      .execute());
    }

    /**
     * Marks a CDC (Change Data Capture) inbox event as dispatched by updating its status in the database.
     *
     * @param inboxId the unique identifier of the inbox event to be marked as dispatched
     */
    public void markDispatched(long inboxId) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                           update eventstore_cdc_inbox
                                                                           set status='DISPATCHED'
                                                                           where inbox_id=:id
                                                                           """)
                                                    .bind("id", inboxId)
                                                    .execute());
    }

    /**
     * Fetches the next batch of events from the CDC inbox table based on the specified slot name and batch size.
     * The events are filtered by their "RECEIVED" status and are locked using "FOR UPDATE SKIP LOCKED" for concurrent processing.
     *
     * @param slotName the name of the slot whose events are to be fetched
     * @param batchSize the maximum number of events to include in the fetched batch
     * @return a list of {@link InboxRow} objects representing the events in the requested batch
     */
    public List<InboxRow> fetchNextBatch(String slotName, int batchSize) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("""
                                                                                SELECT inbox_id, slot_name, lsn, received_at, payload_json, status, error
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
                                                                  rs.getString("payload_json")
                                                          ))
                                                          .list());
    }

    /**
     * Counts the number of entries in the CDC inbox table with the specified slot name and status.
     * <p>
     * For testing purposes.
     * @param slotName the name of the slot used to filter records
     * @param status the status value used to filter records
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
     * @param slotName the name of the slot associated with the event
     * @param lsn the Log Sequence Number (LSN) identifying the event
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
     * @param slotName the name of the slot associated with the event
     * @param lsn the Log Sequence Number (LSN) uniquely identifying the event
     * @param payloadJson the JSON payload of the event to be stored
     * @param status the status of the event to be recorded in the table
     */
    public void insertRaw(String slotName, String lsn, String payloadJson, String status) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createUpdate("""
                                                                           insert into eventstore_cdc_inbox(slot_name, lsn, payload_json, status)
                                                                           values (:slot, :lsn, :payload, :status)
                                                                           on conflict (slot_name, lsn) do nothing
                                                                           """)
                                                    .bind("slot", slotName)
                                                    .bind("lsn", lsn)
                                                    .bind("payload", payloadJson)
                                                    .bind("status", status)
                                                    .execute());
    }

    public record InboxRow(long inboxId, String lsn, String payloadJson) {
    }

    public enum InboxStatus {
        RECEIVED, POISON, DISPATCHED
    }
}

