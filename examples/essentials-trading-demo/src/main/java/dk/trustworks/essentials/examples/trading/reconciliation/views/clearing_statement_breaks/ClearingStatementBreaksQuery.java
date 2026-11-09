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

package dk.trustworks.essentials.examples.trading.reconciliation.views.clearing_statement_breaks;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The breaks of a clearing statement, worked out when asked (design phase 5 §2): its trade lines against
 * {@code known_executions}, matched by execution id - a statement's trades belong to its business date, as an
 * execution belongs to its {@code tradeDate} (SR-29), never to a timestamp range of ours.
 * <p>
 * Obligations are checked against the statement's own lines, not against our executions: the clearinghouse nets its
 * obligations from the lines it reports, so a wrong line already shows as a trade break, and comparing obligations with
 * our executions would report it twice. What is left to find there is an obligation that does not add up.
 */
@Service
public class ClearingStatementBreaksQuery {
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;

    public ClearingStatementBreaksQuery(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    public record StatementSummary(String statementId, LocalDate businessDate, String participantId, int trades, int breaks) {
    }

    public List<StatementSummary> statements() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("""
                                                                                    SELECT s.statement_id, s.business_date, s.participant_id, count(t.execution_id)
                                                                                    FROM clearing_statement s LEFT JOIN clearing_trade t ON t.statement_id = s.statement_id
                                                                                    GROUP BY s.statement_id, s.business_date, s.participant_id
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
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("SELECT statement_id, business_date FROM clearing_statement WHERE business_date >= :from ORDER BY business_date")
                                                     .bind("from", java.sql.Date.valueOf(from))
                                                     .reduceResultSet(statements, (acc, rs, ctx) -> {
                                                         acc.put(rs.getString(1), rs.getDate(2).toLocalDate());
                                                         return acc;
                                                     }));
        return statements;
    }

    public Optional<LocalDate> businessDateOf(String statementId) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("SELECT business_date FROM clearing_statement WHERE statement_id = :statementId")
                                                                   .bind("statementId", statementId)
                                                                   .map((rs, ctx) -> rs.getDate(1).toLocalDate())
                                                                   .findFirst());
    }

    public List<ClearingBreak> breaksOf(String statementId) {
        requireNonNull(statementId, "No statementId provided");
        var breaks = new ArrayList<ClearingBreak>();
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("""
                                                                              SELECT t.execution_id, t.side, t.quantity, t.amount, e.side, e.quantity, e.amount
                                                                              FROM clearing_trade t LEFT JOIN projection_reconciliation_execution e ON e.execution_id = t.execution_id
                                                                              WHERE t.statement_id = :statementId
                                                                              ORDER BY t.execution_id
                                                                              """)
                                                     .bind("statementId", statementId)
                                                     .reduceResultSet(breaks, (acc, rs, ctx) -> {
                                                         var reference = rs.getString(1);
                                                         var statementQuantity = rs.getLong(3);
                                                         var statementAmount = rs.getBigDecimal(4);
                                                         var ourSide = rs.getString(5);
                                                         if (ourSide == null) {
                                                             acc.add(new ClearingBreak(statementId, ClearingBreak.Kind.UNKNOWN_TRADE, reference, statementQuantity, null, statementAmount, null));
                                                             return acc;
                                                         }
                                                         var ourQuantity = rs.getLong(6);
                                                         var ourAmount = rs.getBigDecimal(7);
                                                         if (!ourSide.equals(rs.getString(2))) {
                                                             acc.add(new ClearingBreak(statementId, ClearingBreak.Kind.SIDE_MISMATCH, reference, statementQuantity, ourQuantity, statementAmount, ourAmount));
                                                         } else if (statementQuantity != ourQuantity) {
                                                             acc.add(new ClearingBreak(statementId, ClearingBreak.Kind.QUANTITY_MISMATCH, reference, statementQuantity, ourQuantity, statementAmount, ourAmount));
                                                         } else if (statementAmount.compareTo(ourAmount) != 0) {
                                                             acc.add(new ClearingBreak(statementId, ClearingBreak.Kind.PRICE_MISMATCH, reference, statementQuantity, ourQuantity, statementAmount, ourAmount));
                                                         }
                                                         return acc;
                                                     }));
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("""
                                                                              SELECT e.execution_id, e.quantity, e.amount
                                                                              FROM projection_reconciliation_execution e JOIN clearing_statement s ON s.statement_id = :statementId AND e.trade_date = s.business_date
                                                                              WHERE NOT EXISTS (SELECT 1 FROM clearing_trade t WHERE t.statement_id = s.statement_id AND t.execution_id = e.execution_id)
                                                                              ORDER BY e.execution_id
                                                                              """)
                                                     .bind("statementId", statementId)
                                                     .reduceResultSet(breaks, (acc, rs, ctx) -> {
                                                         acc.add(new ClearingBreak(statementId, ClearingBreak.Kind.MISSING_TRADE, rs.getString(1), null, rs.getLong(2), null, rs.getBigDecimal(3)));
                                                         return acc;
                                                     }));
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("""
                                                                              SELECT o.obligation_id, o.securities_net, o.cash_net, o.trade_count,
                                                                                     COALESCE(sum(CASE WHEN t.side = 'BUY' THEN t.quantity ELSE -t.quantity END), 0),
                                                                                     COALESCE(sum(CASE WHEN t.side = 'BUY' THEN -t.amount ELSE t.amount END), 0),
                                                                                     count(t.execution_id)
                                                                              FROM clearing_obligation o LEFT JOIN clearing_trade t ON t.statement_id = o.statement_id AND t.obligation_id = o.obligation_id
                                                                              WHERE o.statement_id = :statementId
                                                                              GROUP BY o.obligation_id, o.securities_net, o.cash_net, o.trade_count
                                                                              ORDER BY o.obligation_id
                                                                              """)
                                                     .bind("statementId", statementId)
                                                     .reduceResultSet(breaks, (acc, rs, ctx) -> {
                                                         if (rs.getLong(2) != rs.getLong(5) || rs.getBigDecimal(3).compareTo(rs.getBigDecimal(6)) != 0 || rs.getInt(4) != rs.getInt(7)) {
                                                             acc.add(new ClearingBreak(statementId, ClearingBreak.Kind.OBLIGATION_MISMATCH, rs.getString(1), rs.getLong(2), rs.getLong(5),
                                                                                          rs.getBigDecimal(3), rs.getBigDecimal(6)));
                                                         }
                                                         return acc;
                                                     }));
        return breaks;
    }
}
