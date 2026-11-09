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

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
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
 * Both directions apply that rule: a phantom copied from an entry the bank also omitted is an unknown entry, and does
 * not stand in for the omitted one, which is still missing.
 */
@Service
public class BankStatementBreaksQuery {
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;

    public BankStatementBreaksQuery(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    public record StatementSummary(String statementId, LocalDate businessDate, String currency, int entries, int breaks) {
    }

    public List<StatementSummary> statements() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("""
                                                                                    SELECT s.statement_id, s.business_date, s.currency, count(e.entry_id)
                                                                                    FROM bank_statement s LEFT JOIN bank_statement_entry e ON e.statement_id = s.statement_id
                                                                                    GROUP BY s.statement_id, s.business_date, s.currency
                                                                                    ORDER BY s.business_date DESC, s.statement_id
                                                                                    """)
                                                                   .map((rs, ctx) -> new StatementSummary(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getInt(4), breaksOf(rs.getString(1)).size()))
                                                                   .list());
    }

    /**
     * The statements of business days on or after {@code from}, by id.
     */
    public Map<String, LocalDate> statementsFrom(LocalDate from) {
        requireNonNull(from, "No from provided");
        var statements = new LinkedHashMap<String, LocalDate>();
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("SELECT statement_id, business_date FROM bank_statement WHERE business_date >= :from ORDER BY business_date")
                                                     .bind("from", java.sql.Date.valueOf(from))
                                                     .reduceResultSet(statements, (acc, rs, ctx) -> {
                                                         acc.put(rs.getString(1), rs.getDate(2).toLocalDate());
                                                         return acc;
                                                     }));
        return statements;
    }

    public Optional<LocalDate> businessDateOf(String statementId) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("SELECT business_date FROM bank_statement WHERE statement_id = :statementId")
                                                                   .bind("statementId", statementId)
                                                                   .map((rs, ctx) -> rs.getDate(1).toLocalDate())
                                                                   .findFirst());
    }

    public List<StatementBreak> breaksOf(String statementId) {
        requireNonNull(statementId, "No statementId provided");
        var breaks = new ArrayList<StatementBreak>();
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("""
                                                                              SELECT e.end_to_end_id, e.kind, e.amount, p.kind, p.amount, e.payment_id, p.payment_id
                                                                              FROM bank_statement_entry e LEFT JOIN projection_reconciliation_payment p ON p.reference = e.end_to_end_id
                                                                              WHERE e.statement_id = :statementId
                                                                              ORDER BY e.entry_id
                                                                              """)
                                                     .bind("statementId", statementId)
                                                     .reduceResultSet(breaks, (acc, rs, ctx) -> {
                                                         var reference = rs.getString(1);
                                                         var entryKind = rs.getString(2);
                                                         var entryAmount = rs.getBigDecimal(3);
                                                         var ourKind = rs.getString(4);
                                                         var ourAmount = rs.getBigDecimal(5);
                                                         var entryPaymentId = rs.getString(6);
                                                         var ourPaymentId = rs.getString(7);
                                                         // Our reference on a payment the gateway never took for us: a copied or invented entry
                                                         if (ourKind == null || (ourPaymentId != null && !ourPaymentId.equals(entryPaymentId))) {
                                                             acc.add(new StatementBreak(statementId, StatementBreak.Kind.UNKNOWN_ENTRY, reference, entryKind, entryAmount, null));
                                                         } else if (!entryKind.startsWith(ourKind)) {
                                                             acc.add(new StatementBreak(statementId, StatementBreak.Kind.KIND_MISMATCH, reference, entryKind, entryAmount, ourAmount));
                                                         } else if (entryAmount.compareTo(ourAmount) != 0) {
                                                             acc.add(new StatementBreak(statementId, StatementBreak.Kind.AMOUNT_MISMATCH, reference, entryKind, entryAmount, ourAmount));
                                                         }
                                                         return acc;
                                                     }));
        var statement = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("SELECT business_date, currency FROM bank_statement WHERE statement_id = :statementId")
                                                                         .bind("statementId", statementId)
                                                                         .map((rs, ctx) -> new Object[]{rs.getDate(1), rs.getString(2)})
                                                                         .list());
        if (statement.isEmpty()) {
            return breaks;
        }
        var businessDate = (Date) statement.getFirst()[0];
        var currency     = (String) statement.getFirst()[1];
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("""
                                                                              SELECT p.reference, p.kind, p.amount, CASE WHEN p.completed_on = :businessDate THEN p.kind ELSE p.kind || '_RETURN' END
                                                                              FROM projection_reconciliation_payment p
                                                                              WHERE p.currency = :currency AND (p.completed_on = :businessDate OR p.returned_on = :businessDate)
                                                                                AND NOT EXISTS (SELECT 1 FROM bank_statement_entry e
                                                                                                WHERE e.statement_id = :statementId AND e.end_to_end_id = p.reference
                                                                                                  AND (p.payment_id IS NULL OR e.payment_id = p.payment_id)
                                                                                                  AND e.kind = CASE WHEN p.completed_on = :businessDate THEN p.kind ELSE p.kind || '_RETURN' END)
                                                                              ORDER BY p.reference
                                                                              """)
                                                     .bind("businessDate", businessDate)
                                                     .bind("currency", currency)
                                                     .bind("statementId", statementId)
                                                     .reduceResultSet(breaks, (acc, rs, ctx) -> {
                                                         acc.add(new StatementBreak(statementId, StatementBreak.Kind.MISSING_ENTRY, rs.getString(1), rs.getString(4), null, rs.getBigDecimal(3)));
                                                         return acc;
                                                     }));
        return breaks;
    }
}
