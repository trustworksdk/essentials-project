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

package dk.trustworks.essentials.examples.trading.reconciliation.views.bank_statement_breaks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The breaks of a bank statement, worked out when asked: the statement's entries against {@code known_payments}.
 * Because both sides are read now, a statement received before the brokerage recorded its own completion shows no
 * false break once the completion is in. Entry kinds and our payments:
 * {@code DEPOSIT} and {@code DEPOSIT_RETURN} belong to a deposit, {@code WITHDRAWAL} and {@code WITHDRAWAL_RETURN} to a
 * withdrawal; a return entry is expected on the return's value date, the payment's own on its completion's.
 * <p>
 * An entry is ours only if both its {@code endToEndId} and its {@code paymentId} are: a bank's phantom entry can copy a
 * real one's reference and amount under a payment that does not exist (the gateway's seeded {@code PHANTOM_ENTRY}).
 */
@Service
public class BankStatementBreaksQuery {
    private final JdbcTemplate jdbcTemplate;

    public BankStatementBreaksQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    public record StatementSummary(String statementId, LocalDate businessDate, String currency, int entries, int breaks) {
    }

    public List<StatementSummary> statements() {
        return jdbcTemplate.query("""
                                          SELECT s.statement_id, s.business_date, s.currency, count(e.entry_id)
                                          FROM bank_statement s LEFT JOIN bank_statement_entry e ON e.statement_id = s.statement_id
                                          GROUP BY s.statement_id, s.business_date, s.currency
                                          ORDER BY s.business_date DESC, s.statement_id
                                          """,
                                  (rs, row) -> new StatementSummary(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getInt(4), breaksOf(rs.getString(1)).size()));
    }

    /**
     * The statements of business days on or after {@code from}, by id.
     */
    public Map<String, LocalDate> statementsFrom(LocalDate from) {
        requireNonNull(from, "No from provided");
        var statements = new LinkedHashMap<String, LocalDate>();
        jdbcTemplate.query("SELECT statement_id, business_date FROM bank_statement WHERE business_date >= ? ORDER BY business_date",
                           rs -> {
                               statements.put(rs.getString(1), rs.getDate(2).toLocalDate());
                           },
                           java.sql.Date.valueOf(from));
        return statements;
    }

    public Optional<LocalDate> businessDateOf(String statementId) {
        return jdbcTemplate.query("SELECT business_date FROM bank_statement WHERE statement_id = ?", (rs, row) -> rs.getDate(1).toLocalDate(), statementId).stream().findFirst();
    }

    public List<StatementBreak> breaksOf(String statementId) {
        requireNonNull(statementId, "No statementId provided");
        var breaks = new ArrayList<StatementBreak>();
        jdbcTemplate.query("""
                                   SELECT e.end_to_end_id, e.kind, e.amount, p.kind, p.amount, e.payment_id, p.payment_id
                                   FROM bank_statement_entry e LEFT JOIN projection_reconciliation_payment p ON p.reference = e.end_to_end_id
                                   WHERE e.statement_id = ?
                                   ORDER BY e.entry_id
                                   """,
                           rs -> {
                               var reference = rs.getString(1);
                               var entryKind = rs.getString(2);
                               var entryAmount = rs.getBigDecimal(3);
                               var ourKind = rs.getString(4);
                               var ourAmount = rs.getBigDecimal(5);
                               var entryPaymentId = rs.getString(6);
                               var ourPaymentId = rs.getString(7);
                               // Our reference on a payment the gateway never took for us: a copied or invented entry
                               if (ourKind == null || (ourPaymentId != null && !ourPaymentId.equals(entryPaymentId))) {
                                   breaks.add(new StatementBreak(statementId, StatementBreak.Kind.UNKNOWN_ENTRY, reference, entryKind, entryAmount, null));
                               } else if (!entryKind.startsWith(ourKind)) {
                                   breaks.add(new StatementBreak(statementId, StatementBreak.Kind.KIND_MISMATCH, reference, entryKind, entryAmount, ourAmount));
                               } else if (entryAmount.compareTo(ourAmount) != 0) {
                                   breaks.add(new StatementBreak(statementId, StatementBreak.Kind.AMOUNT_MISMATCH, reference, entryKind, entryAmount, ourAmount));
                               }
                           },
                           statementId);
        var statement = jdbcTemplate.query("SELECT business_date, currency FROM bank_statement WHERE statement_id = ?",
                                           (rs, row) -> new Object[]{rs.getDate(1), rs.getString(2)}, statementId);
        if (statement.isEmpty()) {
            return breaks;
        }
        var businessDate = (Date) statement.getFirst()[0];
        var currency     = (String) statement.getFirst()[1];
        jdbcTemplate.query("""
                                   SELECT p.reference, p.kind, p.amount, CASE WHEN p.completed_on = ? THEN p.kind ELSE p.kind || '_RETURN' END
                                   FROM projection_reconciliation_payment p
                                   WHERE p.currency = ? AND (p.completed_on = ? OR p.returned_on = ?)
                                     AND NOT EXISTS (SELECT 1 FROM bank_statement_entry e
                                                     WHERE e.statement_id = ? AND e.end_to_end_id = p.reference
                                                       AND e.kind = CASE WHEN p.completed_on = ? THEN p.kind ELSE p.kind || '_RETURN' END)
                                   ORDER BY p.reference
                                   """,
                           rs -> {
                               breaks.add(new StatementBreak(statementId, StatementBreak.Kind.MISSING_ENTRY, rs.getString(1), rs.getString(4), null, rs.getBigDecimal(3)));
                           },
                           businessDate, currency, businessDate, businessDate, statementId, businessDate);
        return breaks;
    }
}
