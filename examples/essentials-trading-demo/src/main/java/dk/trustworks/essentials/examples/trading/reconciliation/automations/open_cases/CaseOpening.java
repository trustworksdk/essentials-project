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

package dk.trustworks.essentials.examples.trading.reconciliation.automations.open_cases;

import dk.trustworks.essentials.examples.trading.integration.BusinessCalendar;
import dk.trustworks.essentials.examples.trading.integration.BusinessCalendar.BusinessDayClosedNotice;
import dk.trustworks.essentials.examples.trading.reconciliation.config.ReconciliationProperties;
import dk.trustworks.essentials.examples.trading.reconciliation.external_systems.bank.BankStatements;
import dk.trustworks.essentials.examples.trading.reconciliation.external_systems.clearinghouse.ClearingStatements;
import dk.trustworks.essentials.examples.trading.reconciliation.types.CaseId;
import dk.trustworks.essentials.examples.trading.reconciliation.use_cases.track_breaks.*;
import dk.trustworks.essentials.examples.trading.reconciliation.views.bank_statement_breaks.BankStatementBreaksQuery;
import dk.trustworks.essentials.examples.trading.reconciliation.views.cases.*;
import dk.trustworks.essentials.examples.trading.reconciliation.views.clearing_statement_breaks.ClearingStatementBreaksQuery;
import dk.trustworks.essentials.examples.trading.reconciliation.views.missing_statements.*;
import dk.trustworks.essentials.reactive.command.CommandBus;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Turns the breaks that persist into operator cases (design phase 5 §4, Q-1). At each business day's close, every
 * break of a statement of an earlier business day - still there at the next close - gets a case, and so does every
 * missing statement; every open case whose break has gone is cleared. Opening at once would open, then clear, a case for
 * every statement that beats our own projection of the fill or payment.
 * <p>
 * The statements checked are those of the last {@code trading-demo.reconciliation.case-lookback-days} before the
 * closed day, and every statement an open case is about. A statement counts as missing only once both statement sources
 * have been read to their end and stored: the calendar's reader can be ahead of the statements' readers, most visibly
 * when the clock jumps several days and every close arrives at once; if either cannot catch up, missing statements wait
 * for the next close. Both commands are idempotent, so a notice seen twice, or on two
 * instances, costs nothing; one this instance missed is made up at startup and in any case at the next close. Runs on a
 * thread of its own after the calendar's commit, as {@code accounts}' deposit clearing does, for the same reason.
 */
@Component
public class CaseOpening {
    private static final Logger   log              = LoggerFactory.getLogger(CaseOpening.class);
    private static final Duration CATCH_UP_TIMEOUT = Duration.ofSeconds(10);

    @Component
    public record Dependencies(ClearingStatementBreaksQuery clearingBreaks,
                               BankStatementBreaksQuery bankBreaks,
                               MissingStatementsQuery missingStatements,
                               CasesProjection cases,
                               ReconciliationProperties properties,
                               ObjectProvider<ClearingStatements> clearingStatements,
                               ObjectProvider<BankStatements> bankStatements) {
        public Dependencies {
            requireNonNull(clearingBreaks, "No clearingBreaks provided");
            requireNonNull(bankBreaks, "No bankBreaks provided");
            requireNonNull(missingStatements, "No missingStatements provided");
            requireNonNull(cases, "No cases provided");
            requireNonNull(properties, "No properties provided");
            requireNonNull(clearingStatements, "No clearingStatements provided");
            requireNonNull(bankStatements, "No bankStatements provided");
        }
    }

    private record Found(CaseId caseId, String statementId, LocalDate businessDate, String kind, String reference, String detail) {
    }

    private final Dependencies                     dependencies;
    private final CommandBus                       commandBus;
    private final ObjectProvider<BusinessCalendar> calendar;
    private final ExecutorService                  opening = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("reconciliation-case-opening").daemon().factory());

    public CaseOpening(Dependencies dependencies, CommandBus commandBus, ObjectProvider<BusinessCalendar> calendar) {
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.calendar = requireNonNull(calendar, "No calendar provided");
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(BusinessDayClosedNotice closed) {
        opening.execute(() -> reconcile(closed.businessDate()));
    }

    @PreDestroy
    public void stop() {
        opening.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        calendar.ifAvailable(businessCalendar -> businessCalendar.latestClosedBusinessDate().ifPresent(closed -> opening.execute(() -> reconcile(closed))));
    }

    /**
     * Opens and clears cases as of the close of {@code closedBusinessDate}. Public for tests, which drive it without a
     * close.
     */
    public synchronized void reconcile(LocalDate closedBusinessDate) {
        requireNonNull(closedBusinessDate, "No closedBusinessDate provided");
        var openCases = dependencies.cases().openCases();
        var from      = closedBusinessDate.minusDays(dependencies.properties().getCaseLookbackDays());
        var clearing  = new LinkedHashMap<>(dependencies.clearingBreaks().statementsFrom(from));
        var bank      = new LinkedHashMap<>(dependencies.bankBreaks().statementsFrom(from));
        clearing.values().removeIf(businessDate -> !businessDate.isBefore(closedBusinessDate));
        bank.values().removeIf(businessDate -> !businessDate.isBefore(closedBusinessDate));
        for (var open : openCases) {
            if (!MissingStatement.KIND.equals(open.kind())) {
                dependencies.clearingBreaks().businessDateOf(open.statementId()).ifPresent(date -> clearing.putIfAbsent(open.statementId(), date));
                dependencies.bankBreaks().businessDateOf(open.statementId()).ifPresent(date -> bank.putIfAbsent(open.statementId(), date));
            }
        }

        var found = new LinkedHashMap<CaseId, Found>();
        clearing.forEach((statementId, businessDate) -> dependencies.clearingBreaks().breaksOf(statementId).forEach(b -> {
            var caseId = CaseId.of(statementId, b.kind().name(), b.reference());
            found.put(caseId, new Found(caseId, statementId, businessDate, b.kind().name(), b.reference(),
                                        "statement: " + describe(b.statementQuantity(), b.statementAmount()) + "; ours: " + describe(b.ourQuantity(), b.ourAmount())));
        }));
        bank.forEach((statementId, businessDate) -> dependencies.bankBreaks().breaksOf(statementId).forEach(b -> {
            var caseId = CaseId.of(statementId, b.kind().name(), b.reference());
            found.put(caseId, new Found(caseId, statementId, businessDate, b.kind().name(), b.reference(),
                                        b.entryKind() + " statement: " + describe(null, b.statementAmount()) + "; ours: " + describe(null, b.ourAmount())));
        }));
        var clearingSource     = dependencies.clearingStatements().getIfAvailable();
        var bankSource         = dependencies.bankStatements().getIfAvailable();
        var statementsCaughtUp = (clearingSource == null || clearingSource.catchUp(CATCH_UP_TIMEOUT)) && (bankSource == null || bankSource.catchUp(CATCH_UP_TIMEOUT));
        for (var missing : statementsCaughtUp ? dependencies.missingStatements().missing() : List.<MissingStatement>of()) {
            if (missing.businessDate().isBefore(closedBusinessDate)) {
                var caseId = CaseId.of(missing.statementId(), MissingStatement.KIND, missing.businessDate().toString());
                found.put(caseId, new Found(caseId, missing.statementId(), missing.businessDate(), MissingStatement.KIND, missing.businessDate().toString(),
                                            missing.source() + " statement expected by " + missing.expectedBy()));
            }
        }

        var opened = 0;
        for (var f : found.values()) {
            try {
                commandBus.send(new OpenCase(f.caseId(), f.statementId(), f.businessDate(), f.kind(), f.reference(), f.detail()));
                opened++;
            } catch (RuntimeException e) {
                log.warn("Case '{}' was not opened at the close of {}; retried at the next close", f.caseId(), closedBusinessDate, e);
            }
        }
        var cleared = 0;
        for (var open : openCases) {
            if (!found.containsKey(CaseId.of(open.caseId())) && isChecked(open, clearing, bank, statementsCaughtUp)) {
                try {
                    commandBus.send(new ClearCase(CaseId.of(open.caseId())));
                    cleared++;
                } catch (RuntimeException e) {
                    log.warn("Case '{}' was not cleared at the close of {}; retried at the next close", open.caseId(), closedBusinessDate, e);
                }
            }
        }
        if (!found.isEmpty() || cleared > 0) {
            log.debug("Business day {} closed: {} break(s) persisting ({} sent), {} case(s) cleared", closedBusinessDate, found.size(), opened, cleared);
        }
    }

    /**
     * Whether this run looked at the case's break at all - a case about a statement that has since gone out of reach is
     * never cleared by its absence, nor a missing statement while the sources have not been read to their end.
     */
    private static boolean isChecked(CaseView open, Map<String, LocalDate> clearing, Map<String, LocalDate> bank, boolean statementsCaughtUp) {
        if (MissingStatement.KIND.equals(open.kind())) {
            return statementsCaughtUp;
        }
        return clearing.containsKey(open.statementId()) || bank.containsKey(open.statementId());
    }

    private static String describe(Long quantity, BigDecimal amount) {
        if (quantity == null && amount == null) {
            return "none";
        }
        return (quantity != null ? quantity + " for " : "") + (amount != null ? amount.stripTrailingZeros().toPlainString() : "?");
    }
}
