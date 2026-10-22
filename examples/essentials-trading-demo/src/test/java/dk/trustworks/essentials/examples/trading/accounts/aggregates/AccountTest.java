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

package dk.trustworks.essentials.examples.trading.accounts.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.DefaultAggregateSnapshotStateAdapter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * The ledger's rules, on the aggregate alone (design {@code docs/design/phase-1-account-core.md}).
 */
class AccountTest {
    private static final AccountId    ACCOUNT    = AccountId.of("ACC-1");
    private static final InstrumentId ACME       = InstrumentId.of("ACME");
    private static final LocalDate    TRADE_DATE = LocalDate.of(2030, 1, 7);
    private static final LocalDate    SETTLES_ON = TRADE_DATE.plusDays(1);

    private static Money usd(String amount) {
        return Money.of(amount, CurrencyCode.USD);
    }

    private static Account openAccount() {
        return new Account(AccountGenerationId.of(ACCOUNT, 1),
                           ACCOUNT,
                           UserId.of("USR-1"),
                           CurrencyCode.USD,
                           PeriodId.of("2030-01-07"),
                           OpeningBalances.empty(usd("0")));
    }

    private static void assertMoney(Money actual, String expected) {
        assertThat(actual.compareTo(usd(expected))).as("%s should be %s", actual, expected).isZero();
    }

    @Test
    void the_worked_example_of_the_design_note() {
        var account = openAccount();

        // 1. A deposit completes; uncleared for its return window
        account.creditDeposit(DepositId.of("DEP-1"), usd("10000"), TRADE_DATE, TRADE_DATE.plusDays(3));
        assertMoney(account.buyingPower(), "10000");
        assertMoney(account.withdrawable(), "0");

        // 2. A buy order holds 10 x limit 101
        account.holdFunds(HoldId.of("ORD-1"), HoldPurpose.ORDER, usd("1010"));
        assertMoney(account.buyingPower(), "8990");

        // 3. It fills 10 @ 100.50: gross 1005 pending out, all of it taken from the hold
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(10), usd("100.50"), TRADE_DATE, SETTLES_ON, HoldId.of("ORD-1"), null);
        account.releaseFunds(HoldId.of("ORD-1"));
        assertMoney(account.buyingPower(), "8995");
        assertThat(account.sellable(ACME)).isEqualTo(Quantity.of(10));

        // 4. It settles
        account.settleTrade(ExecutionId.of("EX-1"));
        assertMoney(account.settledCash(), "8995");
        assertMoney(account.buyingPower(), "8995");
        assertThat(account.positionOf(ACME)).hasValueSatisfying(position -> {
            assertThat(position.settled()).isEqualTo(Quantity.of(10));
            assertThat(position.pendingIn()).isEqualTo(Quantity.ZERO);
            assertMoney(position.costBasis(), "1005");
        });
        assertMoney(account.withdrawable(), "0");

        // 5. The deposit clears
        account.clearDeposit(DepositId.of("DEP-1"));
        assertMoney(account.withdrawable(), "8995");

        assertThat(account.getUncommittedChanges().events)
                .hasExactlyElementsOfTypes(AccountOpened.class, DepositCredited.class, FundsHeld.class, TradeBooked.class,
                                           FundsReleased.class, TradeSettled.class, DepositCleared.class);
        var released = (FundsReleased) account.getUncommittedChanges().events.get(4);
        assertMoney(released.amount(), "5");
    }

    @Test
    void a_hold_beyond_buying_power_or_withdrawable_cash_is_refused() {
        var account = openAccount();
        account.creditDeposit(DepositId.of("DEP-1"), usd("1000"), TRADE_DATE, TRADE_DATE.plusDays(3));

        assertThatThrownBy(() -> account.holdFunds(HoldId.of("ORD-1"), HoldPurpose.ORDER, usd("1000.01")))
                .isInstanceOf(LedgerRuleViolation.class);
        // Uncleared money buys, but does not leave
        assertThatThrownBy(() -> account.holdFunds(HoldId.of("WDR-1"), HoldPurpose.WITHDRAWAL, usd("1")))
                .isInstanceOf(LedgerRuleViolation.class);
        account.holdFunds(HoldId.of("ORD-1"), HoldPurpose.ORDER, usd("1000"));
    }

    @Test
    void unsettled_sale_proceeds_buy_but_are_not_withdrawable() {
        var account = openAccount();
        account.adjustCash(AdjustmentId.of("ADJ-1"), usd("1000"), "opening credit", "operator");
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(10), usd("100"), TRADE_DATE, SETTLES_ON, null, null);
        account.settleTrade(ExecutionId.of("EX-1"));
        assertMoney(account.settledCash(), "0");

        account.bookTrade(ExecutionId.of("EX-2"), ACME, TradeSide.SELL, Quantity.of(10), usd("110"), TRADE_DATE, SETTLES_ON, null, null);

        assertMoney(account.buyingPower(), "1100");
        assertMoney(account.withdrawable(), "0");
        assertMoney(account.realizedPnl(), "100");
    }

    @Test
    void selling_uses_average_cost_and_selling_everything_leaves_no_residue() {
        var account = openAccount();
        account.adjustCash(AdjustmentId.of("ADJ-1"), usd("10000"), "opening credit", "operator");
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(3), usd("100"), TRADE_DATE, SETTLES_ON, null, null);
        account.bookTrade(ExecutionId.of("EX-2"), ACME, TradeSide.BUY, Quantity.of(3), usd("101"), TRADE_DATE, SETTLES_ON, null, null);
        // Average cost 100.50; sell 1 at 103
        account.bookTrade(ExecutionId.of("EX-3"), ACME, TradeSide.SELL, Quantity.of(1), usd("103"), TRADE_DATE, SETTLES_ON, null, null);
        assertMoney(account.realizedPnl(), "2.50");

        account.bookTrade(ExecutionId.of("EX-4"), ACME, TradeSide.SELL, Quantity.of(5), usd("99"), TRADE_DATE, SETTLES_ON, null, null);
        assertMoney(account.realizedPnl(), "-5");
        assertThat(account.positionOf(ACME)).hasValueSatisfying(position -> {
            assertThat(position.held()).isEqualTo(Quantity.ZERO);
            assertMoney(position.costBasis(), "0");
        });
    }

    @Test
    void shares_can_be_reserved_only_up_to_what_is_sellable() {
        var account = openAccount();
        account.adjustCash(AdjustmentId.of("ADJ-1"), usd("1000"), "opening credit", "operator");
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(5), usd("100"), TRADE_DATE, SETTLES_ON, null, null);

        account.reserveShares(ReservationId.of("SELL-1"), ACME, Quantity.of(3));
        assertThatThrownBy(() -> account.reserveShares(ReservationId.of("SELL-2"), ACME, Quantity.of(3)))
                .isInstanceOf(LedgerRuleViolation.class);

        account.bookTrade(ExecutionId.of("EX-2"), ACME, TradeSide.SELL, Quantity.of(2), usd("101"), TRADE_DATE, SETTLES_ON, null, ReservationId.of("SELL-1"));
        account.releaseShares(ReservationId.of("SELL-1"));
        assertThat(account.sellable(ACME)).isEqualTo(Quantity.of(3));
    }

    @Test
    void a_returned_deposit_after_spending_restricts_the_account_to_selling() {
        var account = openAccount();
        account.creditDeposit(DepositId.of("DEP-1"), usd("1000"), TRADE_DATE, TRADE_DATE.plusDays(3));
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(10), usd("100"), TRADE_DATE, SETTLES_ON, null, null);
        account.settleTrade(ExecutionId.of("EX-1"));

        account.returnDeposit(DepositId.of("DEP-1"), usd("1000"));

        assertThat(account.isRestricted()).isTrue();
        assertMoney(account.settledCash(), "-1000");
        assertThatThrownBy(() -> account.holdFunds(HoldId.of("ORD-2"), HoldPurpose.ORDER, usd("1")))
                .isInstanceOf(LedgerRuleViolation.class);
        account.reserveShares(ReservationId.of("SELL-1"), ACME, Quantity.of(10));
    }

    @Test
    void every_booking_is_applied_once_however_often_it_is_sent() {
        var account = openAccount();
        account.creditDeposit(DepositId.of("DEP-1"), usd("1000"), TRADE_DATE, TRADE_DATE.plusDays(3));
        account.creditDeposit(DepositId.of("DEP-1"), usd("1000"), TRADE_DATE, TRADE_DATE.plusDays(3));
        account.holdFunds(HoldId.of("ORD-1"), HoldPurpose.ORDER, usd("500"));
        account.holdFunds(HoldId.of("ORD-1"), HoldPurpose.ORDER, usd("500"));
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(1), usd("100"), TRADE_DATE, SETTLES_ON, HoldId.of("ORD-1"), null);
        account.settleTrade(ExecutionId.of("EX-1"));
        // Redelivered after it settled: the booking must not come back
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(1), usd("100"), TRADE_DATE, SETTLES_ON, HoldId.of("ORD-1"), null);
        account.settleTrade(ExecutionId.of("EX-1"));
        account.releaseFunds(HoldId.of("ORD-1"));
        account.releaseFunds(HoldId.of("ORD-1"));

        assertThat(account.getUncommittedChanges().events)
                .hasExactlyElementsOfTypes(AccountOpened.class, DepositCredited.class, FundsHeld.class, TradeBooked.class,
                                           TradeSettled.class, FundsReleased.class);
        assertMoney(account.settledCash(), "900");
    }

    @Test
    void a_returned_withdrawal_is_settled_cash_again_once() {
        var account = openAccount();
        account.creditDeposit(DepositId.of("DEP-1"), usd("1000"), TRADE_DATE, TRADE_DATE.plusDays(3));
        account.clearDeposit(DepositId.of("DEP-1"));
        account.holdFunds(HoldId.of("WDR-1"), HoldPurpose.WITHDRAWAL, usd("400"));
        account.withdrawCash(WithdrawalId.of("WDR-1"), HoldId.of("WDR-1"), usd("400"));
        assertMoney(account.withdrawable(), "600");

        account.creditWithdrawalReturn(WithdrawalId.of("WDR-1"), usd("400"));
        account.creditWithdrawalReturn(WithdrawalId.of("WDR-1"), usd("400")); // redelivered: nothing

        assertMoney(account.settledCash(), "1000");
        assertMoney(account.withdrawable(), "1000");
        assertThat(account.getUncommittedChanges().events).filteredOn(WithdrawalReturnCredited.class::isInstance).hasSize(1);
    }

    @Test
    void another_currency_is_refused() {
        var account = openAccount();
        assertThatThrownBy(() -> account.adjustCash(AdjustmentId.of("ADJ-1"), Money.of("1", CurrencyCode.EUR), "wrong", "operator"))
                .isInstanceOf(LedgerRuleViolation.class);
    }

    @Test
    void the_next_generation_opens_with_the_whole_open_state_and_can_settle_what_the_previous_one_booked() {
        var account = openAccount();
        account.creditDeposit(DepositId.of("DEP-1"), usd("1000"), TRADE_DATE, TRADE_DATE.plusDays(3));
        account.holdFunds(HoldId.of("ORD-1"), HoldPurpose.ORDER, usd("300"));
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(2), usd("100"), TRADE_DATE, SETTLES_ON, HoldId.of("ORD-1"), null);
        account.closeBooks(PeriodId.of("2030-01-08"));
        assertThatThrownBy(() -> account.settleTrade(ExecutionId.of("EX-1"))).isInstanceOf(LedgerRuleViolation.class);

        var next = new Account(AccountGenerationId.of(ACCOUNT, 2), ACCOUNT, account.ownerId(), account.currency(),
                               PeriodId.of("2030-01-08"), account.openStateForNextGeneration());

        assertMoney(next.buyingPower(), account.buyingPower().getAmount().toString());
        next.releaseFunds(HoldId.of("ORD-1"));
        next.settleTrade(ExecutionId.of("EX-1"));
        next.clearDeposit(DepositId.of("DEP-1"));
        assertMoney(next.settledCash(), "800");
        assertMoney(next.withdrawable(), "800");
        assertThat(next.getUncommittedChanges().events)
                .hasExactlyElementsOfTypes(AccountOpened.class, FundsReleased.class, TradeSettled.class, DepositCleared.class);
    }

    @Test
    void an_account_round_trips_through_the_snapshot_serializer() {
        var account = openAccount();
        account.creditDeposit(DepositId.of("DEP-1"), usd("1000"), TRADE_DATE, TRADE_DATE.plusDays(3));
        account.holdFunds(HoldId.of("ORD-1"), HoldPurpose.ORDER, usd("300"));
        account.bookTrade(ExecutionId.of("EX-1"), ACME, TradeSide.BUY, Quantity.of(2), usd("100"), TRADE_DATE, SETTLES_ON, HoldId.of("ORD-1"), null);
        account.reserveShares(ReservationId.of("SELL-1"), ACME, Quantity.of(1));
        // Exactly the path a snapshot takes: the adapter the snapshot store uses, over the canonical event serializer
        account.markChangesAsCommitted();
        var adapter = new DefaultAggregateSnapshotStateAdapter(EssentialsJSONEventSerializers.create());

        var json     = adapter.serializeSnapshotState(account);
        var restored = adapter.deserializeSnapshotState(json, Account.class, AccountGenerationId.of(ACCOUNT, 1), EventOrder.of(4));

        assertThat(adapter.serializeSnapshotState(restored)).isEqualTo(json);
        // settled 1000 - pending out 200 - remaining hold 100
        assertMoney(restored.buyingPower(), "700");
        assertThat(restored.sellable(ACME)).isEqualTo(Quantity.of(1));
    }

    @Test
    void a_close_that_overtakes_its_open_is_recorded_once_and_a_duplicate_close_stays_silent() {
        var account = openAccount();
        account.creditDeposit(DepositId.of("DEP-1"), usd("10000"), TRADE_DATE, TRADE_DATE.plusDays(3));

        // The reordering the trading demo's ledger load hit on the unfixed queue engine: a cycle's release and settle
        // delivered before its hold and booking
        account.releaseFunds(HoldId.of("H-1"));
        account.settleTrade(ExecutionId.of("X-1"));
        account.releaseFunds(HoldId.of("H-1")); // redelivered: reported once only
        account.holdFunds(HoldId.of("H-1"), HoldPurpose.ORDER, usd("1010"));
        account.bookTrade(ExecutionId.of("X-1"), ACME, TradeSide.BUY, Quantity.of(10), usd("100.50"), TRADE_DATE, SETTLES_ON, HoldId.of("H-1"), null);

        var events = account.getUncommittedChanges().events;
        assertThat(events).hasExactlyElementsOfTypes(AccountOpened.class, DepositCredited.class, UnknownReferenceIgnored.class,
                                                     UnknownReferenceIgnored.class, FundsHeld.class, TradeBooked.class);
        assertThat(events).filteredOn(UnknownReferenceIgnored.class::isInstance)
                          .extracting(event -> ((UnknownReferenceIgnored) event).kind())
                          .containsExactly(IgnoredCommandKind.HOLD_RELEASE, IgnoredCommandKind.TRADE_SETTLEMENT);
        // The consequence the event exists to make visible: the hold and the trade are left open
        assertMoney(account.buyingPower(), "8990");

        // A close of something opened and closed in this generation is an ordinary duplicate: silent
        account.releaseFunds(HoldId.of("H-1"));
        account.releaseFunds(HoldId.of("H-1"));
        assertThat(account.getUncommittedChanges().events).hasSize(7).last().isInstanceOf(FundsReleased.class);
    }
}
