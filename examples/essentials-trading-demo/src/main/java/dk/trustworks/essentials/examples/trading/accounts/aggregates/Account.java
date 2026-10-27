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

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.*;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A customer's trading account: the ledger of cash, positions, holds and reservations (design
 * {@code docs/design/phase-1-account-core.md} §3–§6). One generation per business day under closing books; snapshots
 * every 25 events (measured against 100 and 10: {@code _demo_harness/CLAUDE.md}, "Snapshot interval").
 *
 * <h2>Two kinds of command</h2>
 * <ul>
 *     <li><b>Requests</b> — hold funds, reserve shares — are checked against the ledger's figures and refused with a
 *     {@link LedgerRuleViolation} when they do not fit (§3.4).</li>
 *     <li><b>Facts</b> — a deposit credited or returned, a withdrawal or a trade completed, a trade settled — come from
 *     outside and are never refused for lack of funds. They can push settled cash below zero, which <em>restricts</em>
 *     the account: no new holds until it is positive again, sells still allowed.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * Every command names an external reference. A command that opens an item (a hold, a reservation, a deposit, a trade)
 * is a no-op when that reference is already open or was handled in this generation. A command that closes one changes
 * nothing when the reference is not open: silently for a duplicate (opened and closed in this generation), and with an
 * {@link UnknownReferenceIgnored} event otherwise — a close that overtook its open, which out-of-order delivery causes
 * and which would otherwise leave the item open for good without a trace. Open items are carried into the next
 * generation, closed ones are not (§5).
 *
 * <h2>Money</h2>
 * One currency per account, enforced on every amount. Average-cost bookkeeping: a buy adds its gross to the
 * position's cost basis; a sell removes the average cost of the shares sold and realizes the difference.
 */
@AggregateSnapshotPolicy(aggregateType = "Accounts",
                         mode = SnapshotExecutionMode.SYNC,
                         everyNEvents = 25)
@AggregateClosingBooksPolicy(aggregateType = "Accounts",
                             triggerMode = ClosingBooksTriggerMode.ON_ACCESS,
                             defaultPolicy = ClosingBooksDefaultPolicyType.TIME_BOUNDARY,
                             timeBoundary = ClosingBooksTimeBoundary.END_OF_DAY,
                             zoneId = "America/New_York")
public class Account extends AggregateRoot<AccountGenerationId, AccountEvent, Account> implements HasClosingBooksPeriodId {
    private static final int COST_SCALE = 6;

    private AccountId                            accountId;
    private UserId                               ownerId;
    private CurrencyCode                         currency;
    private PeriodId                             periodId;
    private boolean                              booksClosed;
    private Money                                settledCash;
    private Money                                pendingCashIn;
    private Money                                pendingCashOut;
    private Money                                realizedPnl;
    private Map<HoldId, Hold>                    holds             = new HashMap<>();
    private Map<ReservationId, Reservation>      reservations      = new HashMap<>();
    private Map<DepositId, UnclearedDeposit>     unclearedDeposits = new HashMap<>();
    private Map<ExecutionId, OpenTrade>          openTrades        = new HashMap<>();
    private Map<InstrumentId, PositionBalance>   positions         = new HashMap<>();
    /**
     * References handled in this generation, prefixed by kind ({@code EXEC:}, {@code HOLD:}, ...), so an opening command
     * redelivered after its item closed is still a no-op. Starts empty in every generation (§5).
     */
    private Set<String>                          handledReferences = new HashSet<>();

    /**
     * Only for the JSON deserializer that restores a snapshot: keeps Jackson 3 from picking a public constructor as an
     * implicit properties creator (see the demo's root {@code CLAUDE.md}). Load-bearing.
     */
    protected Account() {
    }

    /**
     * Used for rehydration
     */
    public Account(AccountGenerationId accountStreamId) {
        super(accountStreamId);
    }

    public Account(AccountGenerationId accountStreamId,
                   AccountId accountId,
                   UserId ownerId,
                   CurrencyCode currency,
                   PeriodId periodId,
                   OpeningBalances openingBalances) {
        this(accountStreamId);
        apply(new AccountOpened(accountStreamId, accountId, ownerId, currency, periodId, openingBalances));
    }

    // ------------------------------------------------------------------------------------------------ Cash

    public void adjustCash(AdjustmentId adjustmentId, Money amount, String reason, String adjustedBy) {
        assertBooksOpen();
        requireNonNull(adjustmentId, "No adjustmentId provided");
        requireCurrency(amount);
        if (handledReferences.contains(ref("ADJ", adjustmentId))) {
            return;
        }
        apply(new CashAdjusted(aggregateId(), accountId, adjustmentId, amount, reason, adjustedBy));
    }

    public void creditDeposit(DepositId depositId, Money amount, LocalDate businessDate, LocalDate clearsOn) {
        assertBooksOpen();
        requireNonNull(depositId, "No depositId provided");
        requireCurrency(amount);
        if (unclearedDeposits.containsKey(depositId) || handledReferences.contains(ref("DEP", depositId))) {
            return;
        }
        apply(new DepositCredited(aggregateId(), accountId, depositId, amount, businessDate, clearsOn));
    }

    public void clearDeposit(DepositId depositId) {
        assertBooksOpen();
        requireNonNull(depositId, "No depositId provided");
        var deposit = unclearedDeposits.get(depositId);
        if (deposit == null) {
            ignoreIfUnknown(IgnoredCommandKind.DEPOSIT_CLEARANCE, ref("DEP", depositId), depositId);
            return;
        }
        apply(new DepositCleared(aggregateId(), accountId, depositId, deposit.amount()));
    }

    public void returnDeposit(DepositId depositId, Money amount) {
        assertBooksOpen();
        requireNonNull(depositId, "No depositId provided");
        requireCurrency(amount);
        if (handledReferences.contains(ref("RET", depositId))) {
            return;
        }
        var stillUncleared    = unclearedDeposits.get(depositId);
        var unclearedReleased = stillUncleared == null ? zero() : min(stillUncleared.amount(), amount);
        apply(new DepositReturned(aggregateId(), accountId, depositId, amount, unclearedReleased));
    }

    public void holdFunds(HoldId holdId, HoldPurpose purpose, Money amount) {
        assertBooksOpen();
        requireNonNull(holdId, "No holdId provided");
        requireNonNull(purpose, "No purpose provided");
        requireCurrency(amount);
        if (holds.containsKey(holdId) || handledReferences.contains(ref("HOLD", holdId))) {
            return;
        }
        if (isRestricted()) {
            throw new LedgerRuleViolation("Account '" + accountId + "' is restricted (settled cash " + settledCash + "); no new holds until it is positive");
        }
        var available = purpose == HoldPurpose.ORDER ? buyingPower() : withdrawable();
        if (amount.compareTo(available) > 0) {
            throw new LedgerRuleViolation("Cannot hold " + amount + " for " + purpose + " on account '" + accountId + "': only " + available + " available");
        }
        apply(new FundsHeld(aggregateId(), accountId, holdId, purpose, amount));
    }

    public void releaseFunds(HoldId holdId) {
        assertBooksOpen();
        requireNonNull(holdId, "No holdId provided");
        var hold = holds.get(holdId);
        if (hold == null) {
            ignoreIfUnknown(IgnoredCommandKind.HOLD_RELEASE, ref("HOLD", holdId), holdId);
            return;
        }
        apply(new FundsReleased(aggregateId(), accountId, holdId, hold.remaining()));
    }

    public void withdrawCash(WithdrawalId withdrawalId, HoldId holdId, Money amount) {
        assertBooksOpen();
        requireNonNull(withdrawalId, "No withdrawalId provided");
        requireNonNull(holdId, "No holdId provided");
        requireCurrency(amount);
        if (handledReferences.contains(ref("WDR", withdrawalId))) {
            return;
        }
        var hold         = holds.get(holdId);
        var holdConsumed = hold == null ? zero() : min(hold.remaining(), amount);
        apply(new CashWithdrawn(aggregateId(), accountId, withdrawalId, holdId, amount, holdConsumed));
    }

    /**
     * The bank sent a completed withdrawal back: settled cash again. A fact - never refused, also on a restricted account.
     */
    public void creditWithdrawalReturn(WithdrawalId withdrawalId, Money amount) {
        assertBooksOpen();
        requireNonNull(withdrawalId, "No withdrawalId provided");
        requireCurrency(amount);
        if (handledReferences.contains(ref("WRET", withdrawalId))) {
            return;
        }
        apply(new WithdrawalReturnCredited(aggregateId(), accountId, withdrawalId, amount));
    }

    // ------------------------------------------------------------------------------------------------ Shares

    public void reserveShares(ReservationId reservationId, InstrumentId instrumentId, Quantity quantity) {
        assertBooksOpen();
        requireNonNull(reservationId, "No reservationId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(quantity, "No quantity provided");
        if (reservations.containsKey(reservationId) || handledReferences.contains(ref("RES", reservationId))) {
            return;
        }
        var sellable = sellable(instrumentId);
        if (quantity.isGreaterThan(sellable)) {
            throw new LedgerRuleViolation("Cannot reserve " + quantity + " of '" + instrumentId + "' on account '" + accountId + "': only " + sellable + " sellable");
        }
        apply(new SharesReserved(aggregateId(), accountId, reservationId, instrumentId, quantity));
    }

    public void releaseShares(ReservationId reservationId) {
        assertBooksOpen();
        requireNonNull(reservationId, "No reservationId provided");
        var reservation = reservations.get(reservationId);
        if (reservation == null) {
            ignoreIfUnknown(IgnoredCommandKind.SHARE_RELEASE, ref("RES", reservationId), reservationId);
            return;
        }
        apply(new SharesReleased(aggregateId(), accountId, reservationId, reservation.instrumentId(), reservation.remaining()));
    }

    // ------------------------------------------------------------------------------------------------ Trades

    /**
     * Books an execution as pending. A fact: never refused for funds or shares.
     *
     * @param holdId        the buy order's hold to consume from, or {@code null}
     * @param reservationId the sell order's reservation to consume from, or {@code null}
     */
    public void bookTrade(ExecutionId executionId,
                          InstrumentId instrumentId,
                          TradeSide side,
                          Quantity quantity,
                          Money price,
                          LocalDate tradeDate,
                          LocalDate settlementDate,
                          HoldId holdId,
                          ReservationId reservationId) {
        assertBooksOpen();
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireNonNull(quantity, "No quantity provided");
        requireCurrency(price);
        if (openTrades.containsKey(executionId) || handledReferences.contains(ref("EXEC", executionId))) {
            return;
        }
        var gross = Money.of(price.getAmount().value().multiply(BigDecimal.valueOf(quantity.value())), currency);
        OpenTrade trade;
        var holdConsumed        = zero();
        var reservationConsumed = Quantity.ZERO;
        if (side == TradeSide.BUY) {
            trade = new OpenTrade(executionId, instrumentId, side, quantity, gross, gross, zero(), tradeDate, settlementDate);
            var hold = holdId == null ? null : holds.get(holdId);
            if (hold != null) {
                holdConsumed = min(hold.remaining(), gross);
            }
        } else {
            var costOfSold = costOfSelling(instrumentId, quantity);
            trade = new OpenTrade(executionId, instrumentId, side, quantity, gross, costOfSold.negate(), gross.subtract(costOfSold), tradeDate, settlementDate);
            var reservation = reservationId == null ? null : reservations.get(reservationId);
            if (reservation != null) {
                reservationConsumed = reservation.remaining().min(quantity);
            }
        }
        apply(new TradeBooked(aggregateId(),
                              accountId,
                              trade,
                              price,
                              holdConsumed.signum() > 0 ? holdId : null,
                              holdConsumed,
                              reservationConsumed.isPositive() ? reservationId : null,
                              reservationConsumed));
    }

    public void settleTrade(ExecutionId executionId) {
        assertBooksOpen();
        requireNonNull(executionId, "No executionId provided");
        var trade = openTrades.get(executionId);
        if (trade == null) {
            ignoreIfUnknown(IgnoredCommandKind.TRADE_SETTLEMENT, ref("EXEC", executionId), executionId);
            return;
        }
        apply(new TradeSettled(aggregateId(), accountId, trade));
    }

    /**
     * The settlement of a trade was corrected after it settled: its settlement amount changed by {@code amountChange}
     * (new minus previous). A fact - never refused. A buy pays the change; while its shares are still held it changes
     * their cost basis, else the period's realized P&L. A sell receives it, as realized P&L.
     */
    public void correctSettlementAmount(ExecutionId executionId, InstrumentId instrumentId, TradeSide side, Money amountChange) {
        assertBooksOpen();
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(side, "No side provided");
        requireCurrency(amountChange);
        if (handledReferences.contains(ref("CORR", executionId)) || amountChange.signum() == 0) {
            return;
        }
        Money cashChange, costBasisChange, realizedPnlChange;
        if (side == TradeSide.BUY) {
            cashChange = amountChange.negate();
            var stillHeld = position(instrumentId).held().isPositive();
            costBasisChange = stillHeld ? amountChange : zero();
            realizedPnlChange = stillHeld ? zero() : amountChange.negate();
        } else {
            cashChange = amountChange;
            costBasisChange = zero();
            realizedPnlChange = amountChange;
        }
        apply(new SettlementAmountCorrected(aggregateId(), accountId, executionId, instrumentId, side, cashChange, costBasisChange, realizedPnlChange));
    }

    public void reverseTradeBooking(ExecutionId executionId, String reason) {
        assertBooksOpen();
        requireNonNull(executionId, "No executionId provided");
        requireNonNull(reason, "No reason provided");
        var trade = openTrades.get(executionId);
        if (trade == null) {
            ignoreIfUnknown(IgnoredCommandKind.TRADE_REVERSAL, ref("EXEC", executionId), executionId);
            return;
        }
        apply(new TradeBookingReversed(aggregateId(), accountId, trade, reason));
    }

    /**
     * A closing command found nothing open under its reference. A duplicate — the item was opened in this generation
     * and has been closed — stays silent; anything else is recorded once per kind and reference.
     */
    private void ignoreIfUnknown(IgnoredCommandKind kind, String openingReference, CharSequence reference) {
        if (handledReferences.contains(openingReference) || handledReferences.contains(ref("IGN:" + kind, reference))) {
            return;
        }
        apply(new UnknownReferenceIgnored(aggregateId(), accountId, kind, reference.toString()));
    }

    // ------------------------------------------------------------------------------------------------ Closing books

    public void closeBooks(PeriodId nextPeriodId) {
        requireNonNull(nextPeriodId, "No nextPeriodId provided");
        if (booksClosed) {
            return;
        }
        apply(new AccountBooksClosed(aggregateId(), accountId, nextPeriodId));
    }

    // ------------------------------------------------------------------------------------------------ Package-private reads

    /**
     * For {@link AccountNextGenerationFactory} only: the whole open state, which the next generation opens with.
     */
    OpeningBalances openStateForNextGeneration() {
        return new OpeningBalances(settledCash,
                                   pendingCashIn,
                                   pendingCashOut,
                                   List.copyOf(holds.values()),
                                   List.copyOf(unclearedDeposits.values()),
                                   List.copyOf(openTrades.values()),
                                   List.copyOf(positions.values()),
                                   List.copyOf(reservations.values()));
    }

    AccountId accountId() {
        return accountId;
    }

    UserId ownerId() {
        return ownerId;
    }

    CurrencyCode currency() {
        return currency;
    }

    /**
     * For {@link AccountClosingBooksPolicy}: the period this generation is open in.
     */
    PeriodId periodId() {
        return periodId;
    }

    /**
     * The framework's view of {@link #periodId()}. Required: with closing books enabled, the starter refuses to start
     * when a time-boundary aggregate does not expose its period this way.
     */
    @Override
    public String closingBooksPeriodId() {
        return periodId.toString();
    }

    // ------------------------------------------------------------------------------------------------ Derived figures (§3.3)
    // Package-private for AccountTest. Never read by a slice: the views project the same figures from the events.

    Money buyingPower() {
        return settledCash.add(pendingCashIn).subtract(pendingCashOut).subtract(totalHeld());
    }

    Money withdrawable() {
        var value = settledCash.subtract(pendingCashOut).subtract(totalHeld()).subtract(totalUncleared());
        return value.signum() < 0 ? zero() : value;
    }

    Quantity sellable(InstrumentId instrumentId) {
        var position = positions.get(instrumentId);
        var held     = position == null ? Quantity.ZERO : position.held();
        var reserved = reservations.values().stream()
                                   .filter(reservation -> reservation.instrumentId().equals(instrumentId))
                                   .map(Reservation::remaining)
                                   .reduce(Quantity.ZERO, Quantity::plus);
        return held.minus(reserved);
    }

    boolean isRestricted() {
        return settledCash.signum() < 0;
    }

    Money settledCash() {
        return settledCash;
    }

    Money realizedPnl() {
        return realizedPnl;
    }

    Optional<PositionBalance> positionOf(InstrumentId instrumentId) {
        return Optional.ofNullable(positions.get(instrumentId));
    }

    private Money totalHeld() {
        return holds.values().stream().map(Hold::remaining).reduce(zero(), Money::add);
    }

    private Money totalUncleared() {
        return unclearedDeposits.values().stream().map(UnclearedDeposit::amount).reduce(zero(), Money::add);
    }

    /**
     * Average cost of {@code quantity} shares out of what is held. Selling everything held takes the whole cost basis,
     * so no rounding residue is ever left on an empty position.
     */
    private Money costOfSelling(InstrumentId instrumentId, Quantity quantity) {
        var position = positions.get(instrumentId);
        if (position == null || !position.held().isPositive()) {
            return zero();
        }
        var held = position.held();
        if (!held.isGreaterThan(quantity)) {
            return position.costBasis();
        }
        var cost = position.costBasis().getAmount().value()
                           .multiply(BigDecimal.valueOf(quantity.value()))
                           .divide(BigDecimal.valueOf(held.value()), COST_SCALE, RoundingMode.HALF_EVEN);
        return Money.of(cost, currency);
    }

    private PositionBalance position(InstrumentId instrumentId) {
        return positions.getOrDefault(instrumentId, new PositionBalance(instrumentId, Quantity.ZERO, Quantity.ZERO, Quantity.ZERO, zero()));
    }

    private void storePosition(PositionBalance position) {
        if (position.isEmpty() && position.costBasis().signum() == 0) {
            positions.remove(position.instrumentId());
        } else {
            positions.put(position.instrumentId(), position);
        }
    }

    private void assertBooksOpen() {
        if (booksClosed) {
            throw new LedgerRuleViolation("The books of account '" + accountId + "' are closed for period '" + periodId + "'");
        }
    }

    private void requireCurrency(Money amount) {
        requireNonNull(amount, "No amount provided");
        if (!currency.equals(amount.getCurrency())) {
            throw new LedgerRuleViolation("Account '" + accountId + "' is in " + currency + ", not " + amount.getCurrency());
        }
    }

    private Money zero() {
        return Money.of(BigDecimal.ZERO, currency);
    }

    private static Money min(Money a, Money b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String ref(String kind, CharSequence reference) {
        return kind + ":" + reference;
    }

    // ------------------------------------------------------------------------------------------------ Event handlers

    @EventHandler
    private void on(AccountOpened e) {
        accountId = e.accountId();
        ownerId = e.ownerId();
        currency = e.currency();
        periodId = e.periodId();
        booksClosed = false;
        var opening = e.openingBalances();
        settledCash = opening.settledCash();
        pendingCashIn = opening.pendingCashIn();
        pendingCashOut = opening.pendingCashOut();
        realizedPnl = zero();
        holds = new HashMap<>();
        opening.holds().forEach(hold -> holds.put(hold.holdId(), hold));
        reservations = new HashMap<>();
        opening.reservations().forEach(reservation -> reservations.put(reservation.reservationId(), reservation));
        unclearedDeposits = new HashMap<>();
        opening.unclearedDeposits().forEach(deposit -> unclearedDeposits.put(deposit.depositId(), deposit));
        openTrades = new HashMap<>();
        opening.openTrades().forEach(trade -> openTrades.put(trade.executionId(), trade));
        positions = new HashMap<>();
        opening.positions().forEach(position -> positions.put(position.instrumentId(), position));
        handledReferences = new HashSet<>();
    }

    @EventHandler
    private void on(CashAdjusted e) {
        settledCash = settledCash.add(e.amount());
        handledReferences.add(ref("ADJ", e.adjustmentId()));
    }

    @EventHandler
    private void on(DepositCredited e) {
        settledCash = settledCash.add(e.amount());
        unclearedDeposits.put(e.depositId(), new UnclearedDeposit(e.depositId(), e.amount(), e.clearsOn()));
        handledReferences.add(ref("DEP", e.depositId()));
    }

    @EventHandler
    private void on(DepositCleared e) {
        unclearedDeposits.remove(e.depositId());
    }

    @EventHandler
    private void on(DepositReturned e) {
        settledCash = settledCash.subtract(e.amount());
        if (e.unclearedReleased().signum() > 0) {
            unclearedDeposits.remove(e.depositId());
        }
        handledReferences.add(ref("RET", e.depositId()));
    }

    @EventHandler
    private void on(FundsHeld e) {
        holds.put(e.holdId(), new Hold(e.holdId(), e.purpose(), e.amount()));
        handledReferences.add(ref("HOLD", e.holdId()));
    }

    @EventHandler
    private void on(FundsReleased e) {
        holds.remove(e.holdId());
    }

    @EventHandler
    private void on(CashWithdrawn e) {
        settledCash = settledCash.subtract(e.amount());
        var hold = holds.get(e.holdId());
        if (hold != null) {
            var remaining = hold.remaining().subtract(e.holdConsumed());
            if (remaining.signum() <= 0) {
                holds.remove(e.holdId());
            } else {
                holds.put(e.holdId(), hold.withRemaining(remaining));
            }
        }
        handledReferences.add(ref("WDR", e.withdrawalId()));
    }

    @EventHandler
    private void on(WithdrawalReturnCredited e) {
        settledCash = settledCash.add(e.amount());
        handledReferences.add(ref("WRET", e.withdrawalId()));
    }

    @EventHandler
    private void on(SharesReserved e) {
        reservations.put(e.reservationId(), new Reservation(e.reservationId(), e.instrumentId(), e.quantity()));
        handledReferences.add(ref("RES", e.reservationId()));
    }

    @EventHandler
    private void on(SharesReleased e) {
        reservations.remove(e.reservationId());
    }

    @EventHandler
    private void on(TradeBooked e) {
        var trade    = e.trade();
        var position = position(trade.instrumentId());
        if (trade.side() == TradeSide.BUY) {
            pendingCashOut = pendingCashOut.add(trade.grossAmount());
            position = new PositionBalance(position.instrumentId(), position.settled(), position.pendingIn().plus(trade.quantity()),
                                           position.pendingOut(), position.costBasis().add(trade.costBasisChange()));
        } else {
            pendingCashIn = pendingCashIn.add(trade.grossAmount());
            realizedPnl = realizedPnl.add(trade.realizedPnl());
            position = new PositionBalance(position.instrumentId(), position.settled(), position.pendingIn(),
                                           position.pendingOut().plus(trade.quantity()), position.costBasis().add(trade.costBasisChange()));
        }
        storePosition(position);
        // A consumed hold or reservation stays, possibly at zero, until its order releases it: FundsReleased and
        // SharesReleased are what close them
        if (e.holdId() != null) {
            holds.computeIfPresent(e.holdId(), (id, hold) -> hold.withRemaining(hold.remaining().subtract(e.holdConsumed())));
        }
        if (e.reservationId() != null) {
            reservations.computeIfPresent(e.reservationId(), (id, reservation) -> reservation.withRemaining(reservation.remaining().minus(e.reservationConsumed())));
        }
        openTrades.put(trade.executionId(), trade);
        handledReferences.add(ref("EXEC", trade.executionId()));
    }

    @EventHandler
    private void on(TradeSettled e) {
        var trade    = e.trade();
        var position = position(trade.instrumentId());
        if (trade.side() == TradeSide.BUY) {
            pendingCashOut = pendingCashOut.subtract(trade.grossAmount());
            settledCash = settledCash.subtract(trade.grossAmount());
            position = new PositionBalance(position.instrumentId(), position.settled().plus(trade.quantity()),
                                           position.pendingIn().minus(trade.quantity()), position.pendingOut(), position.costBasis());
        } else {
            pendingCashIn = pendingCashIn.subtract(trade.grossAmount());
            settledCash = settledCash.add(trade.grossAmount());
            position = new PositionBalance(position.instrumentId(), position.settled().minus(trade.quantity()),
                                           position.pendingIn(), position.pendingOut().minus(trade.quantity()), position.costBasis());
        }
        storePosition(position);
        openTrades.remove(trade.executionId());
    }

    @EventHandler
    private void on(SettlementAmountCorrected e) {
        settledCash = settledCash.add(e.cashChange());
        realizedPnl = realizedPnl.add(e.realizedPnlChange());
        if (e.costBasisChange().signum() != 0) {
            var position = position(e.instrumentId());
            storePosition(new PositionBalance(position.instrumentId(), position.settled(), position.pendingIn(), position.pendingOut(),
                                              position.costBasis().add(e.costBasisChange())));
        }
        handledReferences.add(ref("CORR", e.executionId()));
    }

    @EventHandler
    private void on(TradeBookingReversed e) {
        var trade    = e.trade();
        var position = position(trade.instrumentId());
        if (trade.side() == TradeSide.BUY) {
            pendingCashOut = pendingCashOut.subtract(trade.grossAmount());
            position = new PositionBalance(position.instrumentId(), position.settled(), position.pendingIn().minus(trade.quantity()),
                                           position.pendingOut(), position.costBasis().subtract(trade.costBasisChange()));
        } else {
            pendingCashIn = pendingCashIn.subtract(trade.grossAmount());
            realizedPnl = realizedPnl.subtract(trade.realizedPnl());
            position = new PositionBalance(position.instrumentId(), position.settled(), position.pendingIn(),
                                           position.pendingOut().minus(trade.quantity()), position.costBasis().subtract(trade.costBasisChange()));
        }
        storePosition(position);
        openTrades.remove(trade.executionId());
    }

    @EventHandler
    private void on(UnknownReferenceIgnored e) {
        handledReferences.add(ref("IGN:" + e.kind(), e.reference()));
    }

    @EventHandler
    private void on(AccountBooksClosed e) {
        booksClosed = true;
    }
}
