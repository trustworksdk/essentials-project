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

package dk.trustworks.essentials.examples.trading.brokerage.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksAggregateInstantiationContext;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksLogicalAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.LogicalAggregateId;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.TypedClosingBooksNextGenerationFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradingAccountEvent;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link TradingAccount} aggregates, and the owner of the {@code TradingAccounts}
 * {@link AggregateType} -- the name under which their events are stored, which every subscriber and projection in the
 * brokerage context refers back to.
 *
 * <p>It wraps a {@link ClosingBooksLogicalAggregateRepository} rather than a plain {@code StatefulAggregateRepository},
 * because a trading account is not one stream. Each books generation is its own stream keyed by
 * {@link TradingAccountGenerationId}, and the closing-books repository is what maps the {@link TradingAccountId} a
 * caller holds onto whichever generation is currently open. That mapping is the whole reason this wrapper exists:
 * callers stay on business ids and never see a generation id.
 *
 * <p>The thin wrapper also exists so the context speaks its own language ({@code getAccount}, {@code openNewAccount})
 * instead of a generic {@code load}/{@code open}.
 *
 * <p>It does not construct aggregates; see {@link #openNewAccount}.
 */
@Component
public class TradingAccounts {
    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("TradingAccounts");

    private static final Logger log = LoggerFactory.getLogger(TradingAccounts.class);

    private final ClosingBooksLogicalAggregateRepository<TradingAccountId, TradingAccountGenerationId, TradingAccountEvent, TradingAccount> repository;
    private final TradingAccountClosingBooksPolicy                                                                                          closingBooksPolicy;
    private final TradingAccountNextGenerationFactory                                                                                       nextGenerationFactory;

    public TradingAccounts(ClosingBooksLogicalAggregateRepository<TradingAccountId, TradingAccountGenerationId, TradingAccountEvent, TradingAccount> repository,
                           TradingAccountClosingBooksPolicy closingBooksPolicy,
                           TradingAccountNextGenerationFactory nextGenerationFactory) {
        this.repository = requireNonNull(repository, "No repository provided");
        this.closingBooksPolicy = requireNonNull(closingBooksPolicy, "No closingBooksPolicy provided");
        this.nextGenerationFactory = requireNonNull(nextGenerationFactory, "No nextGenerationFactory provided");
    }

    public TradingAccount getAccount(TradingAccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return repository.load(new LogicalAggregateId<>(accountId));
    }

    /**
     * Loads the account for a command that is about to change it, rolling its books first if the
     * {@code ON_ACCESS} closing-books trigger says they are due.
     *
     * <p>This is the {@code ON_ACCESS} half of {@link TradingAccount}'s {@code @AggregateClosingBooksPolicy}: the
     * rollover is not something a caller asks for, it is a property of <em>loading</em> an account whose period has
     * run out — every mutating slice gets the same behaviour whether or not it knows the policy exists. That is why it
     * lives on this repository wrapper beside {@link #getAccount} rather than in any one slice: a slice that had to
     * remember to ask for it would be a slice that could forget, and the accounting period would then depend on which
     * command happened to arrive.
     *
     * <p>A slice that wants the books left exactly as they are -- {@code close_books}, which is the <em>manual</em>
     * trigger -- calls {@link #getAccount} instead.
     *
     * @param accountId the logical account to load
     * @return the account, either the generation that was already open or the one this call opened
     */
    public TradingAccount getAccountForMutation(TradingAccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        var account = getAccount(accountId);
        if (!closingBooksPolicy.shouldRolloverOnAccess(account)) {
            return account;
        }

        var nextPeriodId = closingBooksPolicy.nextPeriodId(account);
        log.info("TradingAccount '{}' triggered automatic closing-books rollover using policy '{}'. Current generation={}, nextPeriodId={}",
                 accountId,
                 closingBooksPolicy.description(),
                 repository.resolveCurrentGeneration(new LogicalAggregateId<>(accountId))
                           .map(AggregateGeneration::generation)
                           .orElse(1L),
                 nextPeriodId);
        account.closeBooks(nextPeriodId);
        return closeAndOpenNextGeneration(accountId,
                                          account,
                                          nextPeriodId,
                                          nextGenerationFactory);
    }

    public Optional<TradingAccount> findAccount(TradingAccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return repository.tryLoad(new LogicalAggregateId<>(accountId));
    }

    /**
     * Opens the first books generation for an account, persisting the {@link TradingAccount} the caller's factory
     * builds. Constructing it -- which is what emits {@code TradingAccountOpened} -- is the opening slice's decision,
     * not this repository's, so the caller supplies the factory.
     *
     * <p>It is a factory rather than a finished aggregate because the stream id does not exist until the generation is
     * opened: the repository allocates the generation, then hands its
     * {@link ClosingBooksAggregateInstantiationContext} to the factory.
     *
     * @throws IllegalStateException if a generation is already open for this account
     */
    public TradingAccount openNewAccount(TradingAccountId accountId,
                                         Function<ClosingBooksAggregateInstantiationContext<TradingAccountId, TradingAccountGenerationId>, TradingAccount> factory) {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(factory, "No factory provided");
        return repository.open(new LogicalAggregateId<>(accountId),
                               factory::apply);
    }

    /**
     * The generation the account's books are currently open in, or {@code 0} if no generation exists yet.
     * Lets a caller observe a policy-driven rollover happening without having to know how it was triggered.
     */
    public long currentGeneration(TradingAccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return repository.resolveCurrentGeneration(new LogicalAggregateId<>(accountId))
                         .map(AggregateGeneration::generation)
                         .orElse(0L);
    }

    /**
     * Seals the account's current generation and opens the next one on {@code nextPeriodId}, persisting the aggregate
     * {@code nextGenerationFactory} carries forward.
     *
     * <p>The caller is expected to have called {@link TradingAccount#closeBooks(PeriodId)} on {@code currentAccount}
     * first -- that is what writes the closing entry into the outgoing stream; this call is what allocates the
     * incoming one.
     */
    public TradingAccount closeAndOpenNextGeneration(TradingAccountId accountId,
                                                     TradingAccount currentAccount,
                                                     PeriodId nextPeriodId,
                                                     TypedClosingBooksNextGenerationFactory<TradingAccountId, TradingAccountGenerationId, TradingAccount, PeriodId> nextGenerationFactory) {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(currentAccount, "No currentAccount provided");
        requireNonNull(nextPeriodId, "No nextPeriodId provided");
        requireNonNull(nextGenerationFactory, "No nextGenerationFactory provided");
        return repository.closeAndOpenNextGeneration(new LogicalAggregateId<>(accountId),
                                                     currentAccount,
                                                     nextPeriodId,
                                                     nextGenerationFactory);
    }
}
