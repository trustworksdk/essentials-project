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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.eventsourced.aggregates.OptimisticAggregateLoadException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.book_trade.BookTrade;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.credit_deposit.CreditDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.hold_funds.HoldFunds;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account.OpenAccount;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.release_hold.ReleaseHold;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.settle_trade.SettleTrade;
import dk.trustworks.essentials.examples.trading.accounts.views.account_activity.AccountActivityQuery;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.*;
import org.slf4j.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Phase 1's load scenario (expansion plan, phases): drives the account ledger with buy cycles — hold, book, release,
 * settle — over many accounts, a few of them hot, and compares the two ways of getting commands to an account:
 * <ul>
 *     <li><b>{@link Mode#ORDERED}</b> — the D1 scaling model: every command goes through an {@code Inbox} as an
 *     {@code OrderedMessage} keyed by account id, so one account's commands are handled one at a time, in order,
 *     cluster-wide. Expect no optimistic-concurrency conflicts.</li>
 *     <li><b>{@link Mode#DIRECT}</b> — the naive way: each cycle sent on the command bus from a thread pool, so cycles for
 *     one hot account race. Conflicts are retried and counted.</li>
 * </ul>
 * Both report how long {@code account_activity} takes to catch up after the last command: the views run behind
 * exclusive {@code ViewEventProcessor}s, one instance cluster-wide, and that lag is what a key-partitioned event
 * processor would have to beat.
 * <p>
 * Every run uses fresh account ids ({@code LL-<run>-<n>}), so runs never share state. Only buy cycles: a sell needs a
 * settled position, and in DIRECT mode the race would make that a refusal rather than a measurement.
 * <p>
 * Not a slice: a harness, writing through command types only, reading through a view slice's query (see this package's
 * {@code CLAUDE.md}).
 */
@Component
public class LedgerLoadGenerator implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(LedgerLoadGenerator.class);

    private static final InboxName    INBOX_NAME     = InboxName.of("LedgerLoad:commands");
    private static final UserId       LOAD_OWNER     = UserId.of("USR-LOAD");
    private static final InstrumentId INSTRUMENT     = InstrumentId.of("LL-ACME");
    private static final Money        DEPOSIT        = Money.of("100000000", CurrencyCode.USD);
    private static final Money        HOLD           = Money.of("1010", CurrencyCode.USD);
    private static final Money        PRICE          = Money.of("100.50", CurrencyCode.USD);
    private static final int          MAX_ATTEMPTS   = 20;
    private static final int          ENQUEUE_BATCH  = 500;
    private static final Duration     DRAIN_TIMEOUT  = Duration.ofMinutes(15);
    private static final Duration     VIEW_TIMEOUT   = Duration.ofMinutes(15);

    public enum Mode {
        ORDERED,
        DIRECT
    }

    /**
     * @param hotSharePercent the share of all cycles that go to the {@code hotAccounts} first accounts
     * @param threads         DIRECT: sending threads. ORDERED: the inbox's parallel consumers
     */
    public record Settings(Mode mode, int accounts, int cycles, int hotAccounts, int hotSharePercent, int threads) {
        public Settings {
            requireNonNull(mode, "No mode provided");
            if (accounts < 1 || cycles < 1 || threads < 1 || hotAccounts < 0 || hotAccounts > accounts || hotSharePercent < 0 || hotSharePercent > 100) {
                throw new IllegalArgumentException("Invalid settings: " + accounts + " accounts, " + cycles + " cycles, " + hotAccounts + " hot at "
                                                   + hotSharePercent + "%, " + threads + " threads");
            }
        }
    }

    private final CommandBus                              commandBus;
    private final Inboxes                                 inboxes;
    private final UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;
    private final AccountActivityQuery                    activityQuery;
    private final AccountEventCounter                     eventCounter;
    private final ExecutorService                         runner = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ledger-load").daemon().factory());

    private final    AtomicReference<LedgerLoadResultView> lastResult = new AtomicReference<>();
    private volatile Run                                   current;
    private volatile Inbox                                 inbox;

    public LedgerLoadGenerator(CommandBus commandBus,
                               Inboxes inboxes,
                               UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                               AccountActivityQuery activityQuery,
                               AccountEventCounter eventCounter) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.activityQuery = requireNonNull(activityQuery, "No activityQuery provided");
        this.eventCounter = requireNonNull(eventCounter, "No eventCounter provided");
    }

    public synchronized LedgerLoadResultView start(Settings settings) {
        requireNonNull(settings, "No settings provided");
        if (current != null) {
            throw new IllegalStateException("A ledger load run is already in progress: " + current.runId);
        }
        var run = new Run(settings);
        current = run;
        runner.submit(() -> execute(run));
        return run.view("RUNNING", null);
    }

    public LedgerLoadResultView status() {
        var run = current;
        return run != null ? run.view("RUNNING", null) : lastResult.get();
    }

    /**
     * Blocks until the run in progress, if any, has finished. For tests.
     */
    public LedgerLoadResultView awaitResult(Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (current != null && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        return status();
    }

    @Override
    public void destroy() {
        runner.shutdownNow();
    }

    private void execute(Run run) {
        try {
            run.setUp();
            if (run.settings.mode() == Mode.ORDERED) {
                run.driveOrdered();
            } else {
                run.driveDirect();
            }
            run.awaitViewCatchUp();
            lastResult.set(run.view("DONE", null));
            log.info("Ledger load run {} done: {}", run.runId, lastResult.get());
        } catch (Throwable e) {
            log.error("Ledger load run {} failed", run.runId, e);
            lastResult.set(run.view("FAILED", e.toString()));
        } finally {
            current = null;
        }
    }

    private Inbox inbox(int consumers) {
        // Created once: getOrCreateInbox returns the first instance under this name whatever the settings, so the
        // consumer count is fixed by the first ORDERED run until restart
        if (inbox == null) {
            inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(INBOX_NAME)
                                                        .messageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                        .numberOfParallelMessageConsumers(consumers)
                                                        .redeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), MAX_ATTEMPTS))
                                                        .build(),
                                             this::handleQueued);
        }
        return inbox;
    }

    private void handleQueued(dk.trustworks.essentials.components.foundation.messaging.queue.Message message) {
        var run = current;
        var started = System.nanoTime();
        try {
            commandBus.send(message.getPayload());
        } catch (RuntimeException e) {
            if (isConflict(e)) {
                if (run != null) {
                    run.conflicts.incrementAndGet();
                }
                throw e; // redelivered by the inbox
            }
            if (run != null) {
                run.failures.incrementAndGet();
            }
            log.warn("Ledger load command failed and is dropped: {}", e.toString());
        }
        if (run != null) {
            run.latenciesNanos.add(System.nanoTime() - started);
            run.handled.incrementAndGet();
        }
    }

    private static boolean isConflict(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticAppendToStreamException || cause instanceof OptimisticAggregateLoadException) {
                return true;
            }
        }
        return false;
    }

    private final class Run {
        final Settings settings;
        final String   runId = "LL-" + Long.toString(System.currentTimeMillis(), 36);
        final List<AccountId>          accountIds     = new ArrayList<>();
        final List<List<Object>>       cycles         = new ArrayList<>();
        final List<AccountId>          cycleAccounts  = new ArrayList<>();
        final ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        final AtomicLong handled   = new AtomicLong();
        final AtomicLong conflicts = new AtomicLong();
        final AtomicLong failures  = new AtomicLong();
        volatile long setupMs;
        volatile long enqueueMs;
        volatile long drainMs;
        volatile long viewCatchUpMs = -1;

        Run(Settings settings) {
            this.settings = settings;
        }

        long totalCommands() {
            return 4L * settings.cycles();
        }

        void setUp() {
            var started = System.nanoTime();
            var today   = LocalDate.now(ZoneId.of("America/New_York"));
            for (var i = 0; i < settings.accounts(); i++) {
                var accountId = AccountId.of(runId + "-" + i);
                accountIds.add(accountId);
                commandBus.send(new OpenAccount(accountId, LOAD_OWNER, CurrencyCode.USD));
                commandBus.send(new CreditDeposit(accountId, DepositId.of(runId + "-DEP-" + i), DEPOSIT, today));
            }
            var random = new Random(42);
            for (var c = 0; c < settings.cycles(); c++) {
                var hot       = settings.hotAccounts() > 0 && random.nextInt(100) < settings.hotSharePercent();
                var cold      = settings.accounts() - settings.hotAccounts();
                var index     = hot || cold == 0 ? random.nextInt(Math.max(1, settings.hotAccounts())) : settings.hotAccounts() + random.nextInt(cold);
                var accountId = accountIds.get(index);
                var holdId    = HoldId.of(runId + "-H-" + c);
                var execution = ExecutionId.of(runId + "-X-" + c);
                cycleAccounts.add(accountId);
                cycles.add(List.of(new HoldFunds(accountId, holdId, HoldPurpose.ORDER, HOLD),
                                   new BookTrade(accountId, execution, INSTRUMENT, TradeSide.BUY, Quantity.of(10), PRICE, today, today.plusDays(1), holdId, null),
                                   new ReleaseHold(accountId, holdId),
                                   new SettleTrade(accountId, execution)));
            }
            setupMs = millisSince(started);
        }

        /**
         * One producer thread, so each account's key order is numbered in the order it is committed — the rule the
         * shard-owned engine's ordered lane depends on (see this package's {@code CLAUDE.md}).
         */
        void driveOrdered() throws InterruptedException {
            var target  = inbox(settings.threads());
            var started = System.nanoTime();
            var order   = new HashMap<AccountId, Long>();
            for (var from = 0; from < cycles.size(); from += ENQUEUE_BATCH / 4) {
                var to = Math.min(cycles.size(), from + ENQUEUE_BATCH / 4);
                var batchFrom = from;
                unitOfWorkFactory.usingUnitOfWork(() -> {
                    for (var c = batchFrom; c < to; c++) {
                        var accountId = cycleAccounts.get(c);
                        for (var command : cycles.get(c)) {
                            var next = order.merge(accountId, 1L, Long::sum) - 1;
                            target.addMessageReceived(OrderedMessage.of(command, accountId.toString(), next));
                        }
                    }
                });
            }
            enqueueMs = millisSince(started);
            awaitHandled(started);
        }

        void driveDirect() throws InterruptedException {
            var pool    = Executors.newFixedThreadPool(settings.threads(), Thread.ofPlatform().name("ledger-load-direct-", 0).factory());
            var started = System.nanoTime();
            try {
                for (var cycle : cycles) {
                    pool.submit(() -> runCycle(cycle));
                }
                pool.shutdown();
                if (!pool.awaitTermination(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("DIRECT run did not finish within " + DRAIN_TIMEOUT);
                }
            } finally {
                pool.shutdownNow();
            }
            drainMs = millisSince(started);
        }

        private void runCycle(List<Object> cycle) {
            var started = System.nanoTime();
            for (var command : cycle) {
                for (var attempt = 1; ; attempt++) {
                    try {
                        commandBus.send(command);
                        handled.incrementAndGet();
                        break;
                    } catch (RuntimeException e) {
                        if (isConflict(e) && attempt < MAX_ATTEMPTS) {
                            conflicts.incrementAndGet();
                            continue;
                        }
                        failures.incrementAndGet();
                        log.warn("Ledger load command failed after {} attempt(s): {}", attempt, e.toString());
                        break;
                    }
                }
            }
            latenciesNanos.add(System.nanoTime() - started);
        }

        private void awaitHandled(long startedNanos) throws InterruptedException {
            var deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
            while (handled.get() + failures.get() < totalCommands()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("ORDERED run handled " + handled.get() + " of " + totalCommands() + " within " + DRAIN_TIMEOUT);
                }
                Thread.sleep(20);
            }
            drainMs = millisSince(startedNanos);
        }

        /**
         * Waits until {@code account_activity} holds a line for every event the run actually appended — counted in the
         * event store, so failed and no-op commands need no bookkeeping here.
         */
        void awaitViewCatchUp() throws InterruptedException {
            var prefix   = runId + "-";
            var expected = eventCounter.countEventsForAccountsStartingWith(prefix);
            var started  = System.nanoTime();
            var deadline = started + VIEW_TIMEOUT.toNanos();
            while (activityQuery.countEntriesForAccountsStartingWith(prefix) < expected) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("account_activity did not catch up with " + expected + " events within " + VIEW_TIMEOUT);
                }
                Thread.sleep(20);
            }
            viewCatchUpMs = millisSince(started);
        }

        LedgerLoadResultView view(String state, String error) {
            var sorted = latenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
            return new LedgerLoadResultView(runId,
                                            settings.mode(),
                                            state,
                                            settings.accounts(),
                                            settings.hotAccounts(),
                                            settings.cycles(),
                                            totalCommands(),
                                            handled.get(),
                                            setupMs,
                                            enqueueMs,
                                            drainMs,
                                            drainMs > 0 ? handled.get() * 1000.0 / drainMs : 0,
                                            percentileMs(sorted, 0.50),
                                            percentileMs(sorted, 0.99),
                                            sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1_000_000.0,
                                            conflicts.get(),
                                            failures.get(),
                                            viewCatchUpMs,
                                            error);
        }
    }

    private static double percentileMs(long[] sortedNanos, double percentile) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        var index = (int) Math.min(sortedNanos.length - 1, Math.ceil(percentile * sortedNanos.length) - 1);
        return sortedNanos[Math.max(0, index)] / 1_000_000.0;
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
