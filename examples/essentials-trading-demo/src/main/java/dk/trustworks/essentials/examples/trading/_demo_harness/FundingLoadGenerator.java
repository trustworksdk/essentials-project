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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.clear_deposit.ClearDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.credit_deposit.CreditDeposit;
import dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account.OpenAccount;
import dk.trustworks.essentials.examples.trading.funding.types.*;
import dk.trustworks.essentials.examples.trading.funding.use_cases.link_bank_account.LinkBankAccount;
import dk.trustworks.essentials.examples.trading.funding.use_cases.request_deposit.RequestDeposit;
import dk.trustworks.essentials.examples.trading.funding.use_cases.request_withdrawal.RequestWithdrawal;
import dk.trustworks.essentials.examples.trading.identity.types.UserId;
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
 * The phase 2 load scenario: simulated customers moving money, each one account with a linked bank account and seeded
 * withdrawable cash, requesting its deposits and withdrawals one after another, {@code threads} customers at once,
 * through {@code RequestDeposit} / {@code RequestWithdrawal} - against the stub gateway, or the payment-gateway
 * simulator under the {@code simulators} profile. Done when every payment has an outcome and every completed one is
 * booked on the ledger, counted in the event tables (the {@code Deposits} / {@code Withdrawals} outcomes,
 * {@code DepositCredited} / {@code CashWithdrawn} on the accounts). End to end is the booking's timestamp minus the
 * moment the request returned.
 * <p>
 * Fresh {@code FL-<run>-<n>} accounts and {@code FL-<run>-<n>-D<i>} / {@code -W<i>} payments per run. The seed cash goes
 * through the ledger's operator commands (credit and clear at once), so withdrawals need not wait out a deposit's return
 * window.
 */
@Component
public class FundingLoadGenerator implements DisposableBean {
    private static final Logger      log           = LoggerFactory.getLogger(FundingLoadGenerator.class);
    private static final UserId      LOAD_OWNER    = UserId.of("USR-LOAD");
    private static final Money       SEED_CASH     = Money.of("1000000", CurrencyCode.USD);
    private static final BankAccount BANK_ACCOUNT  = new BankAccount("GB82WEST12345698765432", "Load Customer");
    private static final int         MAX_ATTEMPTS  = 20;
    private static final Duration    DRAIN_TIMEOUT = Duration.ofMinutes(15);

    public record Settings(int customers, int depositsPerCustomer, int withdrawalsPerCustomer, int threads) {
        public Settings {
            if (customers < 1 || depositsPerCustomer < 0 || withdrawalsPerCustomer < 0 || depositsPerCustomer + withdrawalsPerCustomer < 1 || threads < 1) {
                throw new IllegalArgumentException("Invalid settings: " + customers + " customers, " + depositsPerCustomer + " deposits and "
                                                   + withdrawalsPerCustomer + " withdrawals each, " + threads + " threads");
            }
        }
    }

    private final CommandBus          commandBus;
    private final AccountEventCounter eventCounter;
    private final ExecutorService     runner = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("funding-load").daemon().factory());

    private final    AtomicReference<FundingLoadResultView> lastResult = new AtomicReference<>();
    private volatile Run                                    current;

    public FundingLoadGenerator(CommandBus commandBus, AccountEventCounter eventCounter) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.eventCounter = requireNonNull(eventCounter, "No eventCounter provided");
    }

    public synchronized FundingLoadResultView start(Settings settings) {
        requireNonNull(settings, "No settings provided");
        if (current != null) {
            throw new IllegalStateException("A funding load run is in progress: " + current.runId);
        }
        var run = new Run(Long.toString(System.currentTimeMillis(), 36), settings);
        current = run;
        lastResult.set(run.view("RUNNING", null));
        runner.submit(() -> execute(run));
        return lastResult.get();
    }

    public FundingLoadResultView status() {
        var run = current;
        return run != null ? run.view("RUNNING", null) : lastResult.get();
    }

    @Override
    public void destroy() {
        runner.shutdownNow();
    }

    private void execute(Run run) {
        try {
            var settings = run.settings;
            log.info("Funding load {}: {} customers x {} deposits + {} withdrawals, {} at once", run.runId, settings.customers(),
                     settings.depositsPerCustomer(), settings.withdrawalsPerCustomer(), settings.threads());
            var setupStarted = System.nanoTime();
            for (var i = 0; i < settings.customers(); i++) {
                var accountId = run.account(i);
                send(new OpenAccount(accountId, LOAD_OWNER, CurrencyCode.USD), run);
                send(new LinkBankAccount(accountId, BANK_ACCOUNT), run);
                var seed = DepositId.of("SEED-" + accountId);
                send(new CreditDeposit(accountId, seed, SEED_CASH, LocalDate.of(2030, 1, 7)), run);
                send(new ClearDeposit(accountId, seed), run);
            }
            run.setupMs = millisSince(setupStarted);

            var pool = Executors.newFixedThreadPool(settings.threads(), Thread.ofPlatform().name("funding-customer-", 0).daemon().factory());
            run.requestStarted = System.nanoTime();
            try {
                var customers = new ArrayList<Future<?>>();
                for (var i = 0; i < settings.customers(); i++) {
                    var customer = i;
                    customers.add(pool.submit(() -> moveMoney(run, customer)));
                }
                for (var customer : customers) {
                    customer.get();
                }
            } finally {
                pool.shutdownNow();
            }
            run.requestMs = millisSince(run.requestStarted);

            awaitDrained(run);
            lastResult.set(run.view("COMPLETED", null));
            log.info("Funding load {} completed: {}", run.runId, lastResult.get());
        } catch (Throwable e) {
            log.error("Funding load {} failed", run.runId, e);
            lastResult.set(run.view("FAILED", e.toString()));
        } finally {
            current = null;
        }
    }

    private void moveMoney(Run run, int customer) {
        var accountId = run.account(customer);
        var random    = ThreadLocalRandom.current();
        for (var n = 0; n < run.settings.depositsPerCustomer(); n++) {
            var reference = accountId + "-D" + n;
            request(run, reference, new RequestDeposit(DepositId.of(reference), accountId, usd(random.nextInt(10, 1001))), run.depositRequestedAt);
        }
        for (var n = 0; n < run.settings.withdrawalsPerCustomer(); n++) {
            var reference = accountId + "-W" + n;
            request(run, reference, new RequestWithdrawal(WithdrawalId.of(reference), accountId, usd(random.nextInt(1, 101))), run.withdrawalRequestedAt);
        }
    }

    private void request(Run run, String reference, Object command, Map<String, Instant> requestedAt) {
        var started = System.nanoTime();
        if (!send(command, run)) {
            return;
        }
        run.requestLatencies.add(System.nanoTime() - started);
        run.requested.incrementAndGet();
        requestedAt.put(reference, Instant.now());
    }

    private static Money usd(long amount) {
        return Money.of(BigDecimal.valueOf(amount), CurrencyCode.USD);
    }

    /**
     * @return false when the command was refused (counted), true when it was handled
     */
    private boolean send(Object command, Run run) {
        for (var attempt = 1; ; attempt++) {
            try {
                commandBus.send(command);
                return true;
            } catch (RuntimeException e) {
                if (isConflict(e) && attempt < MAX_ATTEMPTS) {
                    run.conflicts.incrementAndGet();
                    continue;
                }
                run.refused.incrementAndGet();
                log.debug("Funding load command refused: {}", e.toString());
                return false;
            }
        }
    }

    private void awaitDrained(Run run) throws InterruptedException {
        var prefix   = run.accountPrefix();
        var deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        while (true) {
            var depositsCompleted    = eventCounter.countEventsOfType(FundingAggregateTypes.DEPOSITS, prefix, "DepositCompleted");
            var withdrawalsCompleted = eventCounter.countEventsOfType(FundingAggregateTypes.WITHDRAWALS, prefix, "WithdrawalCompleted");
            run.completed = depositsCompleted + withdrawalsCompleted;
            run.failed = eventCounter.countEventsOfType(FundingAggregateTypes.DEPOSITS, prefix, "DepositFailed")
                         + eventCounter.countEventsOfType(FundingAggregateTypes.WITHDRAWALS, prefix, "WithdrawalFailed");
            // The seed deposit of every account is a DepositCredited too
            run.booked = eventCounter.countEventsOfTypeForAccountsStartingWith(prefix, "DepositCredited") - run.settings.customers()
                         + eventCounter.countEventsOfTypeForAccountsStartingWith(prefix, "CashWithdrawn");
            if (run.completed + run.failed >= run.requested.get() && run.booked >= run.completed) {
                run.drainMs = millisSince(run.requestStarted);
                run.depositE2e = endToEndNanos(run.depositRequestedAt, eventCounter.accountEventTimesByPayloadField(prefix, "DepositCredited", "depositId"));
                run.withdrawalE2e = endToEndNanos(run.withdrawalRequestedAt, eventCounter.accountEventTimesByPayloadField(prefix, "CashWithdrawn", "withdrawalId"));
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Not drained after " + DRAIN_TIMEOUT + ": " + run.completed + " completed, " + run.failed + " failed, "
                                                + run.booked + " booked of " + run.requested.get() + " requested");
            }
            Thread.sleep(200);
        }
    }

    private static long[] endToEndNanos(Map<String, Instant> requestedAt, Map<String, Instant> bookedAt) {
        return requestedAt.entrySet().stream()
                          .filter(requested -> bookedAt.containsKey(requested.getKey()))
                          .mapToLong(requested -> Math.max(0, Duration.between(requested.getValue(), bookedAt.get(requested.getKey())).toNanos()))
                          .sorted()
                          .toArray();
    }

    private static boolean isConflict(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticAppendToStreamException) {
                return true;
            }
        }
        return false;
    }

    private static long millisSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static double percentileMs(long[] sortedNanos, double percentile) {
        if (sortedNanos == null || sortedNanos.length == 0) {
            return 0;
        }
        var index = (int) Math.min(sortedNanos.length - 1, Math.ceil(percentile * sortedNanos.length) - 1);
        return sortedNanos[Math.max(0, index)] / 1_000_000.0;
    }

    private static final class Run {
        final String                      runId;
        final Settings                    settings;
        final AtomicLong                  requested             = new AtomicLong();
        final AtomicLong                  refused               = new AtomicLong();
        final AtomicLong                  conflicts             = new AtomicLong();
        final ConcurrentLinkedQueue<Long> requestLatencies      = new ConcurrentLinkedQueue<>();
        final Map<String, Instant>        depositRequestedAt    = new ConcurrentHashMap<>();
        final Map<String, Instant>        withdrawalRequestedAt = new ConcurrentHashMap<>();
        volatile long                     requestStarted;
        volatile long                     setupMs;
        volatile long                     requestMs;
        volatile long                     drainMs;
        volatile long                     completed;
        volatile long                     failed;
        volatile long                     booked;
        volatile long[]                   depositE2e;
        volatile long[]                   withdrawalE2e;

        Run(String runId, Settings settings) {
            this.runId = runId;
            this.settings = settings;
        }

        String accountPrefix() {
            return "FL-" + runId + "-";
        }

        AccountId account(int customer) {
            return AccountId.of(accountPrefix() + customer);
        }

        FundingLoadResultView view(String state, String error) {
            var latencies = requestLatencies.stream().mapToLong(Long::longValue).sorted().toArray();
            var requestSeconds = requestMs / 1000.0;
            var drainSeconds = drainMs / 1000.0;
            return new FundingLoadResultView(runId, state, settings.customers(), settings.depositsPerCustomer(), settings.withdrawalsPerCustomer(), settings.threads(),
                                             requested.get(), refused.get(), conflicts.get(), setupMs, requestMs,
                                             requestSeconds > 0 ? requested.get() / requestSeconds : 0,
                                             percentileMs(latencies, 0.5), percentileMs(latencies, 0.99),
                                             drainMs, drainSeconds > 0 ? completed / drainSeconds : 0,
                                             completed, failed, booked,
                                             percentileMs(depositE2e, 0.5), percentileMs(depositE2e, 0.99),
                                             percentileMs(withdrawalE2e, 0.5), percentileMs(withdrawalE2e, 0.99),
                                             error);
        }
    }
}
