/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.trading.reconciliation.views.cases;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.reconciliation.aggregates.ReconciliationCases;
import dk.trustworks.essentials.examples.trading.reconciliation.events.*;
import dk.trustworks.essentials.examples.trading.reconciliation.types.*;
import dk.trustworks.essentials.examples.trading.config.TradingDemoViewEventProcessor;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Every reconciliation case and its history, for the operator and for {@code automations/open_cases}, which clears the
 * open ones whose break has gone.
 * <p>
 * Every handler write goes through {@code getRequiredUnitOfWork().handle()…}, the {@code UnitOfWork} a
 * {@link ViewEventProcessor} handles each event in, so the row and the processor's progress commit together. The
 * constructor's DDL runs where none is active, so it goes through {@code getUnitOfWorkFactory().usingUnitOfWork(uow -> …)},
 * which opens one; the read methods go through {@code getUnitOfWorkFactory().withUnitOfWork(uow -> …)}, which joins the
 * caller's unit of work or opens one.
 */
@Service
public class CasesProjection extends TradingDemoViewEventProcessor {
    private static final String SELECT = """
                                         SELECT case_id, statement_id, business_date, kind, reference, detail, state, assignee, resolution, note, adjustment_id, opened_at, updated_at
                                         FROM projection_reconciliation_case
                                         """;

    public CasesProjection(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
                                                                                   CREATE TABLE IF NOT EXISTS projection_reconciliation_case (
                                                                                       case_id VARCHAR PRIMARY KEY,
                                                                                       statement_id VARCHAR NOT NULL,
                                                                                       business_date DATE NOT NULL,
                                                                                       kind VARCHAR NOT NULL,
                                                                                       reference VARCHAR NOT NULL,
                                                                                       detail VARCHAR NOT NULL,
                                                                                       state VARCHAR NOT NULL,
                                                                                       assignee VARCHAR NULL,
                                                                                       resolution VARCHAR NULL,
                                                                                       note VARCHAR NULL,
                                                                                       adjustment_id VARCHAR NULL,
                                                                                       opened_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                                                       updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                                                   )
                                                                                   """));
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("CREATE INDEX IF NOT EXISTS projection_reconciliation_case_state ON projection_reconciliation_case (state)"));
        getUnitOfWorkFactory().usingUnitOfWork(uow -> uow.handle().execute("""
                                                                                   CREATE TABLE IF NOT EXISTS projection_reconciliation_case_history (
                                                                                       case_id VARCHAR NOT NULL,
                                                                                       seq BIGSERIAL,
                                                                                       event VARCHAR NOT NULL,
                                                                                       by_whom VARCHAR NULL,
                                                                                       detail VARCHAR NULL,
                                                                                       at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                                                       PRIMARY KEY (case_id, seq)
                                                                                   )
                                                                                   """));
    }

    @Override
    public String getProcessorName() {
        return "ReconciliationCasesProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(ReconciliationCases.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(CaseOpened e) {
        var inserted = getRequiredUnitOfWork().handle().createUpdate("""
                                                                         INSERT INTO projection_reconciliation_case (case_id, statement_id, business_date, kind, reference, detail, state)
                                                                         VALUES (:caseId, :statementId, :businessDate, :kind, :reference, :detail, :state) ON CONFLICT (case_id) DO NOTHING
                                                                         """)
                                                       .bind("caseId", e.caseId().toString())
                                                       .bind("statementId", e.statementId())
                                                       .bind("businessDate", Date.valueOf(e.businessDate()))
                                                       .bind("kind", e.kind())
                                                       .bind("reference", e.reference())
                                                       .bind("detail", e.detail())
                                                       .bind("state", CaseState.OPEN.name())
                                                       .execute();
        if (inserted > 0) {
            history(e.caseId(), "OPENED", null, e.detail());
        }
    }

    @MessageHandler
    void handle(CaseAssigned e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_reconciliation_case SET state = :state, assignee = :assignee, updated_at = CURRENT_TIMESTAMP WHERE case_id = :caseId")
                                .bind("state", CaseState.ASSIGNED.name())
                                .bind("assignee", e.assignee())
                                .bind("caseId", e.caseId().toString())
                                .execute();
        history(e.caseId(), "ASSIGNED", e.assignedBy(), e.assignee());
    }

    @MessageHandler
    void handle(CaseResolved e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_reconciliation_case SET state = :state, resolution = :resolution, note = :note, adjustment_id = :adjustmentId, updated_at = CURRENT_TIMESTAMP WHERE case_id = :caseId")
                                .bind("state", CaseState.RESOLVED.name())
                                .bind("resolution", e.resolution().name())
                                .bind("note", e.note())
                                .bind("adjustmentId", e.adjustmentId() != null ? e.adjustmentId().toString() : null)
                                .bind("caseId", e.caseId().toString())
                                .execute();
        history(e.caseId(), "RESOLVED", e.resolvedBy(), e.resolution() + (e.note().isBlank() ? "" : ": " + e.note()));
    }

    @MessageHandler
    void handle(CaseCleared e) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("UPDATE projection_reconciliation_case SET state = :state, updated_at = CURRENT_TIMESTAMP WHERE case_id = :caseId")
                                .bind("state", CaseState.CLEARED.name())
                                .bind("caseId", e.caseId().toString())
                                .execute();
        history(e.caseId(), "CLEARED", null, null);
    }

    /**
     * @param state {@code null} for every state
     */
    public List<CaseView> cases(CaseState state) {
        return getUnitOfWorkFactory().withUnitOfWork(uow -> state == null
                                                            ? uow.handle().createQuery(SELECT + "ORDER BY opened_at DESC LIMIT 500")
                                                                 .map((rs, ctx) -> map(rs, List.<CaseView.HistoryEntry>of()))
                                                                 .list()
                                                            : uow.handle().createQuery(SELECT + "WHERE state = :state ORDER BY opened_at DESC")
                                                                 .bind("state", state.name())
                                                                 .map((rs, ctx) -> map(rs, List.<CaseView.HistoryEntry>of()))
                                                                 .list());
    }

    /**
     * The cases an operator still has to work - open or assigned.
     */
    public List<CaseView> openCases() {
        return getUnitOfWorkFactory().withUnitOfWork(uow -> uow.handle().createQuery(SELECT + "WHERE state IN (:open, :assigned) ORDER BY opened_at")
                                                               .bind("open", CaseState.OPEN.name())
                                                               .bind("assigned", CaseState.ASSIGNED.name())
                                                               .map((rs, ctx) -> map(rs, List.<CaseView.HistoryEntry>of()))
                                                               .list());
    }

    public Optional<CaseView> caseWithHistory(String caseId) {
        requireNonNull(caseId, "No caseId provided");
        return getUnitOfWorkFactory().withUnitOfWork(uow -> {
            var history = uow.handle().createQuery("SELECT event, by_whom, detail, at FROM projection_reconciliation_case_history WHERE case_id = :caseId ORDER BY seq")
                             .bind("caseId", caseId)
                             .map((rs, ctx) -> new CaseView.HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()))
                             .list();
            return uow.handle().createQuery(SELECT + "WHERE case_id = :caseId")
                      .bind("caseId", caseId)
                      .map((rs, ctx) -> map(rs, history))
                      .findFirst();
        });
    }

    private void history(CaseId caseId, String event, String by, String detail) {
        getRequiredUnitOfWork().handle()
                                .createUpdate("INSERT INTO projection_reconciliation_case_history (case_id, event, by_whom, detail) VALUES (:caseId, :event, :by, :detail)")
                                .bind("caseId", caseId.toString())
                                .bind("event", event)
                                .bind("by", by)
                                .bind("detail", detail)
                                .execute();
    }

    private static CaseView map(ResultSet rs, List<CaseView.HistoryEntry> history) throws SQLException {
        var resolution = rs.getString(9);
        return new CaseView(rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5), rs.getString(6),
                            CaseState.valueOf(rs.getString(7)), rs.getString(8), resolution != null ? CaseResolution.valueOf(resolution) : null,
                            rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant(), rs.getTimestamp(13).toInstant(), history);
    }
}
