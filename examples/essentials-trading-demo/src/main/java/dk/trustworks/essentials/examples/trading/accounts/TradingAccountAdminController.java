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

import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiClosingBooksGenerationEventStream;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Minimal admin API for inspecting logical trading accounts and their underlying generations.
 */
@RestController
@RequestMapping("/api/admin/trading-accounts")
public class TradingAccountAdminController {
    private final TradingAccountAdminQueryService queryService;
    private final TradingAccountClosingBooksPolicy closingBooksPolicy;

    public TradingAccountAdminController(TradingAccountAdminQueryService queryService,
                                         TradingAccountClosingBooksPolicy closingBooksPolicy) {
        this.queryService = queryService;
        this.closingBooksPolicy = closingBooksPolicy;
    }

    @GetMapping("/{accountId}")
    public TradingAccountAdminView getTradingAccount(@PathVariable String accountId) {
        return queryService.getAccountView(TradingAccountId.of(accountId));
    }

    @GetMapping("/{accountId}/generations/{generation}/events")
    public ApiClosingBooksGenerationEventStream getTradingAccountGenerationEvents(@PathVariable String accountId,
                                                                                  @PathVariable long generation) {
        return queryService.getGenerationEventStream(TradingAccountId.of(accountId), generation);
    }

    @GetMapping("/{accountId}/archives")
    public List<ApiArchivedGeneration> getTradingAccountArchives(@PathVariable String accountId) {
        return queryService.getArchivedGenerations(TradingAccountId.of(accountId));
    }

    @GetMapping(value = "/{accountId}/generations/{generation}/archive-content", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getTradingAccountGenerationArchiveContent(@PathVariable String accountId,
                                                                            @PathVariable long generation) {
        return ResponseEntity.ok(queryService.getArchiveContent(TradingAccountId.of(accountId), generation));
    }

    @PostMapping("/{accountId}/generations/{generation}/archive")
    public ApiArchivedGeneration archiveTradingAccountGeneration(@PathVariable String accountId,
                                                                 @PathVariable long generation) {
        return queryService.archiveGeneration(TradingAccountId.of(accountId), generation);
    }

    @GetMapping("/closing-books")
    public TradingAccountClosingBooksConfigurationView closingBooksConfiguration() {
        return new TradingAccountClosingBooksConfigurationView(closingBooksPolicy.mode().name().toLowerCase().replace('_', '-'),
                                                               closingBooksPolicy.eventThreshold(),
                                                               closingBooksPolicy.timeBoundary().name().toLowerCase().replace('_', '-'),
                                                               closingBooksPolicy.zoneId(),
                                                               closingBooksPolicy.intervalDays(),
                                                               closingBooksPolicy.description());
    }

    @PostMapping("/closing-books/time-boundary")
    public TradingAccountClosingBooksConfigurationView updateTimeBoundary(@RequestParam String value) {
        closingBooksPolicy.updateTimeBoundary(value);
        return closingBooksConfiguration();
    }

    @PostMapping("/closing-books/zone-id")
    public TradingAccountClosingBooksConfigurationView updateZoneId(@RequestParam String value) {
        closingBooksPolicy.updateZoneId(value);
        return closingBooksConfiguration();
    }

    @PostMapping("/closing-books/interval-days")
    public TradingAccountClosingBooksConfigurationView updateIntervalDays(@RequestParam int value) {
        closingBooksPolicy.updateIntervalDays(value);
        return closingBooksConfiguration();
    }

    @PostMapping("/closing-books/mode")
    public TradingAccountClosingBooksConfigurationView updateMode(@RequestParam String value) {
        closingBooksPolicy.updateMode(value);
        return closingBooksConfiguration();
    }
}
