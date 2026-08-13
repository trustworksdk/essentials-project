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

import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiArchivedGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksGenerationRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.GenerationState;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.LogicalAggregateId;
import dk.trustworks.essentials.examples.trading._demo_harness.DashboardMetricSummaryView;
import dk.trustworks.essentials.examples.trading._demo_harness.DashboardSummaryView;
import dk.trustworks.essentials.examples.trading._demo_harness.PricePathScenarioResultView;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingAccountScenarioResultView;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingDemoSimulationProperties;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingLoadGeneratorStatusView;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingSimulationRunner;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementStatus;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.apply_trade_settlement.ApplyTradeSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_books_and_open_next_period.CloseBooksAndOpenNextPeriod;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_settlement.CloseSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.confirm_clearing.ConfirmClearing;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.create_settlement.CreateSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.deposit_cash.DepositCash;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.execute_trade.ExecuteTrade;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_settlement_settled.MarkSettlementSettled;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_trade_settled.MarkTradeSettled;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.open_trading_account.OpenTradingAccount;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.place_trade.PlaceTrade;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.reconcile_settlement.ReconcileSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_clearing.RequestClearing;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_settlement.RequestSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.reserve_funds.ReserveFunds;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.update_closing_books_settings.UpdateClosingBooksSettings;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountOverview;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountStatement;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountStatementQuery;
import dk.trustworks.essentials.examples.trading.brokerage.views.closing_books_configuration.ClosingBooksConfiguration;
import dk.trustworks.essentials.examples.trading.brokerage.views.trade_settlement_status.SettlementStatusView;
import dk.trustworks.essentials.examples.trading.brokerage.views.trade_settlement_status.TradeSettlementStatus;
import dk.trustworks.essentials.examples.trading.brokerage.views.trade_settlement_status.TradeSettlementStatusQuery;
import dk.trustworks.essentials.examples.trading.brokerage.views.trade_valuation.TradeValuation;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.MarketDataAggregateTypes;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.initialize_price.InitializePrice;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.register_instrument.RegisterInstrument;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.rename_instrument.RenameInstrument;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.update_price.UpdatePrice;
import dk.trustworks.essentials.examples.trading.market_data.views.instrument_details.InstrumentDetailsQuery;
import dk.trustworks.essentials.examples.trading.market_data.views.latest_price.LatestPriceQuery;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.Amount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The demo's end-to-end test, rewritten against the slice structure.
 *
 * <p>Two things changed shape and are worth knowing before reading an assertion:
 *
 * <ul>
 *   <li><b>Writes go on the command bus.</b> The five application services are gone; every intent is one command
 *       record sent through {@link CommandBus}, exactly as {@code TradingSimulationRunner} and the load harness do
 *       it. A command handler returns nothing useful in most slices, so nothing is asserted on the send.</li>
 *   <li><b>Reads go through view slices, and two of them are eventually consistent.</b> Aggregate state fields are
 *       private now, so the previous "mutate, reload the aggregate, assert" shape is not available and would have
 *       been the wrong shape anyway. Everything projection-backed is therefore awaited rather than read once --
 *       {@code account_statement}, {@code trade_settlement_status} and {@code trade_valuation} all catch up
 *       asynchronously.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(properties = {
        "trading-demo.simulation.enabled=false",
        "trading-demo.accounts.closing-books.event-threshold=100000",
        "essentials.eventstore.archives.enabled=true"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TradingDemoApplicationTest {
    /**
     * Generous on purpose: the three brokerage projections are driven by the event-store subscription manager over a
     * durable-queue inbox with a fenced lock, so how long they take to catch up is a function of polling intervals and
     * lock hand-over -- not of the assertion. A ceiling low enough to be "tight" would only make the suite flaky on a
     * loaded machine; it costs nothing on a healthy run, because every wait ends as soon as the condition holds.
     */
    private static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(60);

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
    private CommandBus commandBus;

    @Autowired
    private TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy;

    @Autowired
    private ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository;

    @Autowired
    private AccountStatementQuery accountStatementQuery;

    @Autowired
    private TradeSettlementStatusQuery tradeSettlementStatusQuery;

    @Autowired
    private LatestPriceQuery latestPriceQuery;

    @Autowired
    private InstrumentDetailsQuery instrumentDetailsQuery;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetClosingBooksPolicy() {
        // One atomic swap of the whole settings value - the four independent setters this replaces are what let a
        // concurrent change be silently lost.
        tradingAccountClosingBooksPolicy.update(settings -> settings.withMode(ClosingBooksDefaultPolicyType.MANUAL_ONLY)
                                                                    .withEventThreshold(100L)
                                                                    .withTimeBoundary(ClosingBooksTimeBoundary.END_OF_MONTH)
                                                                    .withZoneId(ZoneId.of("Europe/Copenhagen")));
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
    void commands_drive_the_full_trade_lifecycle_into_the_view_slices() {
        var accountId    = TradingAccountId.of("ACC-TEST-1");
        var tradeId      = TradeId.of("TRD-TEST-1");
        var settlementId = SettlementId.of("SET-TEST-1");
        var instrumentId = InstrumentId.of("INST-TEST-1");

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-test"), PeriodId.of("2026-03")));
        commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(1_500))));
        commandBus.send(new ReserveFunds(accountId, Amount.of(BigDecimal.valueOf(250))));

        commandBus.send(new RegisterInstrument(instrumentId, Symbol.of("ABC"), "Alpha Bravo"));
        commandBus.send(new RenameInstrument(instrumentId, "Alpha Bravo Updated"));
        commandBus.send(new InitializePrice(instrumentId, Amount.of(BigDecimal.valueOf(480))));

        commandBus.send(new PlaceTrade(tradeId,
                                       accountId,
                                       instrumentId,
                                       TradeSide.BUY,
                                       Quantity.ONE,
                                       Amount.of(BigDecimal.valueOf(500))));
        commandBus.send(new ExecuteTrade(tradeId));
        commandBus.send(new RequestSettlement(tradeId, settlementId));
        commandBus.send(new UpdatePrice(instrumentId, Amount.of(BigDecimal.valueOf(525))));

        commandBus.send(new CreateSettlement(settlementId, tradeId, accountId, Amount.of(BigDecimal.valueOf(500))));
        commandBus.send(new RequestClearing(settlementId));
        commandBus.send(new ConfirmClearing(settlementId));
        commandBus.send(new MarkSettlementSettled(settlementId));
        commandBus.send(new ReconcileSettlement(settlementId));
        commandBus.send(new CloseSettlement(settlementId));
        commandBus.send(new MarkTradeSettled(tradeId));

        commandBus.send(new ApplyTradeSettlement(accountId,
                                                 tradeId,
                                                 Amount.of(BigDecimal.valueOf(-500)),
                                                 Amount.of(BigDecimal.valueOf(42))));

        // The price aggregate is the authoritative latest price and is strongly consistent, so this one is not awaited.
        assertThat(latestPriceQuery.findLatestPrice(instrumentId))
                .hasValueSatisfying(latestPrice -> assertThat(latestPrice.latestPrice().value()).isEqualByComparingTo("525"));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            var statement = accountStatement(accountId);
            assertThat(statement).isNotNull();
            assertThat((CharSequence) statement.ownerId()).isEqualTo(OwnerId.of("owner-test"));
            assertThat(statement.cashBalance().value()).isEqualByComparingTo("1000");
            assertThat(statement.reservedFunds().value()).isEqualByComparingTo("250");
            assertThat(statement.realizedPnl().value()).isEqualByComparingTo("42");

            var tradeStatus = tradeSettlementStatus(tradeId);
            assertThat(tradeStatus).isNotNull();
            assertThat((CharSequence) tradeStatus.accountId()).isEqualTo(accountId);
            assertThat((CharSequence) tradeStatus.instrumentId()).isEqualTo(instrumentId);
            assertThat(tradeStatus.executed()).isTrue();
            assertThat(tradeStatus.settlementRequested()).isTrue();
            assertThat(tradeStatus.settled()).isTrue();
            assertThat((CharSequence) tradeStatus.settlementId()).isEqualTo(settlementId);
            assertThat(tradeStatus.settlementStatus()).isEqualTo(SettlementStatus.CLOSED);

            assertThat(tradeSettlementStatusQuery.findSettlement(settlementId))
                    .hasValueSatisfying(settlement -> {
                        assertThat((CharSequence) settlement.tradeId()).isEqualTo(tradeId);
                        assertThat(settlement.reconciled()).isTrue();
                        assertThat(settlement.closed()).isTrue();
                    });

            // The rename is only observable because market_data.instrument_details exists. Before that slice the
            // aggregate's fields were private and the context had no read side, so RenameInstrument could be sent
            // but never verified.
            assertThat(instrumentDetailsQuery.findInstrumentDetails(instrumentId))
                    .hasValueSatisfying(instrument -> {
                        assertThat((CharSequence) instrument.symbol()).isEqualTo(Symbol.of("ABC"));
                        assertThat(instrument.displayName()).isEqualTo("Alpha Bravo Updated");
                        assertThat(instrument.suspended()).isFalse();
                    });
        });
    }

    @Test
    void trading_account_can_roll_over_to_a_new_generation_without_exposing_stream_ids() {
        var accountId = TradingAccountId.of("ACC-ROLLOVER-1");

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-rollover"), PeriodId.of("2026-03")));
        commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(2_000))));
        commandBus.send(new ApplyTradeSettlement(accountId,
                                                 TradeId.of("trade-rollover-1"),
                                                 Amount.of(BigDecimal.valueOf(-250)),
                                                 Amount.of(BigDecimal.valueOf(15))));

        commandBus.send(new CloseBooksAndOpenNextPeriod(accountId, PeriodId.of("2026-04")));

        // The generation ledger is strongly consistent - it is written in the same unit of work as the rollover.
        var generations = tradingAccountGenerationRepository.loadGenerations(TradingAccounts.AGGREGATE_TYPE,
                                                                             new LogicalAggregateId<>(accountId));
        assertThat(generations).hasSize(2);
        assertThat(generations.get(0).state()).isEqualTo(GenerationState.CLOSED);
        assertThat(generations.get(1).state()).isEqualTo(GenerationState.OPEN);
        assertThat(generations.get(0).streamAggregateId()).isNotEqualTo(generations.get(1).streamAggregateId());

        // Cash carries across the rollover, realized P&L resets - asserted through the statement view, because the
        // aggregate's fields are private and a caller never sees the generation the numbers came from.
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            var statement = accountStatement(accountId);
            assertThat(statement).isNotNull();
            assertThat((CharSequence) statement.periodId()).isEqualTo(PeriodId.of("2026-04"));
            assertThat(statement.currentGeneration()).isEqualTo(2);
            assertThat(statement.generationCount()).isEqualTo(2);
            assertThat(statement.booksClosed()).isFalse();
            assertThat(statement.cashBalance().value()).isEqualByComparingTo("1750");
            assertThat(statement.realizedPnl().value()).isEqualByComparingTo("0");
        });
    }

    @Test
    void admin_endpoint_exposes_current_trading_account_and_generation_history() {
        var accountId = TradingAccountId.of("ACC-ADMIN-1");

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-admin"), PeriodId.of("2026-03")));
        commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(3_000))));
        commandBus.send(new ApplyTradeSettlement(accountId,
                                                 TradeId.of("trade-admin-1"),
                                                 Amount.of(BigDecimal.valueOf(-450)),
                                                 Amount.of(BigDecimal.valueOf(25))));
        commandBus.send(new CloseBooksAndOpenNextPeriod(accountId, PeriodId.of("2026-04")));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            var response = restTemplate.getForEntity("/api/admin/trading-accounts/{accountId}",
                                                     AccountOverview.class,
                                                     accountId.toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(response.getBody()).isNotNull();
            assertThat((CharSequence) response.getBody().logicalAccountId()).isEqualTo(accountId);
            assertThat((CharSequence) response.getBody().ownerId()).isEqualTo(OwnerId.of("owner-admin"));
            assertThat((CharSequence) response.getBody().currentStatementPeriod()).isEqualTo(PeriodId.of("2026-04"));
            assertThat(response.getBody().cashBalance().value()).isEqualByComparingTo("2550");
            assertThat(response.getBody().realizedPnl().value()).isEqualByComparingTo("0");
            assertThat(response.getBody().currentGeneration()).isEqualTo(2);
            assertThat(response.getBody().generations()).hasSize(2);
            assertThat(response.getBody().generations().get(0).state()).isEqualTo(GenerationState.CLOSED);
            assertThat(response.getBody().generations().get(1).state()).isEqualTo(GenerationState.OPEN);
        });
    }

    @Test
    void admin_endpoint_can_read_events_for_a_specific_generation() {
        var accountId = TradingAccountId.of("ACC-ADMIN-EVENTS-1");

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-admin"), PeriodId.of("2026-03")));
        commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(3_000))));
        commandBus.send(new ApplyTradeSettlement(accountId,
                                                 TradeId.of("trade-admin-events-1"),
                                                 Amount.of(BigDecimal.valueOf(-450)),
                                                 Amount.of(BigDecimal.valueOf(25))));
        commandBus.send(new CloseBooksAndOpenNextPeriod(accountId, PeriodId.of("2026-04")));

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

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-archive"), PeriodId.of("2026-03")));
        commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(3_000))));
        commandBus.send(new ApplyTradeSettlement(accountId,
                                                 TradeId.of("trade-admin-archive-1"),
                                                 Amount.of(BigDecimal.valueOf(-450)),
                                                 Amount.of(BigDecimal.valueOf(25))));
        commandBus.send(new CloseBooksAndOpenNextPeriod(accountId, PeriodId.of("2026-04")));

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
        assertThat(archiveResponse.getBody().aggregateType()).isEqualTo(TradingAccounts.AGGREGATE_TYPE.toString());
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

    /**
     * The four unguarded {@code ?value=} endpoints became one command slice taking the whole settings change as a
     * request body, so a partial retune is no longer expressible over HTTP either.
     */
    @Test
    void admin_endpoint_can_update_closing_books_configuration_for_demoing() {
        var initialResponse = restTemplate.getForEntity("/api/admin/trading-accounts/closing-books",
                                                        ClosingBooksConfiguration.class);

        assertThat(initialResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(initialResponse.getBody()).isNotNull();
        assertThat(initialResponse.getBody().mode()).isEqualTo("manual-only");
        assertThat(initialResponse.getBody().timeBoundary()).isEqualTo("end-of-month");
        assertThat(initialResponse.getBody().zoneId()).isEqualTo("Europe/Copenhagen");

        var modeResponse = restTemplate.postForEntity("/api/admin/trading-accounts/closing-books",
                                                      new UpdateClosingBooksSettings(ClosingBooksDefaultPolicyType.TIME_BOUNDARY,
                                                                                     null,
                                                                                     null,
                                                                                     null,
                                                                                     null),
                                                      Void.class);
        assertThat(modeResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(closingBooksConfiguration().mode()).isEqualTo("time-boundary");

        var boundaryAndZoneResponse = restTemplate.postForEntity("/api/admin/trading-accounts/closing-books",
                                                                 new UpdateClosingBooksSettings(null,
                                                                                                null,
                                                                                                ClosingBooksTimeBoundary.END_OF_WEEK,
                                                                                                ZoneId.of("UTC"),
                                                                                                null),
                                                                 Void.class);
        assertThat(boundaryAndZoneResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));

        var updatedConfiguration = closingBooksConfiguration();
        assertThat(updatedConfiguration.mode()).isEqualTo("time-boundary");
        assertThat(updatedConfiguration.timeBoundary()).isEqualTo("end-of-week");
        assertThat(updatedConfiguration.zoneId()).isEqualTo("UTC");
        assertThat(updatedConfiguration.description()).contains("end-of-week").contains("UTC");

        var dashboardResponse = restTemplate.getForEntity("/api/admin/dashboard", DashboardSummaryView.class);
        assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(dashboardResponse.getBody()).isNotNull();
        assertThat(dashboardResponse.getBody().closingBooks().timeBoundary()).isEqualTo("end-of-week");
        assertThat(dashboardResponse.getBody().closingBooks().zoneId()).isEqualTo("UTC");
    }

    /**
     * Regression: a trade projected <em>after</em> the price ticks must still be valued.
     *
     * <p>{@code TradeValuationProjection} subscribes to {@code Trades} and {@code InstrumentPrices} as two independent
     * subscriptions, and {@code GlobalEventOrder} sequences within one aggregate type rather than across two — so
     * either can be projected first. When the price won, {@code applyMarketPrice}'s {@code UPDATE} matched no rows and
     * the trade's market price stayed {@code null} <b>permanently</b>: nothing replays a consumed tick. Continuous demo
     * traffic masked it, because the next tick a second later repaired the row.
     *
     * <p>The first trade here exists only to prove the projection has consumed both price events. The second is the
     * assertion that matters — it is placed with no tick after it, so before the fix it could never acquire a price.
     */
    @Test
    void a_trade_projected_after_its_instruments_price_ticks_is_still_valued() {
        var accountId       = TradingAccountId.of("ACC-PRICE-FIRST-1");
        var instrumentId    = InstrumentId.of("SAP");
        var priceProbeTrade = TradeId.of("TRD-PRICE-FIRST-PROBE");
        var lateTrade       = TradeId.of("TRD-PRICE-FIRST-LATE");

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-price-first"), PeriodId.of("2026-03")));
        registerInstrumentWithPriceIfAbsent(instrumentId, "SAP", "SAP SE", Amount.of(BigDecimal.valueOf(400)));
        commandBus.send(new UpdatePrice(instrumentId, Amount.of(BigDecimal.valueOf(600))));

        commandBus.send(new PlaceTrade(priceProbeTrade,
                                       accountId,
                                       instrumentId,
                                       TradeSide.BUY,
                                       Quantity.of(1),
                                       Amount.of(BigDecimal.valueOf(500))));

        // Once this holds, the projection has consumed both price events for SAP.
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            var probe = restTemplate.getForEntity("/api/admin/trades/{tradeId}",
                                                  TradeValuation.class,
                                                  priceProbeTrade.toString());
            assertThat(probe.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(probe.getBody()).isNotNull();
            assertThat(probe.getBody().latestMarketPrice()).isNotNull();
            assertThat(probe.getBody().latestMarketPrice().value()).isEqualByComparingTo("600");
        });

        // No price tick follows this trade. Its market price can only come from what the slice already recorded.
        commandBus.send(new PlaceTrade(lateTrade,
                                       accountId,
                                       instrumentId,
                                       TradeSide.BUY,
                                       Quantity.of(2),
                                       Amount.of(BigDecimal.valueOf(500))));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            var late = restTemplate.getForEntity("/api/admin/trades/{tradeId}",
                                                 TradeValuation.class,
                                                 lateTrade.toString());
            assertThat(late.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(late.getBody()).isNotNull();
            assertThat(late.getBody().latestMarketPrice()).isNotNull();
            assertThat(late.getBody().latestMarketPrice().value()).isEqualByComparingTo("600");
            assertThat(late.getBody().marketValue().value()).isEqualByComparingTo("1200");
            assertThat(late.getBody().unrealizedPnl().value()).isEqualByComparingTo("200");
        });
    }

    @Test
    void admin_endpoints_expose_trade_valuation_and_settlement_lifecycle() {
        var accountId    = TradingAccountId.of("ACC-ADMIN-TRADE-1");
        var instrumentId = InstrumentId.of("NVDA");
        var tradeId      = TradeId.of("TRD-ADMIN-1");
        var settlementId = SettlementId.of("SET-ADMIN-1");

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-trade-admin"), PeriodId.of("2026-03")));
        registerInstrumentWithPriceIfAbsent(instrumentId, "NVDA", "NVIDIA Corporation", Amount.of(BigDecimal.valueOf(500)));
        commandBus.send(new PlaceTrade(tradeId,
                                       accountId,
                                       instrumentId,
                                       TradeSide.BUY,
                                       Quantity.of(2),
                                       Amount.of(BigDecimal.valueOf(500))));
        commandBus.send(new ExecuteTrade(tradeId));
        commandBus.send(new RequestSettlement(tradeId, settlementId));
        commandBus.send(new UpdatePrice(instrumentId, Amount.of(BigDecimal.valueOf(540))));

        commandBus.send(new CreateSettlement(settlementId, tradeId, accountId, Amount.of(BigDecimal.valueOf(1_000))));
        commandBus.send(new RequestClearing(settlementId));
        commandBus.send(new ConfirmClearing(settlementId));
        commandBus.send(new MarkSettlementSettled(settlementId));
        commandBus.send(new ReconcileSettlement(settlementId));
        commandBus.send(new CloseSettlement(settlementId));
        commandBus.send(new MarkTradeSettled(tradeId));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            var tradeResponse = restTemplate.getForEntity("/api/admin/trades/{tradeId}",
                                                          TradeValuation.class,
                                                          tradeId.toString());
            var settlementResponse = restTemplate.getForEntity("/api/admin/settlements/{settlementId}",
                                                               SettlementStatusView.class,
                                                               settlementId.toString());

            assertThat(tradeResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(tradeResponse.getBody()).isNotNull();
            assertThat((CharSequence) tradeResponse.getBody().tradeId()).isEqualTo(tradeId);
            assertThat((CharSequence) tradeResponse.getBody().instrumentId()).isEqualTo(instrumentId);
            assertThat(tradeResponse.getBody().latestMarketPrice()).isNotNull();
            assertThat(tradeResponse.getBody().latestMarketPrice().value()).isEqualByComparingTo("540");
            assertThat(tradeResponse.getBody().marketValue().value()).isEqualByComparingTo("1080");
            assertThat(tradeResponse.getBody().unrealizedPnl().value()).isEqualByComparingTo("80");
            assertThat(tradeResponse.getBody().settled()).isTrue();

            assertThat(settlementResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(settlementResponse.getBody()).isNotNull();
            assertThat((CharSequence) settlementResponse.getBody().settlementId()).isEqualTo(settlementId);
            assertThat((CharSequence) settlementResponse.getBody().tradeId()).isEqualTo(tradeId);
            assertThat(settlementResponse.getBody().clearingConfirmed()).isTrue();
            assertThat(settlementResponse.getBody().reconciled()).isTrue();
            assertThat(settlementResponse.getBody().closed()).isTrue();
        });
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
        openAccountIfAbsent(TradingAccountId.of("ACC-DEMO-001"), OwnerId.of("dashboard-owner"), PeriodId.of("2026-03"));

        // The dashboard's account list is projection-backed, so it can legitimately report zero accounts for a moment
        // after the command returns.
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            var summaryResponse = restTemplate.getForEntity("/api/admin/dashboard", DashboardSummaryView.class);

            assertThat(summaryResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(summaryResponse.getBody()).isNotNull();
            assertThat(summaryResponse.getBody().configuredAccountCount()).isEqualTo(3);
            assertThat(summaryResponse.getBody().accountsPresent()).isGreaterThanOrEqualTo(1);
            assertThat(summaryResponse.getBody().closingBooks().totalGenerations()).isGreaterThanOrEqualTo(1);
            assertThat(summaryResponse.getBody().pricePathComparison().performances()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(summaryResponse.getBody().snapshotStats().aggregateTypes()).contains(TradingAccounts.AGGREGATE_TYPE.toString(),
                                                                                            MarketDataAggregateTypes.INSTRUMENT_PRICES.toString());
            assertThat(summaryResponse.getBody().snapshotStats().saveCount()).isGreaterThanOrEqualTo(0);
            assertThat(summaryResponse.getBody().snapshotMetrics())
                    .as("snapshot metrics are reported per snapshotting aggregate type, not just for TradingAccounts")
                    .extracting(DashboardMetricSummaryView::aggregateType)
                    .contains(MarketDataAggregateTypes.INSTRUMENT_PRICES.toString());
        });

        var htmlResponse = restTemplate.getForEntity("/admin", String.class);

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
        seedLoadGeneratorFixtures("price-stress-owner-");

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
        seedLoadGeneratorFixtures("burst-owner-");

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
        assertThat((CharSequence) priceBurstResponse.getBody().latestPriceInstrumentId()).isNotNull();

        assertThat(pendingTradeBurstResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(pendingTradeBurstResponse.getBody()).isNotNull();
        assertThat(pendingTradeBurstResponse.getBody().generatedTradeCount()).isGreaterThanOrEqualTo(2);
        assertThat(pendingTradeBurstResponse.getBody().pendingSettlementCount()).isGreaterThanOrEqualTo(2);
        assertThat(pendingTradeBurstResponse.getBody().latestTradeId().toString()).startsWith("TRD-LIVE-");
        assertThat(pendingTradeBurstResponse.getBody().latestSettlementId().toString()).endsWith("-SET");

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
        seedLoadGeneratorFixtures("comparison-owner-");

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

        // The scenario overrides the policy through withTemporarySettings, which restores it afterwards.
        assertThat(tradingAccountClosingBooksPolicy.settings().mode()).isEqualTo(ClosingBooksDefaultPolicyType.MANUAL_ONLY);
        assertThat(tradingAccountClosingBooksPolicy.settings().eventThreshold()).isEqualTo(100L);

        var dashboardResponse = restTemplate.getForEntity("/api/admin/dashboard", DashboardSummaryView.class);
        assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(dashboardResponse.getBody()).isNotNull();
        assertThat(dashboardResponse.getBody().latestTradingAccountScenario()).isNotNull();
        assertThat(dashboardResponse.getBody().latestTradingAccountScenario().available()).isTrue();
    }

    @Test
    void projection_endpoints_expose_view_processor_and_event_processor_read_models() {
        var accountId    = TradingAccountId.of("ACC-PROJECTION-1");
        var tradeId      = TradeId.of("TRD-PROJECTION-1");
        var settlementId = SettlementId.of("SET-PROJECTION-1");
        var instrumentId = InstrumentId.of("INST-PROJECTION-1");

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-projection"), PeriodId.of("2026-04")));
        commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(5_000))));
        commandBus.send(new RegisterInstrument(instrumentId, Symbol.of("MSFT"), "Microsoft Corporation"));
        commandBus.send(new InitializePrice(instrumentId, Amount.of(BigDecimal.valueOf(320))));

        commandBus.send(new PlaceTrade(tradeId,
                                       accountId,
                                       instrumentId,
                                       TradeSide.BUY,
                                       Quantity.of(2),
                                       Amount.of(BigDecimal.valueOf(320))));
        commandBus.send(new ExecuteTrade(tradeId));
        commandBus.send(new RequestSettlement(tradeId, settlementId));

        commandBus.send(new CreateSettlement(settlementId, tradeId, accountId, Amount.of(BigDecimal.valueOf(640))));
        commandBus.send(new RequestClearing(settlementId));
        commandBus.send(new ConfirmClearing(settlementId));
        commandBus.send(new MarkSettlementSettled(settlementId));
        commandBus.send(new ReconcileSettlement(settlementId));
        commandBus.send(new CloseSettlement(settlementId));
        commandBus.send(new MarkTradeSettled(tradeId));
        commandBus.send(new ApplyTradeSettlement(accountId,
                                                 tradeId,
                                                 Amount.of(BigDecimal.valueOf(-640)),
                                                 Amount.of(BigDecimal.valueOf(12))));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            var accountProjectionResponse = restTemplate.getForEntity("/api/admin/projections/account-statements",
                                                                      AccountStatement[].class);
            var tradeProjectionResponse = restTemplate.getForEntity("/api/admin/projections/trade-settlements",
                                                                    TradeSettlementStatus[].class);

            assertThat(accountProjectionResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
            assertThat(tradeProjectionResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));

            var accountProjections = Arrays.asList(accountProjectionResponse.getBody());
            var tradeProjections   = Arrays.asList(tradeProjectionResponse.getBody());

            assertThat(accountProjections)
                    .filteredOn(view -> view.logicalAccountId().equals(accountId))
                    .singleElement()
                    .satisfies(view -> {
                        assertThat((CharSequence) view.ownerId()).isEqualTo(OwnerId.of("owner-projection"));
                        assertThat((CharSequence) view.periodId()).isEqualTo(PeriodId.of("2026-04"));
                        assertThat(view.cashBalance().value()).isEqualByComparingTo("4360");
                        assertThat(view.realizedPnl().value()).isEqualByComparingTo("12");
                    });

            assertThat(tradeProjections)
                    .filteredOn(view -> view.tradeId().equals(tradeId))
                    .singleElement()
                    .satisfies(view -> {
                        assertThat((CharSequence) view.accountId()).isEqualTo(accountId);
                        assertThat((CharSequence) view.instrumentId()).isEqualTo(instrumentId);
                        assertThat(view.executed()).isTrue();
                        assertThat(view.settlementRequested()).isTrue();
                        assertThat(view.settled()).isTrue();
                        assertThat((CharSequence) view.settlementId()).isEqualTo(settlementId);
                        assertThat(view.settlementStatus()).isEqualTo(SettlementStatus.CLOSED);
                    });
        });
    }

    private ClosingBooksConfiguration closingBooksConfiguration() {
        var response = restTemplate.getForEntity("/api/admin/trading-accounts/closing-books",
                                                 ClosingBooksConfiguration.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private AccountStatement accountStatement(TradingAccountId accountId) {
        return accountStatementQuery.accountStatements()
                                    .stream()
                                    .filter(statement -> statement.logicalAccountId().equals(accountId))
                                    .findFirst()
                                    .orElse(null);
    }

    private TradeSettlementStatus tradeSettlementStatus(TradeId tradeId) {
        return tradeSettlementStatusQuery.tradeSettlements()
                                         .stream()
                                         .filter(status -> status.tradeId().equals(tradeId))
                                         .findFirst()
                                         .orElse(null);
    }

    /**
     * The load harness needs the three demo accounts and two demo instruments to exist before it can generate against
     * them. It used to be spelled {@code service.tryLoad(...).orElseGet(...)}; the write side no longer exposes a
     * load, so existence is probed through the generation ledger and the price aggregate -- both strongly consistent,
     * unlike the statement projection.
     */
    private void seedLoadGeneratorFixtures(String ownerIdPrefix) {
        for (int index = 1; index <= 3; index++) {
            openAccountIfAbsent(TradingAccountId.of("ACC-DEMO-%03d".formatted(index)),
                                OwnerId.of(ownerIdPrefix + index),
                                PeriodId.of("2026-03"));
        }
        registerInstrumentWithPriceIfAbsent(InstrumentId.of("AAPL"), "AAPL", "Apple Inc.", Amount.of(BigDecimal.valueOf(500)));
        registerInstrumentWithPriceIfAbsent(InstrumentId.of("MSFT"), "MSFT", "Microsoft Corporation", Amount.of(BigDecimal.valueOf(510)));

        // The harness answers 503 "Demo seed data is not available yet" until its own probe can see ACC-DEMO-001, and
        // that probe reads the eventually consistent account_statement projection - so opening the account is not
        // enough, the projection has to have caught up before any burst or comparison endpoint is called.
        await().atMost(PROJECTION_TIMEOUT)
               .untilAsserted(() -> assertThat(accountStatement(TradingAccountId.of("ACC-DEMO-001"))).isNotNull());
    }

    private void openAccountIfAbsent(TradingAccountId accountId, OwnerId ownerId, PeriodId periodId) {
        var generations = tradingAccountGenerationRepository.loadGenerations(TradingAccounts.AGGREGATE_TYPE,
                                                                             new LogicalAggregateId<>(accountId));
        if (generations.isEmpty()) {
            commandBus.send(new OpenTradingAccount(accountId, ownerId, periodId));
        }
    }

    private void registerInstrumentWithPriceIfAbsent(InstrumentId instrumentId,
                                                     String symbol,
                                                     String displayName,
                                                     Amount initialPrice) {
        if (latestPriceQuery.findLatestPrice(instrumentId).isEmpty()) {
            commandBus.send(new RegisterInstrument(instrumentId, Symbol.of(symbol), displayName));
            commandBus.send(new InitializePrice(instrumentId, initialPrice));
        }
    }
}
