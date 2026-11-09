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

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.AggregateClosingBooksConfigurationResolver;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.integration.ExchangeClosingBooksCalendar;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Decides when an {@link Account} rolls to its next generation, from what {@code @AggregateClosingBooksPolicy} on
 * {@code Account} resolves to: {@code END_OF_DAY} on the exchange's business days (EF-1, D22, D32) - the books roll when
 * the exchange closes a business day, never on weekends or holidays.
 * <p>
 * Without the exchange (no {@code simulators} profile) there is no business calendar, and the books roll at midnight in
 * {@code America/New_York} on this application's clock instead. The period id is {@code yyyy-MM-dd} either way.
 * <p>
 * Built once: its settings do not change at runtime, and the evaluator is on every mutating command's path.
 */
@Component
public class AccountClosingBooksPolicy {
    private static final Logger log      = LoggerFactory.getLogger(AccountClosingBooksPolicy.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final BuiltInClosingBooksPolicyEvaluator<Account> evaluator;
    private final Optional<ExchangeClosingBooksCalendar>      businessCalendar;

    public AccountClosingBooksPolicy(AggregateClosingBooksConfigurationResolver configurationResolver,
                                     Clock clock,
                                     Optional<MeterRegistry> meterRegistry,
                                     Optional<ExchangeClosingBooksCalendar> exchangeCalendar) {
        requireNonNull(configurationResolver, "No configurationResolver provided");
        requireNonNull(exchangeCalendar, "No exchangeCalendar provided");
        var configuration = configurationResolver.resolve(Accounts.AGGREGATE_TYPE, Account.class);
        businessCalendar = configuration.periodSource() == ClosingBooksPeriodSource.BUSINESS_CALENDAR ? exchangeCalendar : Optional.empty();
        if (configuration.periodSource() == ClosingBooksPeriodSource.BUSINESS_CALENDAR && businessCalendar.isEmpty()) {
            log.info("No exchange, so no business calendar: accounts roll their books at midnight {} instead", configuration.zoneId());
        }
        evaluator = BuiltInClosingBooksPolicyEvaluator.<Account>builder()
                                                      .setAggregateType(Accounts.AGGREGATE_TYPE)
                                                      .setDefaultPolicy(configuration.defaultPolicy())
                                                      // A pure time boundary declares no threshold; 0 is the evaluator's "none"
                                                      .setEventThreshold(configuration.eventThreshold() == null ? 0L : configuration.eventThreshold())
                                                      .setTimeBoundary(configuration.timeBoundary())
                                                      .setZoneId(ZoneId.of(configuration.zoneId()))
                                                      .setIntervalDays(configuration.intervalDays())
                                                      .setClock(requireNonNull(clock, "No clock provided"))
                                                      .setBusinessCalendar(businessCalendar.orElse(null))
                                                      .setMeterRegistry(requireNonNull(meterRegistry, "No meterRegistry provided"))
                                                      .setEventCountProvider(account -> account.eventOrderOfLastAppliedEvent().longValue() + 1)
                                                      .setCurrentPeriodIdProvider(account -> account.periodId().toString())
                                                      .build();
    }

    public boolean shouldRolloverOnAccess(Account account) {
        requireNonNull(account, "No account provided");
        return evaluator.shouldRolloverOnAccess(account);
    }

    public PeriodId nextPeriodId(Account account) {
        requireNonNull(account, "No account provided");
        return PeriodId.of(evaluator.nextPeriodId(account));
    }

    /**
     * The period a brand new account opens in: the exchange's open business date, or - without the exchange, or before
     * it can tell - today in New York on {@code clock}.
     */
    public PeriodId currentPeriodId(Clock clock) {
        var date = businessCalendar.flatMap(ExchangeClosingBooksCalendar::openBusinessDate)
                                   .orElseGet(() -> LocalDate.now(clock.withZone(NEW_YORK)));
        return PeriodId.of(date.toString());
    }

    public String description() {
        return evaluator.description();
    }
}
