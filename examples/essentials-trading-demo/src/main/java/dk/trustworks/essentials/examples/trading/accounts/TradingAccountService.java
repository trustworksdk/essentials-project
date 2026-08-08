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

package dk.trustworks.essentials.examples.trading.accounts;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksLogicalAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.LogicalAggregateId;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Transactional application service for the {@link TradingAccount} aggregate.
 */
@Service
public class TradingAccountService {
    private static final Logger log = LoggerFactory.getLogger(TradingAccountService.class);

    private final ClosingBooksLogicalAggregateRepository<TradingAccountId, TradingAccountGenerationId, TradingAccountEvent, TradingAccount> repository;
    private final TradingAccountClosingBooksPolicy closingBooksPolicy;
    private final TradingAccountNextGenerationFactory nextGenerationFactory;

    public TradingAccountService(ClosingBooksLogicalAggregateRepository<TradingAccountId, TradingAccountGenerationId, TradingAccountEvent, TradingAccount> repository,
                                 TradingAccountClosingBooksPolicy closingBooksPolicy,
                                 TradingAccountNextGenerationFactory nextGenerationFactory) {
        this.repository = repository;
        this.closingBooksPolicy = closingBooksPolicy;
        this.nextGenerationFactory = nextGenerationFactory;
    }

    @Transactional
    public TradingAccount openAccount(TradingAccountId accountId, String ownerId, String periodId) {
        return repository.open(new LogicalAggregateId<>(accountId),
                               context -> new TradingAccount(context.streamAggregateId(),
                                                             accountId,
                                                             ownerId,
                                                             periodId));
    }

    @Transactional
    public TradingAccount depositCash(TradingAccountId accountId, BigDecimal amount) {
        var account = loadForMutation(accountId);
        account.depositCash(amount);
        return account;
    }

    @Transactional
    public TradingAccount reserveFunds(TradingAccountId accountId, BigDecimal amount) {
        var account = loadForMutation(accountId);
        account.reserveFunds(amount);
        return account;
    }

    @Transactional
    public TradingAccount releaseFunds(TradingAccountId accountId, BigDecimal amount) {
        var account = loadForMutation(accountId);
        account.releaseFunds(amount);
        return account;
    }

    @Transactional
    public TradingAccount applyTradeSettlement(TradingAccountId accountId,
                                               String tradeId,
                                               BigDecimal cashDelta,
                                               BigDecimal realizedPnlDelta) {
        var account = loadForMutation(accountId);
        account.applyTradeSettlement(tradeId, cashDelta, realizedPnlDelta);
        return account;
    }

    @Transactional
    public TradingAccount closeBooks(TradingAccountId accountId, String nextPeriodId) {
        var account = repository.load(new LogicalAggregateId<>(accountId));
        account.closeBooks(nextPeriodId);
        return account;
    }

    @Transactional
    public TradingAccount closeBooksAndOpenNextPeriod(TradingAccountId accountId, String nextPeriodId) {
        var logicalAggregateId = new LogicalAggregateId<>(accountId);
        var account = repository.load(logicalAggregateId);
        account.closeBooks(nextPeriodId);

        return repository.closeAndOpenNextGeneration(logicalAggregateId,
                                                     account,
                                                     nextPeriodId,
                                                     nextGenerationFactory);
    }

    @Transactional(readOnly = true)
    public TradingAccount load(TradingAccountId accountId) {
        return repository.load(new LogicalAggregateId<>(accountId));
    }

    /**
     * The generation the account's books are currently open in, or {@code 0} if no generation exists yet.
     * Lets a caller observe a policy-driven rollover happening without having to know how it was triggered.
     */
    @Transactional(readOnly = true)
    public long currentGeneration(TradingAccountId accountId) {
        return repository.resolveCurrentGeneration(new LogicalAggregateId<>(accountId))
                         .map(AggregateGeneration::generation)
                         .orElse(0L);
    }

    @Transactional(readOnly = true)
    public Optional<TradingAccount> tryLoad(TradingAccountId accountId) {
        return repository.tryLoad(new LogicalAggregateId<>(accountId));
    }

    private TradingAccount loadForMutation(TradingAccountId accountId) {
        var logicalAggregateId = new LogicalAggregateId<>(accountId);
        var account = repository.load(logicalAggregateId);
        if (!closingBooksPolicy.shouldRolloverOnAccess(account)) {
            return account;
        }

        var nextPeriodId = closingBooksPolicy.nextPeriodId(account);
        log.info("TradingAccount '{}' triggered automatic closing-books rollover using policy '{}'. Current generation={}, nextPeriodId={}",
                 accountId,
                 closingBooksPolicy.description(),
                 repository.resolveCurrentGeneration(logicalAggregateId).map(generation -> generation.generation()).orElse(1L),
                 nextPeriodId);
        account.closeBooks(nextPeriodId);
        return repository.closeAndOpenNextGeneration(logicalAggregateId,
                                                     account,
                                                     nextPeriodId,
                                                     nextGenerationFactory);
    }
}
