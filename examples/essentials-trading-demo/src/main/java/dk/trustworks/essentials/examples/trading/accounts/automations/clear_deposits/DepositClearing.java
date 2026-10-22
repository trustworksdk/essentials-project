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

package dk.trustworks.essentials.examples.trading.accounts.automations.clear_deposits;

import dk.trustworks.essentials.examples.trading.accounts.use_cases.clear_deposit.ClearDeposit;
import dk.trustworks.essentials.examples.trading.accounts.views.uncleared_deposits.UnclearedDepositsProjection;
import dk.trustworks.essentials.examples.trading.integration.BusinessCalendar;
import dk.trustworks.essentials.examples.trading.integration.BusinessCalendar.BusinessDayClosedNotice;
import dk.trustworks.essentials.reactive.command.CommandBus;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Clears every deposit whose return window has passed (design phase 2 §6, phase 1 Q-7) when the exchange closes a
 * business day: each deposit in {@code views/uncleared_deposits} that clears on or before the closed day gets a
 * {@link ClearDeposit}, which makes it withdrawable. The business day is the exchange's ({@link BusinessCalendar}),
 * never this application's clock (D10).
 * <p>
 * {@code ClearDeposit} is idempotent, so a notice seen twice, or a deposit the view still lists after it cleared, costs
 * nothing. A notice this instance did not see - another instance recorded the day, or the application was down - is
 * made up at startup from the calendar's latest closed day, and in any case at the next close.
 * <p>
 * Without the exchange-simulator there is no calendar, and deposits clear only through the operator endpoint.
 */
@Component
public class DepositClearing {
    private static final Logger log = LoggerFactory.getLogger(DepositClearing.class);

    private final UnclearedDepositsProjection    unclearedDeposits;
    private final CommandBus                     commandBus;
    private final ObjectProvider<BusinessCalendar> calendar;
    private final ExecutorService                  clearing = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("deposit-clearing").daemon().factory());

    public DepositClearing(UnclearedDepositsProjection unclearedDeposits, CommandBus commandBus, ObjectProvider<BusinessCalendar> calendar) {
        this.unclearedDeposits = requireNonNull(unclearedDeposits, "No unclearedDeposits provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.calendar = requireNonNull(calendar, "No calendar provided");
    }

    /**
     * After the calendar's unit of work committed, and on a thread of its own: each {@link ClearDeposit} runs in a unit of
     * work of its own, so a deposit that fails to clear never rolls back the recorded business day; it stays in the view
     * and is cleared at the next close. Not inline in the after-commit callback: Spring's transaction synchronization is
     * no longer active there, and {@code SpringTransactionAwareUnitOfWorkFactory} cannot open a unit of work in it
     * ({@code IllegalStateException: Transaction synchronization is not active}).
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(BusinessDayClosedNotice closed) {
        clearing.execute(() -> clearDepositsDueBy(closed.businessDate()));
    }

    @PreDestroy
    public void stop() {
        clearing.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        calendar.ifAvailable(businessCalendar -> businessCalendar.latestClosedBusinessDate().ifPresent(this::clearDepositsDueBy));
    }

    private void clearDepositsDueBy(LocalDate businessDate) {
        var due = unclearedDeposits.clearingOnOrBefore(businessDate);
        for (var deposit : due) {
            try {
                commandBus.send(new ClearDeposit(deposit.accountId(), deposit.depositId()));
            } catch (RuntimeException e) {
                log.warn("Deposit '{}' of account '{}' did not clear on business day {}; retried at the next close", deposit.depositId(), deposit.accountId(), businessDate, e);
            }
        }
        if (!due.isEmpty()) {
            log.debug("Business day {} closed: cleared {} deposit(s)", businessDate, due.size());
        }
    }
}
