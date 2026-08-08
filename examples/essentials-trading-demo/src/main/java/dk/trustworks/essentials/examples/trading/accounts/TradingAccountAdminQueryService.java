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

import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateArchiveApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiAggregateSnapshot;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiClosingBooksGenerationEventStream;
import dk.trustworks.essentials.components.eventsourced.aggregates.archive.AggregateGenerationArchiver;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.GenerationState;
import dk.trustworks.essentials.examples.trading.config.TradingDemoAggregateConfiguration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Read-only query service used by the demo admin API to inspect trading account rollover state.
 */
@Service
public class TradingAccountAdminQueryService {
    private final TradingAccountService tradingAccountService;
    private final AggregateLifecycleApi aggregateLifecycleApi;
    /**
     * Archiving is optional: both of these beans only exist when {@code essentials.eventstore.archives.enabled} is
     * true. Requiring them outright meant the whole application failed to start with archiving switched off, even
     * though only the three archive endpoints below need them.
     */
    private final Optional<AggregateArchiveApi> aggregateArchiveApi;
    private final Optional<AggregateGenerationArchiver> aggregateGenerationArchiver;

    public TradingAccountAdminQueryService(TradingAccountService tradingAccountService,
                                           AggregateLifecycleApi aggregateLifecycleApi,
                                           Optional<AggregateArchiveApi> aggregateArchiveApi,
                                           Optional<AggregateGenerationArchiver> aggregateGenerationArchiver) {
        this.tradingAccountService = tradingAccountService;
        this.aggregateLifecycleApi = aggregateLifecycleApi;
        this.aggregateArchiveApi = aggregateArchiveApi;
        this.aggregateGenerationArchiver = aggregateGenerationArchiver;
    }

    /**
     * Fails with the reason and the fix rather than reporting an empty archive, which would be indistinguishable from
     * an aggregate that genuinely has nothing archived.
     */
    private AggregateArchiveApi archiveApi() {
        return aggregateArchiveApi.orElseThrow(TradingAccountAdminQueryService::archivingDisabled);
    }

    private AggregateGenerationArchiver archiver() {
        return aggregateGenerationArchiver.orElseThrow(TradingAccountAdminQueryService::archivingDisabled);
    }

    private static IllegalStateException archivingDisabled() {
        return new IllegalStateException("Aggregate archiving is disabled in this instance. "
                                                 + "Set 'essentials.eventstore.archives.enabled=true' to use the archive endpoints.");
    }

    @Transactional(readOnly = true)
    public TradingAccountAdminView getAccountView(TradingAccountId accountId) {
        var account = tradingAccountService.load(accountId);
        var currentGeneration = aggregateLifecycleApi.findCurrentClosingBooksGeneration("demo-admin",
                                                                                        TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                                                        accountId.toString())
                                                     .orElseThrow(() -> new IllegalStateException("Couldn't resolve current generation for trading account " + accountId));
        var generations = aggregateLifecycleApi.findClosingBooksGenerations("demo-admin",
                                                                            TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                                            accountId.toString());

        return new TradingAccountAdminView(account.logicalAccountId.toString(),
                                           account.ownerId,
                                           account.periodId,
                                           account.cashBalance,
                                           account.reservedFunds,
                                           account.realizedPnl,
                                           account.booksClosed,
                                           currentGeneration.generation(),
                                           currentGeneration.streamAggregateId(),
                                           generations.stream()
                                                      .map(generation -> new TradingAccountGenerationView(generation.generation(),
                                                                                                          generation.streamAggregateId(),
                                                                                                          GenerationState.valueOf(generation.state()),
                                                                                                          generation.openedAt(),
                                                                                                          generation.closedAt()))
                                                      .toList());
    }

    /**
     * Snapshots stored for the account's <em>current</em> generation.
     * <p>
     * Scoped to the current generation on purpose: snapshots are keyed by the per-generation stream id, so
     * "all snapshots for this account" means one lookup per generation, and the demo's load generator rolls
     * generations continuously — an unbounded fan-out on a dashboard that refreshes on a timer. The live
     * generation is also the interesting one when watching the snapshot policy work.
     */
    @Transactional(readOnly = true)
    public List<ApiAggregateSnapshot> getCurrentGenerationSnapshots(TradingAccountId accountId) {
        var currentGeneration = aggregateLifecycleApi.findCurrentClosingBooksGeneration("demo-admin",
                                                                                        TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                                                        accountId.toString());
        return currentGeneration.map(generation -> aggregateLifecycleApi.findSnapshots("demo-admin",
                                                                                        TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                                                        generation.streamAggregateId(),
                                                                                        false))
                                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public ApiClosingBooksGenerationEventStream getGenerationEventStream(TradingAccountId accountId, long generation) {
        return aggregateLifecycleApi.findClosingBooksGenerationEventStream("demo-admin",
                                                                           TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                                           accountId.toString(),
                                                                           generation)
                                    .orElseThrow(() -> new IllegalStateException("Couldn't resolve generation " + generation + " for trading account " + accountId));
    }

    @Transactional(readOnly = true)
    public List<ApiArchivedGeneration> getArchivedGenerations(TradingAccountId accountId) {
        return archiveApi().findArchivedGenerations("demo-admin",
                                                           TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                           accountId.toString());
    }

    @Transactional(readOnly = true)
    public String getArchiveContent(TradingAccountId accountId, long generation) {
        var archivedGeneration = archiveApi().findArchivedGeneration("demo-admin",
                                                                            TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                                            accountId.toString(),
                                                                            generation)
                                                    .orElseThrow(() -> new IllegalStateException("Couldn't resolve archived generation " + generation + " for trading account " + accountId));
        var archiveUri = URI.create(archivedGeneration.archiveLocation());
        if (!"file".equalsIgnoreCase(archiveUri.getScheme())) {
            throw new IllegalStateException("Only file-based archive locations are currently supported in the trading demo, but got '" + archivedGeneration.archiveLocation() + "'");
        }
        try {
            return Files.readString(Paths.get(archiveUri));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read archive content from '" + archivedGeneration.archiveLocation() + "'", e);
        }
    }

    @Transactional
    public ApiArchivedGeneration archiveGeneration(TradingAccountId accountId, long generation) {
        var archivedGeneration = archiver().archiveGeneration(TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                                               accountId.toString(),
                                                                               generation);
        return archiveApi().findArchivedGeneration("demo-admin",
                                                          TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                                                          accountId.toString(),
                                                          generation)
                                 .orElseThrow(() -> new IllegalStateException("Archived generation " + generation + " for trading account " + accountId + " could not be reloaded"));
    }
}
