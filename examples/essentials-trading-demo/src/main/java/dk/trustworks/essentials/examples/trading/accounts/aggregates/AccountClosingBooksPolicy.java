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
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Decides when an {@link Account} rolls to its next generation, from what {@code @AggregateClosingBooksPolicy} on
 * {@code Account} resolves to: {@code END_OF_DAY} in {@code America/New_York} (design §6).
 * <p>
 * Built once. The frozen {@code brokerage} account builds its evaluator on every call because its settings change at
 * runtime for the benchmarks; this one does not need to, and the evaluator is on every mutating command's path.
 * <p>
 * Interim: the wall clock in New York, until EF-1 lets the period follow the exchange's business day (D22, D24). The
 * period id is {@code yyyy-MM-dd} either way, so the switch will migrate nothing.
 */
@Component
public class AccountClosingBooksPolicy {
    private final BuiltInClosingBooksPolicyEvaluator<Account> evaluator;

    public AccountClosingBooksPolicy(AggregateClosingBooksConfigurationResolver configurationResolver,
                                     Clock clock,
                                     Optional<MeterRegistry> meterRegistry) {
        requireNonNull(configurationResolver, "No configurationResolver provided");
        var configuration = configurationResolver.resolve(Accounts.AGGREGATE_TYPE, Account.class);
        evaluator = BuiltInClosingBooksPolicyEvaluator.<Account>builder()
                                                      .setAggregateType(Accounts.AGGREGATE_TYPE)
                                                      .setDefaultPolicy(configuration.defaultPolicy())
                                                      // A pure time boundary declares no threshold; 0 is the evaluator's "none"
                                                      .setEventThreshold(configuration.eventThreshold() == null ? 0L : configuration.eventThreshold())
                                                      .setTimeBoundary(configuration.timeBoundary())
                                                      .setZoneId(ZoneId.of(configuration.zoneId()))
                                                      .setIntervalDays(configuration.intervalDays())
                                                      .setClock(requireNonNull(clock, "No clock provided"))
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
     * The period a brand new account opens in.
     */
    public PeriodId currentPeriodId(Clock clock) {
        return PeriodId.of(LocalDate.now(clock.withZone(ZoneId.of("America/New_York"))).toString());
    }

    public String description() {
        return evaluator.description();
    }
}
