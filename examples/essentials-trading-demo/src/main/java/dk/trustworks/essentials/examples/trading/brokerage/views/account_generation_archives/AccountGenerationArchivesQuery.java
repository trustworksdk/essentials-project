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

package dk.trustworks.essentials.examples.trading.brokerage.views.account_generation_archives;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateArchiveApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The two queries of the {@code brokerage.account_generation_archives} slice, over the same model: the archive.
 * <p>
 * The archive is a store this slice reads and does not own — writing to it is
 * {@code use_cases/archive_generation}'s job, and that command slice is where {@code POST …/archive} lives. Listing
 * the entries and reading one entry's contents are two questions about one model, which is one slice (§R2).
 */
@Service
public class AccountGenerationArchivesQuery {
    /**
     * The principal the demo's admin surface acts as. The demo has no authentication; a real deployment would pass the
     * authenticated caller.
     */
    private static final String DEMO_ADMIN_PRINCIPAL = "demo-admin";

    /**
     * Archiving is optional: {@link AggregateArchiveApi} only exists when
     * {@code essentials.eventstore.archives.enabled} is true. Requiring it outright meant the whole application failed
     * to start with archiving switched off, even though only these two endpoints need it.
     */
    private final Optional<AggregateArchiveApi> aggregateArchiveApi;

    public AccountGenerationArchivesQuery(Optional<AggregateArchiveApi> aggregateArchiveApi) {
        this.aggregateArchiveApi = requireNonNull(aggregateArchiveApi, "No aggregateArchiveApi provided");
    }

    @Transactional(readOnly = true)
    public List<ApiArchivedGeneration> archivedGenerations(TradingAccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return archiveApi().findArchivedGenerations(DEMO_ADMIN_PRINCIPAL,
                                                    TradingAccounts.AGGREGATE_TYPE,
                                                    accountId.toString());
    }

    /**
     * The archived events themselves, as the archive wrote them.
     *
     * <p>Only {@code file:} locations are supported, and an unsupported scheme fails loudly with the location it got.
     * The demo archives to the local filesystem; a deployment archiving to object storage would need a reader per
     * scheme, and silently returning nothing would look identical to an empty archive.
     */
    @Transactional(readOnly = true)
    public String archiveContent(TradingAccountId accountId, long generation) {
        requireNonNull(accountId, "No accountId provided");
        var archivedGeneration = archiveApi().findArchivedGeneration(DEMO_ADMIN_PRINCIPAL,
                                                                     TradingAccounts.AGGREGATE_TYPE,
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

    /**
     * Fails with the reason and the fix rather than reporting an empty archive, which would be indistinguishable from
     * an aggregate that genuinely has nothing archived.
     */
    private AggregateArchiveApi archiveApi() {
        return aggregateArchiveApi.orElseThrow(AccountGenerationArchivesQuery::archivingDisabled);
    }

    private static IllegalStateException archivingDisabled() {
        return new IllegalStateException("Aggregate archiving is disabled in this instance. "
                                                 + "Set 'essentials.eventstore.archives.enabled=true' to use the archive endpoints.");
    }
}
