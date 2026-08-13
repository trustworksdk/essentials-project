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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.archive_generation;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateArchiveApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.archive.AggregateGenerationArchiver;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.archive_generation} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 *
 * <p>Both collaborators are {@link Optional} because they only exist when
 * {@code essentials.eventstore.archives.enabled} is true. Requiring them outright made the <em>whole application</em>
 * fail to start with archiving switched off, even though only this slice needs them -- so the absence is reported
 * here, at the point of use, with the property that turns it on.
 *
 * <p>The {@code @CmdHandler} returns a value, which is allowed: archiving is the one write in this context whose
 * result the caller cannot obtain any other way in the same request. The archiver's own return is deliberately
 * ignored in favour of reloading through {@link AggregateArchiveApi}, so the endpoint reports what the archive
 * <em>says</em> rather than what the archiver claimed to have written.
 */
@Service
public class ArchiveGenerationHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(ArchiveGenerationHandler.class);

    /**
     * The principal the demo's admin surface acts as. The demo has no authentication; a real deployment would pass
     * the authenticated caller.
     */
    private static final String DEMO_ADMIN_PRINCIPAL = "demo-admin";

    private final Optional<AggregateGenerationArchiver> aggregateGenerationArchiver;
    private final Optional<AggregateArchiveApi>         aggregateArchiveApi;

    public ArchiveGenerationHandler(Optional<AggregateGenerationArchiver> aggregateGenerationArchiver,
                                    Optional<AggregateArchiveApi> aggregateArchiveApi) {
        this.aggregateGenerationArchiver = requireNonNull(aggregateGenerationArchiver, "No aggregateGenerationArchiver provided");
        this.aggregateArchiveApi = requireNonNull(aggregateArchiveApi, "No aggregateArchiveApi provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public ApiArchivedGeneration handle(ArchiveGeneration cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Archiving generation {} of TradingAccount '{}'", cmd.generation(), cmd.accountId());

        var archiver = aggregateGenerationArchiver.orElseThrow(ArchiveGenerationHandler::archivingDisabled);
        var archiveApi = aggregateArchiveApi.orElseThrow(ArchiveGenerationHandler::archivingDisabled);

        archiver.archiveGeneration(TradingAccounts.AGGREGATE_TYPE,
                                   cmd.accountId().toString(),
                                   cmd.generation());
        return archiveApi.findArchivedGeneration(DEMO_ADMIN_PRINCIPAL,
                                                 TradingAccounts.AGGREGATE_TYPE,
                                                 cmd.accountId().toString(),
                                                 cmd.generation())
                         .orElseThrow(() -> new IllegalStateException("Archived generation " + cmd.generation() + " for trading account " + cmd.accountId() + " could not be reloaded"));
    }

    /**
     * Fails with the reason and the fix rather than reporting an empty archive, which would be indistinguishable from
     * an aggregate that genuinely has nothing archived.
     */
    private static IllegalStateException archivingDisabled() {
        return new IllegalStateException("Aggregate archiving is disabled in this instance. "
                                                 + "Set 'essentials.eventstore.archives.enabled=true' to use the archive endpoints.");
    }
}
