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

package dk.trustworks.essentials.examples.trading;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksGenerationRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.GenerationState;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.LogicalAggregateId;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccount;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountAdminView;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountClosingBooksConfigurationView;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleApi;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountService;
import dk.trustworks.essentials.examples.trading.config.TradingDemoAggregateConfiguration;
import dk.trustworks.essentials.examples.trading.dashboard.DashboardMetricSummaryView;
import dk.trustworks.essentials.examples.trading.dashboard.DashboardSummaryView;
import dk.trustworks.essentials.examples.trading.instruments.Instrument;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentService;
import dk.trustworks.essentials.examples.trading.prices.InstrumentPriceService;
import dk.trustworks.essentials.examples.trading.projections.TradeSettlementProjectionView;
import dk.trustworks.essentials.examples.trading.projections.TradingAccountStatementProjectionView;
import dk.trustworks.essentials.examples.trading.settlements.Settlement;
import dk.trustworks.essentials.examples.trading.settlements.SettlementAdminView;
import dk.trustworks.essentials.examples.trading.settlements.SettlementId;
import dk.trustworks.essentials.examples.trading.settlements.SettlementService;
import dk.trustworks.essentials.examples.trading.simulation.TradingDemoSimulationProperties;
import dk.trustworks.essentials.examples.trading.simulation.TradingLoadGeneratorStatusView;
import dk.trustworks.essentials.examples.trading.simulation.PricePathScenarioResultView;
import dk.trustworks.essentials.examples.trading.simulation.TradingAccountScenarioResultView;
import dk.trustworks.essentials.examples.trading.simulation.TradingSimulationRunner;
import dk.trustworks.essentials.examples.trading.trades.Trade;
import dk.trustworks.essentials.examples.trading.trades.TradeAdminView;
import dk.trustworks.essentials.examples.trading.trades.TradeId;
import dk.trustworks.essentials.examples.trading.trades.TradeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.accounts.closing-books.event-threshold=100000",
        "essentials.eventstore.archives.enabled=true"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TradingDemoApplicationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4")
            .withDatabaseName("trading-demo-test-db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TradingDemoSimulationProperties simulationProperties;

    @Autowired
    private TradingAccountService tradingAccountService;

    @Autowired
    private TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy;

    @Autowired
    private ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private InstrumentService instrumentService;

    @Autowired
    private TradeService tradeService;

    @Autowired
    private InstrumentPriceService instrumentPriceService;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetClosingBooksPolicy() {
        tradingAccountClosingBooksPolicy.updateMode("manual-only");
        tradingAccountClosingBooksPolicy.updateEventThreshold(100);
        tradingAccountClosingBooksPolicy.updateTimeBoundary("end-of-month");
        tradingAccountClosingBooksPolicy.updateZoneId("Europe/Copenhagen");
    }

    @Test
    void application_context_starts_and_exposes_demo_types() {
        assertThat(applicationContext).isNotNull();
        assertThat(simulationProperties.isEnabled()).isFalse();
        assertThat(applicationContext.getBean(TradingSimulationRunner.class)).isNotNull();
        /*
         * Asserted through the lifecycle API rather than with isAnnotationPresent, which is what the previous version of
         * this test did. An annotation reaches a policy registry only if something registers it, so asserting its
         * presence on the class proved nothing — and let Settlement carry a closing-books policy that never did
         * anything, for as long as this test claimed to cover it.
         */
        var lifecycleApi = applicationContext.getBean(AggregateLifecycleApi.class);

        assertThat(lifecycleApi.findAllAggregateSnapshotPolicies("demo-admin"))
                .describedAs("Both snapshot policies must reach the registry, not merely annotate their classes")
                .extracting(policy -> policy.aggregateType().toString())
                .containsExactlyInAnyOrder("TradingAccounts", "InstrumentPrices");
        assertThat(lifecycleApi.findAllAggregateClosingBooksPolicies("demo-admin"))
                .extracting(policy -> policy.aggregateType().toString())
                .containsExactly("TradingAccounts");
    }

    @Test
    void application_services_persist_and_load_demo_aggregates() {
        var accountId = TradingAccountId.of("ACC-TEST-1");
        var tradeId = TradeId.of("TRD-TEST-1");
        var settlementId = SettlementId.of("SET-TEST-1");
        var instrumentId = InstrumentId.of("INST-TEST-1");

        tradingAccountService.openAccount(accountId, "owner-test", "2026-03");
        tradingAccountService.depositCash(accountId, BigDecimal.valueOf(1_500));
        tradingAccountService.reserveFunds(accountId, BigDecimal.valueOf(250));

        instrumentService.registerInstrument(instrumentId, "ABC", "Alpha Bravo");
        instrumentService.rename(instrumentId, "Alpha Bravo Updated");
        instrumentPriceService.initializePrice(instrumentId, BigDecimal.valueOf(480));

        tradeService.placeTrade(tradeId,
                                accountId,
                                instrumentId,
                                "BUY",
                                BigDecimal.ONE,
                                BigDecimal.valueOf(500));
        tradeService.executeTrade(tradeId);
        tradeService.requestSettlement(tradeId, settlementId.toString());
        instrumentPriceService.updatePrice(instrumentId, BigDecimal.valueOf(525));

        settlementService.createSettlement(settlementId,
                                           tradeId.toString(),
                                           accountId.toString(),
                                           BigDecimal.valueOf(500));
        settlementService.requestClearing(settlementId);
        settlementService.confirmClearing(settlementId);
        settlementService.markSettled(settlementId);
        settlementService.reconcile(settlementId);
        settlementService.closeSettlement(settlementId);
        tradeService.markSettled(tradeId);

        tradingAccountService.applyTradeSettlement(accountId,
                                                   tradeId.toString(),
                                                   BigDecimal.valueOf(-500),
                                                   BigDecimal.valueOf(42));

        var persistedAccount = tradingAccountService.load(accountId);
        var persistedTrade = tradeService.load(tradeId);
        var persistedSettlement = settlementService.load(settlementId);
        var persistedInstrument = instrumentService.load(instrumentId);

        assertThat(persistedAccount.cashBalance).isEqualByComparingTo("1000");
        assertThat(persistedAccount.reservedFunds).isEqualByComparingTo("250");
        assertThat(persistedAccount.realizedPnl).isEqualByComparingTo("42");
        assertThat(persistedTrade.executed).isTrue();
        assertThat(persistedTrade.settlementRequested).isTrue();
        assertThat(persistedTrade.settled).isTrue();
        assertThat(persistedTrade.instrumentId.toString()).isEqualTo(instrumentId.toString());
        assertThat(persistedSettlement.closed).isTrue();
        assertThat(persistedSettlement.reconciled).isTrue();
        assertThat(persistedInstrument.displayName).isEqualTo("Alpha Bravo Updated");
    }

    @Test
    void trading_account_can_roll_over_to_a_new_generation_without_exposing_stream_ids() {
        var accountId = TradingAccountId.of("ACC-ROLLOVER-1");

        tradingAccountService.openAccount(accountId, "owner-rollover", "2026-03");
        tradingAccountService.depositCash(accountId, BigDecimal.valueOf(2_000));
        tradingAccountService.applyTradeSettlement(accountId,
                                                  "trade-rollover-1",
                                                  BigDecimal.valueOf(-250),
                                                  BigDecimal.valueOf(15));

        var nextPeriodAccount = tradingAccountService.closeBooksAndOpenNextPeriod(accountId, "2026-04");
        var reloadedAccount = tradingAccountService.load(accountId);
        var generations = tradingAccountGenerationRepository.loadGenerations(
                dk.trustworks.essentials.examples.trading.config.TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
                new LogicalAggregateId<>(accountId));

        assertThat(nextPeriodAccount.logicalAccountId.toString()).isEqualTo(accountId.toString());
        assertThat(nextPeriodAccount.periodId).isEqualTo("2026-04");
        assertThat(nextPeriodAccount.cashBalance).isEqualByComparingTo("1750");
        assertThat(nextPeriodAccount.realizedPnl).isEqualByComparingTo("0");
        assertThat(nextPeriodAccount.booksClosed).isFalse();

        assertThat(reloadedAccount.periodId).isEqualTo("2026-04");
        assertThat(reloadedAccount.cashBalance).isEqualByComparingTo("1750");
        assertThat(reloadedAccount.realizedPnl).isEqualByComparingTo("0");

        assertThat(generations).hasSize(2);
        assertThat(generations.get(0).state()).isEqualTo(GenerationState.CLOSED);
        assertThat(generations.get(1).state()).isEqualTo(GenerationState.OPEN);
        assertThat(generations.get(0).streamAggregateId()).isNotEqualTo(generations.get(1).streamAggregateId());
    }

    @Test
    void admin_endpoint_exposes_current_trading_account_and_generation_history() {
        var accountId = TradingAccountId.of("ACC-ADMIN-1");

        tradingAccountService.openAccount(accountId, "owner-admin", "2026-03");
        tradingAccountService.depositCash(accountId, BigDecimal.valueOf(3_000));
        tradingAccountService.applyTradeSettlement(accountId,
                                                  "trade-admin-1",
                                                  BigDecimal.valueOf(-450),
                                                  BigDecimal.valueOf(25));
        tradingAccountService.closeBooksAndOpenNextPeriod(accountId, "2026-04");

        var response = restTemplate.getForEntity("/api/admin/trading-accounts/{accountId}",
                                                 TradingAccountAdminView.class,
                                                 accountId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().logicalAccountId()).isEqualTo(accountId.toString());
        assertThat(response.getBody().ownerId()).isEqualTo("owner-admin");
        assertThat(response.getBody().currentStatementPeriod()).isEqualTo("2026-04");
        assertThat(response.getBody().cashBalance()).isEqualByComparingTo("2550");
        assertThat(response.getBody().realizedPnl()).isEqualByComparingTo("0");
        assertThat(response.getBody().currentGeneration()).isEqualTo(2);
        assertThat(response.getBody().generations()).hasSize(2);
        assertThat(response.getBody().generations().get(0).state()).isEqualTo(GenerationState.CLOSED);
        assertThat(response.getBody().generations().get(1).state()).isEqualTo(GenerationState.OPEN);
    }

    @Test
    void admin_endpoint_can_read_events_for_a_specific_generation() {
        var accountId = TradingAccountId.of("ACC-ADMIN-EVENTS-1");

        tradingAccountService.openAccount(accountId, "owner-admin", "2026-03");
        tradingAccountService.depositCash(accountId, BigDecimal.valueOf(3_000));
        tradingAccountService.applyTradeSettlement(accountId,
                                                  "trade-admin-events-1",
                                                  BigDecimal.valueOf(-450),
                                                  BigDecimal.valueOf(25));
        tradingAccountService.closeBooksAndOpenNextPeriod(accountId, "2026-04");

        var response = restTemplate.getForEntity("/api/admin/trading-accounts/{accountId}/generations/{generation}/events",
                                                 String.class,
                                                 accountId.toString(),
                                                 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"generation\":1");
        assertThat(response.getBody()).contains("\"streamAggregateId\"");
        assertThat(response.getBody()).contains("\"events\"");
        assertThat(response.getBody()).contains("owner-admin");
        assertThat(response.getBody()).contains("3000");
        assertThat(response.getBody()).contains("trade-admin-events-1");
        assertThat(response.getBody()).contains("2026-04");
    }

    @Test
    void admin_endpoint_can_archive_and_list_a_closed_generation() {
        var accountId = TradingAccountId.of("ACC-ADMIN-ARCHIVE-1");

        tradingAccountService.openAccount(accountId, "owner-archive", "2026-03");
        tradingAccountService.depositCash(accountId, BigDecimal.valueOf(3_000));
        tradingAccountService.applyTradeSettlement(accountId,
                                                  "trade-admin-archive-1",
                                                  BigDecimal.valueOf(-450),
                                                  BigDecimal.valueOf(25));
        tradingAccountService.closeBooksAndOpenNextPeriod(accountId, "2026-04");

        var archiveResponse = restTemplate.postForEntity("/api/admin/trading-accounts/{accountId}/generations/{generation}/archive",
                                                         null,
                                                         ApiArchivedGeneration.class,
                                                         accountId.toString(),
                                                         1);
        var listResponse = restTemplate.getForEntity("/api/admin/trading-accounts/{accountId}/archives",
                                                     ApiArchivedGeneration[].class,
                                                     accountId.toString());
        var archiveContentResponse = restTemplate.getForEntity("/api/admin/trading-accounts/{accountId}/generations/{generation}/archive-content",
                                                               String.class,
                                                               accountId.toString(),
                                                               1);

        assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(archiveResponse.getBody()).isNotNull();
        assertThat(archiveResponse.getBody().aggregateType()).isEqualTo(TradingDemoAggregateConfiguration.TRADING_ACCOUNTS.toString());
        assertThat(archiveResponse.getBody().logicalAggregateId()).isEqualTo(accountId.toString());
        assertThat(archiveResponse.getBody().generation()).isEqualTo(1);
        assertThat(archiveResponse.getBody().format()).isEqualTo("JSONL");
        assertThat(archiveResponse.getBody().status()).isEqualTo("ARCHIVED");
        assertThat(archiveResponse.getBody().archiveLocation()).startsWith("file:");
        assertThat(archiveResponse.getBody().eventCount()).isGreaterThan(0);
        assertThat(archiveResponse.getBody().checksum()).startsWith("sha256:");

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(Arrays.asList(listResponse.getBody()))
                .filteredOn(entry -> entry.generation() == 1)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo("ARCHIVED");
                    assertThat(entry.archiveLocation()).startsWith("file:");
                });

        assertThat(archiveContentResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(archiveContentResponse.getBody()).isNotNull();
        assertThat(archiveContentResponse.getBody()).contains("\"logicalAggregateId\":\"" + accountId + "\"");
        assertThat(archiveContentResponse.getBody()).contains("\"generation\":1");
        assertThat(archiveContentResponse.getBody()).contains("\"streamAggregateId\":\"" + accountId + "#1\"");
        assertThat(archiveContentResponse.getBody()).contains("\"aggregateType\":\"TradingAccounts\"");
        assertThat(archiveContentResponse.getBody()).contains("\"eventTypeOrName\"");
    }

    @Test
    void admin_endpoint_can_update_time_boundary_configuration_for_demoing() {
        var initialResponse = restTemplate.getForEntity("/api/admin/trading-accounts/closing-books",
                                                        TradingAccountClosingBooksConfigurationView.class);

        assertThat(initialResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(initialResponse.getBody()).isNotNull();
        assertThat(initialResponse.getBody().timeBoundary()).isEqualTo("end-of-month");
        assertThat(initialResponse.getBody().zoneId()).isEqualTo("Europe/Copenhagen");

        var modeResponse = restTemplate.postForEntity("/api/admin/trading-accounts/closing-books/mode?value=time-boundary",
                                                      null,
                                                      TradingAccountClosingBooksConfigurationView.class);

        assertThat(modeResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(modeResponse.getBody()).isNotNull();
        assertThat(modeResponse.getBody().mode()).isEqualTo("time-boundary");

        var boundaryResponse = restTemplate.postForEntity("/api/admin/trading-accounts/closing-books/time-boundary?value=end-of-week",
                                                          null,
                                                          TradingAccountClosingBooksConfigurationView.class);
        var zoneResponse = restTemplate.postForEntity("/api/admin/trading-accounts/closing-books/zone-id?value=UTC",
                                                      null,
                                                      TradingAccountClosingBooksConfigurationView.class);

        assertThat(boundaryResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(boundaryResponse.getBody()).isNotNull();
        assertThat(boundaryResponse.getBody().mode()).isEqualTo("time-boundary");
        assertThat(boundaryResponse.getBody().timeBoundary()).isEqualTo("end-of-week");
        assertThat(zoneResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(zoneResponse.getBody()).isNotNull();
        assertThat(zoneResponse.getBody().zoneId()).isEqualTo("UTC");
        assertThat(zoneResponse.getBody().description()).contains("end-of-week").contains("UTC");

        var dashboardResponse = restTemplate.getForEntity("/api/admin/dashboard", DashboardSummaryView.class);
        assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(dashboardResponse.getBody()).isNotNull();
        assertThat(dashboardResponse.getBody().closingBooks().timeBoundary()).isEqualTo("end-of-week");
        assertThat(dashboardResponse.getBody().closingBooks().zoneId()).isEqualTo("UTC");
    }

    @Test
    void admin_endpoints_expose_trade_valuation_and_settlement_lifecycle() {
        var accountId = TradingAccountId.of("ACC-ADMIN-TRADE-1");
        var instrumentId = InstrumentId.of("NVDA");
        var tradeId = TradeId.of("TRD-ADMIN-1");
        var settlementId = SettlementId.of("SET-ADMIN-1");

        tradingAccountService.openAccount(accountId, "owner-trade-admin", "2026-03");
        instrumentService.registerInstrument(instrumentId, "NVDA", "NVIDIA Corporation");
        instrumentPriceService.initializePrice(instrumentId, BigDecimal.valueOf(500));
        tradeService.placeTrade(tradeId,
                                accountId,
                                instrumentId,
                                "BUY",
                                BigDecimal.valueOf(2),
                                BigDecimal.valueOf(500));
        tradeService.executeTrade(tradeId);
        tradeService.requestSettlement(tradeId, settlementId.toString());
        instrumentPriceService.updatePrice(instrumentId, BigDecimal.valueOf(540));

        settlementService.createSettlement(settlementId,
                                           tradeId.toString(),
                                           accountId.toString(),
                                           BigDecimal.valueOf(1_000));
        settlementService.requestClearing(settlementId);
        settlementService.confirmClearing(settlementId);
        settlementService.markSettled(settlementId);
        settlementService.reconcile(settlementId);
        settlementService.closeSettlement(settlementId);
        tradeService.markSettled(tradeId);

        var tradeResponse = restTemplate.getForEntity("/api/admin/trades/{tradeId}",
                                                      TradeAdminView.class,
                                                      tradeId.toString());
        var settlementResponse = restTemplate.getForEntity("/api/admin/settlements/{settlementId}",
                                                           SettlementAdminView.class,
                                                           settlementId.toString());

        assertThat(tradeResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(tradeResponse.getBody()).isNotNull();
        assertThat(tradeResponse.getBody().tradeId()).isEqualTo(tradeId.toString());
        assertThat(tradeResponse.getBody().instrumentId()).isEqualTo(instrumentId.toString());
        assertThat(tradeResponse.getBody().latestMarketPrice()).isEqualByComparingTo("540");
        assertThat(tradeResponse.getBody().marketValue()).isEqualByComparingTo("1080");
        assertThat(tradeResponse.getBody().unrealizedPnl()).isEqualByComparingTo("80");
        assertThat(tradeResponse.getBody().settled()).isTrue();

        assertThat(settlementResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(settlementResponse.getBody()).isNotNull();
        assertThat(settlementResponse.getBody().settlementId()).isEqualTo(settlementId.toString());
        assertThat(settlementResponse.getBody().tradeId()).isEqualTo(tradeId.toString());
        assertThat(settlementResponse.getBody().clearingConfirmed()).isTrue();
        assertThat(settlementResponse.getBody().reconciled()).isTrue();
        assertThat(settlementResponse.getBody().closed()).isTrue();
    }

    @Test
    void load_generator_status_endpoint_is_exposed() {
        var response = restTemplate.getForEntity("/api/admin/load-generator",
                                                 TradingLoadGeneratorStatusView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().enabled()).isFalse();
        assertThat(response.getBody().started()).isFalse();
        assertThat(response.getBody().pendingSettlementCount()).isZero();
        assertThat(response.getBody().priceStressRunning()).isFalse();
    }

    @Test
    void load_generator_can_be_started_and_stopped_manually() {
        var startResponse = restTemplate.exchange("/api/admin/load-generator/start",
                                                  HttpMethod.POST,
                                                  null,
                                                  TradingLoadGeneratorStatusView.class);
        var stopResponse = restTemplate.exchange("/api/admin/load-generator/stop",
                                                 HttpMethod.POST,
                                                 null,
                                                 TradingLoadGeneratorStatusView.class);

        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(startResponse.getBody()).isNotNull();
        assertThat(startResponse.getBody().started()).isTrue();

        assertThat(stopResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(stopResponse.getBody()).isNotNull();
        assertThat(stopResponse.getBody().started()).isFalse();
    }

    @Test
    void dashboard_summary_and_html_are_exposed() {
        tradingAccountService.tryLoad(TradingAccountId.of("ACC-DEMO-001"))
                             .orElseGet(() -> tradingAccountService.openAccount(TradingAccountId.of("ACC-DEMO-001"),
                                                                                "dashboard-owner",
                                                                                "2026-03"));

        var summaryResponse = restTemplate.getForEntity("/api/admin/dashboard", DashboardSummaryView.class);
        var htmlResponse = restTemplate.getForEntity("/admin", String.class);

        assertThat(summaryResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(summaryResponse.getBody()).isNotNull();
        assertThat(summaryResponse.getBody().configuredAccountCount()).isEqualTo(3);
        assertThat(summaryResponse.getBody().accountsPresent()).isGreaterThanOrEqualTo(1);
        assertThat(summaryResponse.getBody().closingBooks().totalGenerations()).isGreaterThanOrEqualTo(1);
        assertThat(summaryResponse.getBody().pricePathComparison().performances()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(summaryResponse.getBody().snapshotStats().aggregateTypes()).contains(TradingDemoAggregateConfiguration.TRADING_ACCOUNTS.toString(),
                                                                                        TradingDemoAggregateConfiguration.INSTRUMENT_PRICES.toString());
        assertThat(summaryResponse.getBody().snapshotStats().saveCount()).isGreaterThanOrEqualTo(0);
        assertThat(summaryResponse.getBody().snapshotMetrics())
                .as("snapshot metrics are reported per snapshotting aggregate type, not just for TradingAccounts")
                .extracting(DashboardMetricSummaryView::aggregateType)
                .contains(TradingDemoAggregateConfiguration.INSTRUMENT_PRICES.toString());

        assertThat(htmlResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(htmlResponse.getHeaders().getContentType()).isNotNull();
        assertThat(htmlResponse.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(htmlResponse.getBody()).contains("Essentials Trading Demo");
        assertThat(htmlResponse.getBody()).contains("/api/admin/dashboard");
        assertThat(htmlResponse.getBody()).contains("/api/admin/dashboard/stream");
        assertThat(htmlResponse.getBody()).contains("Avg generations/account");
        assertThat(htmlResponse.getBody()).contains("snapshot saves");
        assertThat(htmlResponse.getBody()).contains("Price Path Comparison");
        assertThat(htmlResponse.getBody()).contains("aggregate-event-sourced");
        assertThat(htmlResponse.getBody()).contains("direct-write");
        assertThat(htmlResponse.getBody()).contains("Realistic Feed");
        assertThat(htmlResponse.getBody()).contains("Fast Stress");
        assertThat(htmlResponse.getBody()).contains("Max Throughput");
        assertThat(htmlResponse.getBody()).contains("Run Price Path Comparison");
    }

    @Test
    void async_price_stress_endpoint_starts_background_run() throws Exception {
        for (int index = 1; index <= 3; index++) {
            var accountId = TradingAccountId.of("ACC-DEMO-%03d".formatted(index));
            var ownerId = "price-stress-owner-" + index;
            tradingAccountService.tryLoad(accountId)
                                 .orElseGet(() -> tradingAccountService.openAccount(accountId,
                                                                                     ownerId,
                                                                                     "2026-03"));
        }
        instrumentService.tryLoad(InstrumentId.of("AAPL"))
                         .orElseGet(() -> instrumentService.registerInstrument(InstrumentId.of("AAPL"), "AAPL", "Apple Inc."));
        instrumentPriceService.tryLoad(InstrumentId.of("AAPL"))
                              .orElseGet(() -> instrumentPriceService.initializePrice(InstrumentId.of("AAPL"), BigDecimal.valueOf(500)));
        instrumentService.tryLoad(InstrumentId.of("MSFT"))
                         .orElseGet(() -> instrumentService.registerInstrument(InstrumentId.of("MSFT"), "MSFT", "Microsoft Corporation"));
        instrumentPriceService.tryLoad(InstrumentId.of("MSFT"))
                              .orElseGet(() -> instrumentPriceService.initializePrice(InstrumentId.of("MSFT"), BigDecimal.valueOf(510)));

        var startResponse = restTemplate.exchange("/api/admin/load-generator/price-stress/start?count=5&intervalMs=1",
                                                  HttpMethod.POST,
                                                  null,
                                                  TradingLoadGeneratorStatusView.class);

        assertThat(startResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(startResponse.getBody()).isNotNull();
        assertThat(startResponse.getBody().priceStressRequestedCount()).isEqualTo(5);
        assertThat(startResponse.getBody().priceStressIntervalMillis()).isEqualTo(1);

        TradingLoadGeneratorStatusView completedStatus = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            Thread.sleep(25);
            completedStatus = restTemplate.getForEntity("/api/admin/load-generator",
                                                        TradingLoadGeneratorStatusView.class).getBody();
            if (completedStatus != null
                && !completedStatus.priceStressRunning()
                && completedStatus.priceStressCompletedCount() >= 5) {
                break;
            }
        }

        assertThat(completedStatus).isNotNull();
        assertThat(completedStatus.priceStressRunning()).isFalse();
        assertThat(completedStatus.priceStressCompletedCount()).isGreaterThanOrEqualTo(5);
        assertThat(completedStatus.latestPrices()).isNotEmpty();
    }

    @Test
    void load_generator_burst_endpoints_generate_usage_on_demand() {
        for (int index = 1; index <= 3; index++) {
            var accountId = TradingAccountId.of("ACC-DEMO-%03d".formatted(index));
            var ownerId = "burst-owner-" + index;
            tradingAccountService.tryLoad(accountId)
                                 .orElseGet(() -> tradingAccountService.openAccount(accountId,
                                                                                     ownerId,
                                                                                     "2026-03"));
        }
        instrumentService.tryLoad(InstrumentId.of("AAPL"))
                         .orElseGet(() -> instrumentService.registerInstrument(InstrumentId.of("AAPL"), "AAPL", "Apple Inc."));
        instrumentPriceService.tryLoad(InstrumentId.of("AAPL"))
                              .orElseGet(() -> instrumentPriceService.initializePrice(InstrumentId.of("AAPL"), BigDecimal.valueOf(500)));
        instrumentService.tryLoad(InstrumentId.of("MSFT"))
                         .orElseGet(() -> instrumentService.registerInstrument(InstrumentId.of("MSFT"), "MSFT", "Microsoft Corporation"));
        instrumentPriceService.tryLoad(InstrumentId.of("MSFT"))
                              .orElseGet(() -> instrumentPriceService.initializePrice(InstrumentId.of("MSFT"), BigDecimal.valueOf(510)));

        ResponseEntity<TradingLoadGeneratorStatusView> priceBurstResponse = restTemplate.exchange(
                "/api/admin/load-generator/burst/price-updates?count=3",
                HttpMethod.POST,
                null,
                TradingLoadGeneratorStatusView.class);
        ResponseEntity<TradingLoadGeneratorStatusView> pendingTradeBurstResponse = restTemplate.exchange(
                "/api/admin/load-generator/burst/trades?count=2",
                HttpMethod.POST,
                null,
                TradingLoadGeneratorStatusView.class);
        ResponseEntity<TradingLoadGeneratorStatusView> settlementBurstResponse = restTemplate.exchange(
                "/api/admin/load-generator/burst/settlements?count=2",
                HttpMethod.POST,
                null,
                TradingLoadGeneratorStatusView.class);
        ResponseEntity<TradingLoadGeneratorStatusView> lifecycleBurstResponse = restTemplate.exchange(
                "/api/admin/load-generator/burst/trade-lifecycles?count=1",
                HttpMethod.POST,
                null,
                TradingLoadGeneratorStatusView.class);

        assertThat(priceBurstResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(priceBurstResponse.getBody()).isNotNull();
        assertThat(priceBurstResponse.getBody().generatedPriceUpdateCount()).isGreaterThanOrEqualTo(3);
        assertThat(priceBurstResponse.getBody().latestPriceInstrumentId()).isNotBlank();

        assertThat(pendingTradeBurstResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(pendingTradeBurstResponse.getBody()).isNotNull();
        assertThat(pendingTradeBurstResponse.getBody().generatedTradeCount()).isGreaterThanOrEqualTo(2);
        assertThat(pendingTradeBurstResponse.getBody().pendingSettlementCount()).isGreaterThanOrEqualTo(2);
        assertThat(pendingTradeBurstResponse.getBody().latestTradeId()).startsWith("TRD-LIVE-");
        assertThat(pendingTradeBurstResponse.getBody().latestSettlementId()).endsWith("-SET");

        assertThat(settlementBurstResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(settlementBurstResponse.getBody()).isNotNull();
        assertThat(settlementBurstResponse.getBody().generatedSettlementCount()).isGreaterThanOrEqualTo(2);
        assertThat(settlementBurstResponse.getBody().pendingSettlementCount()).isZero();

        assertThat(lifecycleBurstResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(lifecycleBurstResponse.getBody()).isNotNull();
        assertThat(lifecycleBurstResponse.getBody().generatedTradeCount()).isGreaterThanOrEqualTo(3);
        assertThat(lifecycleBurstResponse.getBody().generatedSettlementCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void price_path_comparison_scenario_endpoint_compares_both_modes() {
        for (int index = 1; index <= 3; index++) {
            var accountId = TradingAccountId.of("ACC-DEMO-%03d".formatted(index));
            var ownerId = "comparison-owner-" + index;
            tradingAccountService.tryLoad(accountId)
                                 .orElseGet(() -> tradingAccountService.openAccount(accountId,
                                                                                     ownerId,
                                                                                     "2026-03"));
        }
        instrumentService.tryLoad(InstrumentId.of("AAPL"))
                         .orElseGet(() -> instrumentService.registerInstrument(InstrumentId.of("AAPL"), "AAPL", "Apple Inc."));
        instrumentPriceService.tryLoad(InstrumentId.of("AAPL"))
                              .orElseGet(() -> instrumentPriceService.initializePrice(InstrumentId.of("AAPL"), BigDecimal.valueOf(500)));
        instrumentService.tryLoad(InstrumentId.of("MSFT"))
                         .orElseGet(() -> instrumentService.registerInstrument(InstrumentId.of("MSFT"), "MSFT", "Microsoft Corporation"));
        instrumentPriceService.tryLoad(InstrumentId.of("MSFT"))
                              .orElseGet(() -> instrumentPriceService.initializePrice(InstrumentId.of("MSFT"), BigDecimal.valueOf(510)));

        var response = restTemplate.exchange("/api/admin/load-generator/comparisons/price-path?count=5",
                                             HttpMethod.POST,
                                             null,
                                             PricePathScenarioResultView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().available()).isTrue();
        assertThat(response.getBody().requestedCount()).isEqualTo(5);
        assertThat(response.getBody().aggregateEventSourced()).isNotNull();
        assertThat(response.getBody().directWrite()).isNotNull();
        assertThat(response.getBody().aggregateEventSourced().completedCount()).isEqualTo(5);
        assertThat(response.getBody().directWrite().completedCount()).isEqualTo(5);
        assertThat(response.getBody().winnerMode()).isNotBlank();

        var dashboardResponse = restTemplate.getForEntity("/api/admin/dashboard", DashboardSummaryView.class);
        assertThat(dashboardResponse.getBody()).isNotNull();
        assertThat(dashboardResponse.getBody().latestPricePathScenario().available()).isTrue();
        assertThat(dashboardResponse.getBody().latestPricePathScenario().requestedCount()).isEqualTo(5);
    }

    @Test
    void trading_account_comparison_scenario_endpoint_compares_bootstrap_only_and_event_count() {
        var response = restTemplate.exchange("/api/admin/load-generator/comparisons/trading-account?count=12&readPasses=4&eventThreshold=4",
                                             HttpMethod.POST,
                                             null,
                                             TradingAccountScenarioResultView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().available()).isTrue();
        assertThat(response.getBody().requestedMutationCount()).isEqualTo(12);
        assertThat(response.getBody().readPasses()).isEqualTo(4);
        assertThat(response.getBody().eventThreshold()).isEqualTo(4);
        assertThat(response.getBody().bootstrapOnly()).isNotNull();
        assertThat(response.getBody().eventCount()).isNotNull();
        assertThat(response.getBody().bootstrapOnly().mode()).isEqualTo("manual-only");
        assertThat(response.getBody().eventCount().mode()).isEqualTo("event-count");
        assertThat(response.getBody().eventCount().rolledOverAccountCount()).isGreaterThan(0);
        assertThat(response.getBody().eventCount().totalGenerations()).isGreaterThan(response.getBody().bootstrapOnly().totalGenerations());

        var dashboardResponse = restTemplate.getForEntity("/api/admin/dashboard", DashboardSummaryView.class);
        assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(dashboardResponse.getBody()).isNotNull();
        assertThat(dashboardResponse.getBody().latestTradingAccountScenario()).isNotNull();
        assertThat(dashboardResponse.getBody().latestTradingAccountScenario().available()).isTrue();
    }

    @Test
    void projection_endpoints_expose_view_processor_and_event_processor_read_models() {
        var accountId = TradingAccountId.of("ACC-PROJECTION-1");
        var tradeId = TradeId.of("TRD-PROJECTION-1");
        var settlementId = SettlementId.of("SET-PROJECTION-1");
        var instrumentId = InstrumentId.of("INST-PROJECTION-1");

        tradingAccountService.openAccount(accountId, "owner-projection", "2026-04");
        tradingAccountService.depositCash(accountId, BigDecimal.valueOf(5_000));
        instrumentService.registerInstrument(instrumentId, "MSFT", "Microsoft Corporation");
        instrumentPriceService.initializePrice(instrumentId, BigDecimal.valueOf(320));

        tradeService.placeTrade(tradeId,
                                accountId,
                                instrumentId,
                                "BUY",
                                BigDecimal.valueOf(2),
                                BigDecimal.valueOf(320));
        tradeService.executeTrade(tradeId);
        tradeService.requestSettlement(tradeId, settlementId.toString());

        settlementService.createSettlement(settlementId,
                                           tradeId.toString(),
                                           accountId.toString(),
                                           BigDecimal.valueOf(640));
        settlementService.requestClearing(settlementId);
        settlementService.confirmClearing(settlementId);
        settlementService.markSettled(settlementId);
        settlementService.reconcile(settlementId);
        settlementService.closeSettlement(settlementId);
        tradeService.markSettled(tradeId);
        tradingAccountService.applyTradeSettlement(accountId,
                                                   tradeId.toString(),
                                                   BigDecimal.valueOf(-640),
                                                   BigDecimal.valueOf(12));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var accountProjectionResponse = restTemplate.getForEntity("/api/admin/projections/account-statements",
                                                                      TradingAccountStatementProjectionView[].class);
            var tradeProjectionResponse = restTemplate.getForEntity("/api/admin/projections/trade-settlements",
                                                                    TradeSettlementProjectionView[].class);

            assertThat(accountProjectionResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(tradeProjectionResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));

            var accountProjections = Arrays.asList(accountProjectionResponse.getBody());
            var tradeProjections = Arrays.asList(tradeProjectionResponse.getBody());

            assertThat(accountProjections)
                    .filteredOn(view -> view.logicalAccountId().equals(accountId.toString()))
                    .singleElement()
                    .satisfies(view -> {
                        assertThat(view.ownerId()).isEqualTo("owner-projection");
                        assertThat(view.periodId()).isEqualTo("2026-04");
                        assertThat(view.cashBalance()).isEqualByComparingTo("4360");
                        assertThat(view.realizedPnl()).isEqualByComparingTo("12");
                    });

            assertThat(tradeProjections)
                    .filteredOn(view -> view.tradeId().equals(tradeId.toString()))
                    .singleElement()
                    .satisfies(view -> {
                        assertThat(view.accountId()).isEqualTo(accountId.toString());
                        assertThat(view.instrumentId()).isEqualTo(instrumentId.toString());
                        assertThat(view.executed()).isTrue();
                        assertThat(view.settlementRequested()).isTrue();
                        assertThat(view.settled()).isTrue();
                        assertThat(view.settlementId()).isEqualTo(settlementId.toString());
                        assertThat(view.settlementStatus()).isEqualTo("CLOSED");
                    });
        });
    }
}
