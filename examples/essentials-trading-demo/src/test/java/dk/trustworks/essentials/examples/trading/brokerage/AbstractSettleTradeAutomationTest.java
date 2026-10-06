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

package dk.trustworks.essentials.examples.trading.brokerage;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Settlements;
import dk.trustworks.essentials.examples.trading.brokerage.types.*;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.deposit_cash.DepositCash;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.execute_trade.ExecuteTrade;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.open_trading_account.OpenTradingAccount;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.place_trade.PlaceTrade;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.settle_trade.SettleTradeProcessor;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountStatementQuery;
import dk.trustworks.essentials.examples.trading.brokerage.views.trade_settlement_status.TradeSettlementStatusQuery;
import dk.trustworks.essentials.examples.trading.market_data.types.*;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.initialize_price.InitializePrice;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.register_instrument.RegisterInstrument;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.Amount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end coverage of the {@code brokerage.settle_trade} automation: the harness only places and executes a trade, and
 * {@link SettleTradeProcessor} drives the settlement to the end, one command per event. The second half checks what the
 * automation is there to show in the admin console: the whole settlement is one causation chain, back to the
 * {@code TradeExecuted} that started it. Run once per {@code DurableQueues} implementation the demo can use, since the
 * automation's inbox is a durable queue - see the subclasses.
 */
abstract class AbstractSettleTradeAutomationTest {
    private static final Duration SETTLEMENT_TIMEOUT = Duration.ofSeconds(60);

    static PostgreSQLContainer<?> postgres(String databaseName) {
        return new PostgreSQLContainer<>("postgres:18.4")
                .withDatabaseName(databaseName)
                .withUsername("test")
                .withPassword("test");
    }

    static void registerDataSource(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private CommandBus                 commandBus;
    @Autowired
    private TradeSettlementStatusQuery tradeSettlementStatusQuery;
    @Autowired
    private AccountStatementQuery      accountStatementQuery;
    @Autowired
    private ConfigurableEventStore<?>  eventStore;

    @Test
    void an_executed_trade_is_settled_by_the_automation_as_one_causation_chain() {
        var accountId    = TradingAccountId.of("ACC-SETTLE-1");
        var tradeId      = TradeId.of("TRD-SETTLE-1");
        var settlementId = SettlementId.forTrade(tradeId);
        var instrumentId = InstrumentId.of("INST-SETTLE-1");

        commandBus.send(new OpenTradingAccount(accountId, OwnerId.of("owner-settle"), PeriodId.of("2026-03")));
        commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(1_500))));
        commandBus.send(new RegisterInstrument(instrumentId, Symbol.of("SETL"), "Settle Corp"));
        commandBus.send(new InitializePrice(instrumentId, Amount.of(BigDecimal.valueOf(500))));

        commandBus.send(new PlaceTrade(tradeId, accountId, instrumentId, TradeSide.BUY, Quantity.ONE, Amount.of(BigDecimal.valueOf(500))));
        commandBus.send(new ExecuteTrade(tradeId));

        await().atMost(SETTLEMENT_TIMEOUT).untilAsserted(() -> {
            var trade = tradeSettlementStatusQuery.tradeSettlements()
                                                  .stream()
                                                  .filter(status -> status.tradeId().equals(tradeId))
                                                  .findFirst();
            assertThat(trade).hasValueSatisfying(status -> {
                assertThat(status.settled()).isTrue();
                assertThat(status.settlementStatus()).isEqualTo(SettlementStatus.CLOSED);
            });
            assertThat(accountStatementQuery.findAccountOverview(accountId))
                    .hasValueSatisfying(statement -> assertThat(statement.cashBalance().value()).isEqualByComparingTo("1000"));
        });

        // Walk back from the settlement's last event: every step records the step that caused it
        var settlementEvents = eventStore.getUnitOfWorkFactory()
                                         .withUnitOfWork(() -> eventStore.fetchStream(Settlements.AGGREGATE_TYPE, settlementId).orElseThrow().eventList());
        var chain = causationChainFrom(settlementEvents.getLast());
        assertThat(chain).extracting(AbstractSettleTradeAutomationTest::simpleEventType)
                         .containsExactly("SettlementClosed",
                                          "SettlementReconciled",
                                          "SettlementMarkedSettled",
                                          "ClearingConfirmed",
                                          "ClearingRequested",
                                          "SettlementCreated",
                                          "SettlementRequested",
                                          "TradeExecuted");
        assertThat(chain.getLast().causedByEventId()).as("ExecuteTrade was sent by the test - nothing caused it").isEmpty();

        // ... and the settlement fanned out to three aggregates
        var markedSettled = chain.get(2);
        var effects = eventStore.getUnitOfWorkFactory()
                                .withUnitOfWork(() -> eventStore.loadEventsCausedBy(markedSettled.eventId()));
        assertThat(effects).extracting(AbstractSettleTradeAutomationTest::simpleEventType)
                           .containsExactlyInAnyOrder("SettlementReconciled", "TradeSettled", "TradeSettlementApplied");
    }

    private List<PersistedEvent> causationChainFrom(PersistedEvent event) {
        return eventStore.getUnitOfWorkFactory().withUnitOfWork(() -> {
            var chain = new ArrayList<PersistedEvent>();
            Optional<PersistedEvent> next = Optional.of(event);
            while (next.isPresent() && chain.size() < 20) {
                chain.add(next.get());
                next = next.get().causedByEventId().flatMap(eventStore::findEvent);
            }
            return chain;
        });
    }

    private static String simpleEventType(PersistedEvent event) {
        var type = event.event().getEventTypeOrNamePersistenceValue();
        return type.substring(type.lastIndexOf('.') + 1);
    }
}
