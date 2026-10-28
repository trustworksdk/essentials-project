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

package dk.trustworks.essentials.examples.trading.funding.views.payments;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountId;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.types.Money;
import org.jdbi.v3.core.statement.StatementContext;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.ZoneOffset;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Eventually consistent, like every view.
 */
@Service
public class PaymentsQuery {
    private static final String SELECT = "SELECT reference, kind, account_id, amount, currency, status, payment_id, value_date, reason, iban, requested_at, updated_at FROM projection_funding_payment ";

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;

    public PaymentsQuery(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    public List<PaymentView> paymentsOf(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(SELECT + "WHERE account_id = :accountId ORDER BY requested_at DESC, reference")
                                                                   .bind("accountId", accountId.toString())
                                                                   .map(PaymentsQuery::map)
                                                                   .list());
    }

    public Optional<PaymentView> payment(AccountId accountId, String reference) {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(reference, "No reference provided");
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(SELECT + "WHERE account_id = :accountId AND reference = :reference")
                                                                   .bind("accountId", accountId.toString())
                                                                   .bind("reference", reference)
                                                                   .map(PaymentsQuery::map)
                                                                   .findFirst());
    }

    private static PaymentView map(ResultSet rs, StatementContext ctx) throws SQLException {
        var valueDate = rs.getDate("value_date");
        return new PaymentView(rs.getString("reference"),
                               PaymentKind.valueOf(rs.getString("kind")),
                               AccountId.of(rs.getString("account_id")),
                               Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
                               PaymentStatus.valueOf(rs.getString("status")),
                               rs.getString("payment_id"),
                               valueDate == null ? null : valueDate.toLocalDate(),
                               rs.getString("reason"),
                               rs.getString("iban"),
                               rs.getTimestamp("requested_at").toInstant().atOffset(ZoneOffset.UTC),
                               rs.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC));
    }
}
