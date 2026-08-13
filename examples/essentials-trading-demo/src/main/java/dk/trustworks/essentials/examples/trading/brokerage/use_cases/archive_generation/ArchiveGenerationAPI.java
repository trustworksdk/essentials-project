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

import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code brokerage.archive_generation} slice (rules/slice-design.md §R2).
 *
 * <p>No request body: both values are in the path, so the command is constructed inline -- there is nothing to
 * reconcile the way the deposit-style endpoints have to.
 *
 * <p>Uses {@code send} rather than {@code sendAndDontWait} because the response <em>is</em> the handler's return
 * value: the archived generation as the archive reports it back.
 */
@RestController
@RequestMapping(path = "/api/admin/trading-accounts")
public class ArchiveGenerationAPI {
    private final CommandBus commandBus;

    public ArchiveGenerationAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{accountId}/generations/{generation}/archive")
    public ApiArchivedGeneration archiveGeneration(@PathVariable TradingAccountId accountId,
                                                   @PathVariable long generation) {
        return commandBus.send(new ArchiveGeneration(accountId, generation));
    }
}
