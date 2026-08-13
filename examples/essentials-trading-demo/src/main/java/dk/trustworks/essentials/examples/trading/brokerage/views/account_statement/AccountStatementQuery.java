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

package dk.trustworks.essentials.examples.trading.brokerage.views.account_statement;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiClosingBooksGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.GenerationState;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The two queries of the {@code brokerage.account_statement} slice, over the one read model it owns.
 * <p>
 * Both interrogate {@code projection_trading_account_statement}, which is what §R2 scopes a view slice by. The second
 * one additionally reads the framework's closing-books lifecycle metadata — that is not a second read model, it is
 * event-store bookkeeping nothing in this application projects, so it does not fork the slice.
 */
@Service
public class AccountStatementQuery {
    /**
     * The principal the demo's admin surface acts as. The demo has no authentication; a real deployment would pass the
     * authenticated caller.
     */
    private static final String DEMO_ADMIN_PRINCIPAL = "demo-admin";

    private static final RowMapper<AccountStatement> ROW_MAPPER =
            (rs, rowNum) -> new AccountStatement(TradingAccountId.of(rs.getString("logical_account_id")),
                                                 OwnerId.of(rs.getString("owner_id")),
                                                 PeriodId.of(rs.getString("period_id")),
                                                 rs.getInt("current_generation"),
                                                 rs.getInt("generation_count"),
                                                 Amount.of(rs.getBigDecimal("cash_balance")),
                                                 Amount.of(rs.getBigDecimal("reserved_funds")),
                                                 Amount.of(rs.getBigDecimal("realized_pnl")),
                                                 rs.getBoolean("books_closed"));

    private final JdbcTemplate          jdbcTemplate;
    private final AggregateLifecycleApi aggregateLifecycleApi;

    public AccountStatementQuery(JdbcTemplate jdbcTemplate,
                                 AggregateLifecycleApi aggregateLifecycleApi) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.aggregateLifecycleApi = requireNonNull(aggregateLifecycleApi, "No aggregateLifecycleApi provided");
    }

    @Transactional(readOnly = true)
    public List<AccountStatement> accountStatements() {
        return jdbcTemplate.query("""
                                          SELECT logical_account_id,
                                                 owner_id,
                                                 period_id,
                                                 current_generation,
                                                 generation_count,
                                                 cash_balance,
                                                 reserved_funds,
                                                 realized_pnl,
                                                 books_closed
                                          FROM projection_trading_account_statement
                                          ORDER BY logical_account_id
                                          """,
                                  ROW_MAPPER);
    }

    /**
     * The statement row of one account, together with the generations behind it.
     *
     * <p>The current generation is resolved <em>before</em> the row is read, so an account the event store has never
     * heard of still fails the way it always did, with the message below, rather than being reported as a missing
     * projection row. {@link Optional#empty()} therefore means "the account exists but its statement has not been
     * projected yet" — which is a real state, because the projection is asynchronous.
     *
     * @throws IllegalStateException if the account has no current closing-books generation
     */
    @Transactional(readOnly = true)
    public Optional<AccountOverview> findAccountOverview(TradingAccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        var currentGeneration = aggregateLifecycleApi.findCurrentClosingBooksGeneration(DEMO_ADMIN_PRINCIPAL,
                                                                                       TradingAccounts.AGGREGATE_TYPE,
                                                                                       accountId.toString())
                                                     .orElseThrow(() -> new IllegalStateException("Couldn't resolve current generation for trading account " + accountId));
        var generations = aggregateLifecycleApi.findClosingBooksGenerations(DEMO_ADMIN_PRINCIPAL,
                                                                           TradingAccounts.AGGREGATE_TYPE,
                                                                           accountId.toString());

        return findAccountStatement(accountId)
                .map(statement -> new AccountOverview(statement.logicalAccountId(),
                                                      statement.ownerId(),
                                                      statement.periodId(),
                                                      statement.cashBalance(),
                                                      statement.reservedFunds(),
                                                      statement.realizedPnl(),
                                                      statement.booksClosed(),
                                                      currentGeneration.generation(),
                                                      TradingAccountGenerationId.of(currentGeneration.streamAggregateId()),
                                                      generations.stream()
                                                                 .map(AccountStatementQuery::toAccountGeneration)
                                                                 .toList()));
    }

    private Optional<AccountStatement> findAccountStatement(TradingAccountId accountId) {
        return jdbcTemplate.query("""
                                          SELECT logical_account_id,
                                                 owner_id,
                                                 period_id,
                                                 current_generation,
                                                 generation_count,
                                                 cash_balance,
                                                 reserved_funds,
                                                 realized_pnl,
                                                 books_closed
                                          FROM projection_trading_account_statement
                                          WHERE logical_account_id = ?
                                          """,
                                  ROW_MAPPER,
                                  accountId.toString())
                           .stream()
                           .findFirst();
    }

    /**
     * {@code state} arrives as a {@code String} because {@link ApiClosingBooksGeneration} is the framework's
     * transport-neutral shape; it is the {@code name()} of a {@link GenerationState}, so parsing it back is
     * lossless and a value the enum does not know fails loudly rather than reaching the wire.
     */
    private static AccountGeneration toAccountGeneration(ApiClosingBooksGeneration generation) {
        return new AccountGeneration(generation.generation(),
                                     TradingAccountGenerationId.of(generation.streamAggregateId()),
                                     GenerationState.valueOf(generation.state()),
                                     generation.openedAt(),
                                     generation.closedAt());
    }
}
