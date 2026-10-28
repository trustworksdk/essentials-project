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

package dk.trustworks.essentials.examples.trading.reconciliation.views.missing_statements;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.examples.trading.integration.*;
import dk.trustworks.essentials.examples.trading.reconciliation.config.ReconciliationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The statements expected and not received by their grace (design phase 5 §3, Q-3), worked out when asked. A business
 * day expects a clearing statement when the brokerage traded on it ({@code known_executions}, by {@code tradeDate}) and
 * a bank statement when a payment of ours was completed or returned on it ({@code known_payments}, by value date). The
 * day's cutoff is the exchange's ({@code business_day_closed}); the gateway follows the exchange's calendar, so its
 * statement day ends then too. The deadline is compared with the exchange's clock, never this application's (D10).
 * <p>
 * Answers empty without the exchange: no calendar, no clock, nothing can be late.
 */
@Service
public class MissingStatementsQuery {
    private static final String BANK_CURRENCY = "USD";

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final ObjectProvider<BusinessCalendar>                              calendar;
    private final ReconciliationProperties                                      properties;
    private final SimulatorProperties                                           simulators;

    public MissingStatementsQuery(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                  ObjectProvider<BusinessCalendar> calendar,
                                  ReconciliationProperties properties,
                                  SimulatorProperties simulators) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.calendar = requireNonNull(calendar, "No calendar provided");
        this.properties = requireNonNull(properties, "No properties provided");
        this.simulators = requireNonNull(simulators, "No simulators provided");
    }

    public List<MissingStatement> missing() {
        var businessCalendar = calendar.getIfAvailable();
        if (businessCalendar == null) {
            return List.of();
        }
        var now = businessCalendar.businessNow();
        if (now.isEmpty()) {
            return List.of();
        }
        var grace          = properties.getStatementGrace();
        var cutoffBefore   = Timestamp.from(now.get().minus(grace));
        var participantId  = simulators.getExchange().getParticipantId();
        var missing        = new ArrayList<MissingStatement>();
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("""
                                                                              SELECT d.business_date, d.cutoff_at FROM business_day_closed d
                                                                              WHERE d.cutoff_at < :cutoffBefore
                                                                                AND EXISTS (SELECT 1 FROM projection_reconciliation_execution e WHERE e.trade_date = d.business_date)
                                                                                AND NOT EXISTS (SELECT 1 FROM clearing_statement s WHERE s.business_date = d.business_date)
                                                                              ORDER BY d.business_date
                                                                              """)
                                                     .bind("cutoffBefore", cutoffBefore)
                                                     .reduceResultSet(missing, (acc, rs, ctx) -> {
                                                         var businessDate = rs.getDate(1).toLocalDate();
                                                         acc.add(new MissingStatement(MissingStatement.Source.CLEARING, "CS:" + businessDate + ":" + participantId, businessDate,
                                                                                          rs.getTimestamp(2).toInstant().plus(grace)));
                                                         return acc;
                                                     }));
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().createQuery("""
                                                                              SELECT d.business_date, d.cutoff_at FROM business_day_closed d
                                                                              WHERE d.cutoff_at < :cutoffBefore
                                                                                AND EXISTS (SELECT 1 FROM projection_reconciliation_payment p WHERE p.completed_on = d.business_date OR p.returned_on = d.business_date)
                                                                                AND NOT EXISTS (SELECT 1 FROM bank_statement s WHERE s.business_date = d.business_date)
                                                                              ORDER BY d.business_date
                                                                              """)
                                                     .bind("cutoffBefore", cutoffBefore)
                                                     .reduceResultSet(missing, (acc, rs, ctx) -> {
                                                         var businessDate = rs.getDate(1).toLocalDate();
                                                         acc.add(new MissingStatement(MissingStatement.Source.BANK, "BS:" + businessDate + ":" + BANK_CURRENCY, businessDate,
                                                                                          rs.getTimestamp(2).toInstant().plus(grace)));
                                                         return acc;
                                                     }));
        return missing;
    }
}
