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

package dk.trustworks.essentials.examples.trading.accounts.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.examples.trading.accounts.events.AccountEvent;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link Account}s, reached by logical {@link AccountId} across generations. Every mutating slice
 * goes through {@link #getAccountForMutation}, which is the {@code ON_ACCESS} closing-books trigger: the first command
 * of a new business day rolls the account before it is applied. The close-books slices do not, or one requested
 * rollover would become two.
 */
@Component
public class Accounts {
    public static final AggregateType AGGREGATE_TYPE = AccountsAggregateTypes.ACCOUNTS;

    private static final Logger log = LoggerFactory.getLogger(Accounts.class);

    private final ClosingBooksLogicalAggregateRepository<AccountId, AccountGenerationId, AccountEvent, Account> repository;
    private final AccountClosingBooksPolicy                                                                  closingBooksPolicy;
    private final AccountNextGenerationFactory                                                               nextGenerationFactory;

    public Accounts(ClosingBooksLogicalAggregateRepository<AccountId, AccountGenerationId, AccountEvent, Account> repository,
                    AccountClosingBooksPolicy closingBooksPolicy,
                    AccountNextGenerationFactory nextGenerationFactory) {
        this.repository = requireNonNull(repository, "No repository provided");
        this.closingBooksPolicy = requireNonNull(closingBooksPolicy, "No closingBooksPolicy provided");
        this.nextGenerationFactory = requireNonNull(nextGenerationFactory, "No nextGenerationFactory provided");
    }

    public Account getAccount(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return repository.load(new LogicalAggregateId<>(accountId));
    }

    public Optional<Account> findAccount(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return repository.tryLoad(new LogicalAggregateId<>(accountId));
    }

    public Account getAccountForMutation(AccountId accountId) {
        var account = getAccount(accountId);
        if (!closingBooksPolicy.shouldRolloverOnAccess(account)) {
            return account;
        }
        var nextPeriodId = closingBooksPolicy.nextPeriodId(account);
        log.info("Account '{}' rolls to period {} ({})", accountId, nextPeriodId, closingBooksPolicy.description());
        account.closeBooks(nextPeriodId);
        return closeAndOpenNextGeneration(accountId, account, nextPeriodId);
    }

    public Account openNewAccount(AccountId accountId,
                                  Function<ClosingBooksAggregateInstantiationContext<AccountId, AccountGenerationId>, Account> factory) {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(factory, "No factory provided");
        return repository.open(new LogicalAggregateId<>(accountId), factory::apply);
    }

    public Account closeAndOpenNextGeneration(AccountId accountId, Account currentAccount, PeriodId nextPeriodId) {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(currentAccount, "No currentAccount provided");
        requireNonNull(nextPeriodId, "No nextPeriodId provided");
        return repository.closeAndOpenNextGeneration(new LogicalAggregateId<>(accountId), currentAccount, nextPeriodId, nextGenerationFactory);
    }

    public long currentGeneration(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return repository.resolveCurrentGeneration(new LogicalAggregateId<>(accountId))
                         .map(AggregateGeneration::generation)
                         .orElse(0L);
    }
}
