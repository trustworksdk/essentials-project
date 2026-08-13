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

import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Move one sealed books generation of a trading account out of the event store and into the archive.
 *
 * <p>Unlike its sibling slices this command is never a request body -- the endpoint carries both values as path
 * variables and constructs it inline, because {@code POST .../generations/{generation}/archive} has nothing left to
 * say in a body.
 *
 * @param accountId  the account whose generation is archived
 * @param generation the generation number to archive; a sealed one, not the currently open one
 */
public record ArchiveGeneration(TradingAccountId accountId,
                                long generation) {
    public ArchiveGeneration {
        requireNonNull(accountId, "No accountId provided");
    }
}
