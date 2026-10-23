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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Every reconciliation case and its history, for the operator and for {@code automations/open_cases}, which clears the
 * open ones whose break has gone.
 */
@Service
public class CasesProjection extends TradingDemoViewEventProcessor {
    private static final String SELECT = """
                                         SELECT case_id, statement_id, business_date, kind, reference, detail, state, assignee, resolution, note, adjustment_id, opened_at, updated_at
                                         FROM projection_reconciliation_case
                                         """;

    private final JdbcTemplate jdbcTemplate;

    public CasesProjection(ViewEventProcessorDependencies dependencies, JdbcTemplate jdbcTemplate) {
        super(dependencies);
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.jdbcTemplate.execute("""
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
                                          """);
        this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS projection_reconciliation_case_state ON projection_reconciliation_case (state)");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS projection_reconciliation_case_history (
                                              case_id VARCHAR NOT NULL,
                                              seq BIGSERIAL,
                                              event VARCHAR NOT NULL,
                                              by_whom VARCHAR NULL,
                                              detail VARCHAR NULL,
                                              at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                              PRIMARY KEY (case_id, seq)
                                          )
                                          """);
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
        var inserted = jdbcTemplate.update("""
                                                   INSERT INTO projection_reconciliation_case (case_id, statement_id, business_date, kind, reference, detail, state)
                                                   VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (case_id) DO NOTHING
                                                   """,
                                           e.caseId().toString(), e.statementId(), Date.valueOf(e.businessDate()), e.kind(), e.reference(), e.detail(), CaseState.OPEN.name());
        if (inserted > 0) {
            history(e.caseId(), "OPENED", null, e.detail());
        }
    }

    @MessageHandler
    void handle(CaseAssigned e) {
        jdbcTemplate.update("UPDATE projection_reconciliation_case SET state = ?, assignee = ?, updated_at = CURRENT_TIMESTAMP WHERE case_id = ?",
                            CaseState.ASSIGNED.name(), e.assignee(), e.caseId().toString());
        history(e.caseId(), "ASSIGNED", e.assignedBy(), e.assignee());
    }

    @MessageHandler
    void handle(CaseResolved e) {
        jdbcTemplate.update("UPDATE projection_reconciliation_case SET state = ?, resolution = ?, note = ?, adjustment_id = ?, updated_at = CURRENT_TIMESTAMP WHERE case_id = ?",
                            CaseState.RESOLVED.name(), e.resolution().name(), e.note(), e.adjustmentId() != null ? e.adjustmentId().toString() : null, e.caseId().toString());
        history(e.caseId(), "RESOLVED", e.resolvedBy(), e.resolution() + (e.note().isBlank() ? "" : ": " + e.note()));
    }

    @MessageHandler
    void handle(CaseCleared e) {
        jdbcTemplate.update("UPDATE projection_reconciliation_case SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE case_id = ?", CaseState.CLEARED.name(), e.caseId().toString());
        history(e.caseId(), "CLEARED", null, null);
    }

    /**
     * @param state {@code null} for every state
     */
    public List<CaseView> cases(CaseState state) {
        return state == null
               ? jdbcTemplate.query(SELECT + "ORDER BY opened_at DESC LIMIT 500", (rs, row) -> map(rs, List.of()))
               : jdbcTemplate.query(SELECT + "WHERE state = ? ORDER BY opened_at DESC", (rs, row) -> map(rs, List.of()), state.name());
    }

    /**
     * The cases an operator still has to work - open or assigned.
     */
    public List<CaseView> openCases() {
        return jdbcTemplate.query(SELECT + "WHERE state IN (?, ?) ORDER BY opened_at", (rs, row) -> map(rs, List.of()), CaseState.OPEN.name(), CaseState.ASSIGNED.name());
    }

    public Optional<CaseView> caseWithHistory(String caseId) {
        requireNonNull(caseId, "No caseId provided");
        var history = jdbcTemplate.query("SELECT event, by_whom, detail, at FROM projection_reconciliation_case_history WHERE case_id = ? ORDER BY seq",
                                         (rs, row) -> new CaseView.HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
                                         caseId);
        return jdbcTemplate.query(SELECT + "WHERE case_id = ?", (rs, row) -> map(rs, history), caseId).stream().findFirst();
    }

    private void history(CaseId caseId, String event, String by, String detail) {
        jdbcTemplate.update("INSERT INTO projection_reconciliation_case_history (case_id, event, by_whom, detail) VALUES (?, ?, ?, ?)", caseId.toString(), event, by, detail);
    }

    private static CaseView map(ResultSet rs, List<CaseView.HistoryEntry> history) throws SQLException {
        var resolution = rs.getString(9);
        return new CaseView(rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5), rs.getString(6),
                            CaseState.valueOf(rs.getString(7)), rs.getString(8), resolution != null ? CaseResolution.valueOf(resolution) : null,
                            rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant(), rs.getTimestamp(13).toInstant(), history);
    }
}
