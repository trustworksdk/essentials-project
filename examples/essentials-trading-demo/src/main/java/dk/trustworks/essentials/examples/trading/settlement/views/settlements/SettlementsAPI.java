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

package dk.trustworks.essentials.examples.trading.settlement.views.settlements;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.examples.trading.settlement.types.SettlementState;
import org.jdbi.v3.core.statement.StatementContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Operator only (under {@code /api/admin}): every settlement, and the exceptions - settlements the exchange abandoned,
 * whose bookings were reversed while the order still shows filled (Q-1).
 */
@RestController
@RequestMapping("/api/admin/settlements")
public class SettlementsAPI {
    private static final String SELECT = "SELECT execution_id, account_id, instrument_id, side, amount, currency, intended_settlement_date, state, outcome, reason FROM projection_settlement ";

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;

    public SettlementsAPI(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    @GetMapping
    public List<SettlementView> settlements(@RequestParam(name = "accountId", required = false) String accountId) {
        return unitOfWorkFactory.withUnitOfWork(uow -> accountId == null
                                                       ? uow.handle().createQuery(SELECT + "ORDER BY updated_at DESC LIMIT 500")
                                                            .map(this::map)
                                                            .list()
                                                       : uow.handle().createQuery(SELECT + "WHERE account_id = :accountId ORDER BY updated_at DESC")
                                                            .bind("accountId", accountId)
                                                            .map(this::map)
                                                            .list());
    }

    @GetMapping("/exceptions")
    public List<SettlementView> exceptions() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(SELECT + "WHERE state = :state ORDER BY updated_at DESC")
                                                                   .bind("state", SettlementState.ABANDONED.name())
                                                                   .map(this::map)
                                                                   .list());
    }

    private SettlementView map(java.sql.ResultSet rs, StatementContext ctx) throws java.sql.SQLException {
        return new SettlementView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6),
                                  rs.getDate(7).toLocalDate(), SettlementState.valueOf(rs.getString(8)), rs.getString(9), rs.getString(10));
    }
}
