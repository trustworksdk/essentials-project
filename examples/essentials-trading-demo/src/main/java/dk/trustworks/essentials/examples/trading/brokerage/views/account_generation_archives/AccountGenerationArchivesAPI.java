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

import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read API of the {@code brokerage.account_generation_archives} view slice, and of no other (§R2).
 * <p>
 * Two query methods, one slice: both interrogate the archive. Writing to it is
 * {@code use_cases/archive_generation}'s job — {@code POST …/generations/{generation}/archive} lives there, not here,
 * even though it shares this path prefix.
 * <p>
 * The archive contents endpoint produces {@code text/plain}: what comes back is the archive file exactly as written,
 * and declaring it JSON would invite a client to parse it as this application's shape rather than the archive's.
 */
@RestController
@RequestMapping(path = "/api/admin/trading-accounts")
public class AccountGenerationArchivesAPI {
    private final AccountGenerationArchivesQuery query;

    public AccountGenerationArchivesAPI(AccountGenerationArchivesQuery accountGenerationArchivesQuery) {
        this.query = requireNonNull(accountGenerationArchivesQuery, "No accountGenerationArchivesQuery provided");
    }

    @GetMapping("/{accountId}/archives")
    public List<ApiArchivedGeneration> archivedGenerations(@PathVariable TradingAccountId accountId) {
        return query.archivedGenerations(accountId);
    }

    @GetMapping(value = "/{accountId}/generations/{generation}/archive-content", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> archiveContent(@PathVariable TradingAccountId accountId,
                                                 @PathVariable long generation) {
        return ResponseEntity.ok(query.archiveContent(accountId, generation));
    }
}
