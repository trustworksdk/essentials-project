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

package dk.trustworks.essentials.examples.trading.accounts;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.AggregateClosingBooksConfigurationResolver;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.BuiltInClosingBooksPolicyEvaluator;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.examples.trading.config.TradingDemoAggregateConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Component
public class TradingAccountClosingBooksPolicy {
    private volatile ClosingBooksDefaultPolicyType mode;
    private volatile long eventThreshold;
    private volatile ClosingBooksTimeBoundary timeBoundary;
    private volatile String zoneId;
    private volatile Integer intervalDays;
    private final Clock clock;
    private final Optional<MeterRegistry> meterRegistry;

    public TradingAccountClosingBooksPolicy(AggregateClosingBooksConfigurationResolver configurationResolver,
                                            TradingAccountClosingBooksProperties properties,
                                            Clock clock,
                                            Optional<MeterRegistry> meterRegistry) {
        requireNonNull(configurationResolver, "No configurationResolver provided");
        requireNonNull(properties, "No properties provided");
        this.clock = requireNonNull(clock, "No clock provided");
        this.meterRegistry = requireNonNull(meterRegistry, "No meterRegistry provided");

        var resolvedConfiguration = configurationResolver.resolve(AggregateType.of(TradingDemoAggregateConfiguration.TRADING_ACCOUNTS.toString()),
                                                                  TradingAccount.class);
        mode = properties.getMode() != null
                ? properties.getMode()
                : resolvedConfiguration.defaultPolicy() != null && resolvedConfiguration.defaultPolicy() != ClosingBooksDefaultPolicyType.UNSPECIFIED
                    ? resolvedConfiguration.defaultPolicy()
                    : ClosingBooksDefaultPolicyType.MANUAL_ONLY;
        eventThreshold = properties.getEventThreshold() != null
                ? properties.getEventThreshold()
                : resolvedConfiguration.eventThreshold() != null
                    ? resolvedConfiguration.eventThreshold()
                    : 100L;
        timeBoundary = properties.getTimeBoundary() != null
                ? properties.getTimeBoundary()
                : resolvedConfiguration.timeBoundary() != null
                    ? resolvedConfiguration.timeBoundary()
                    : ClosingBooksTimeBoundary.NONE;
        zoneId = properties.getZoneId() != null && !properties.getZoneId().isBlank()
                ? properties.getZoneId().trim()
                : resolvedConfiguration.zoneId() != null && !resolvedConfiguration.zoneId().isBlank()
                    ? resolvedConfiguration.zoneId()
                    : "UTC";
        intervalDays = properties.getIntervalDays() != null
                ? properties.getIntervalDays()
                : resolvedConfiguration.intervalDays();
    }

    public boolean shouldRolloverOnAccess(TradingAccount account) {
        requireNonNull(account, "No account provided");
        return evaluator().shouldRolloverOnAccess(account);
    }

    public String nextPeriodId(TradingAccount account) {
        requireNonNull(account, "No account provided");
        return evaluator().nextPeriodId(account);
    }

    public String description() {
        return evaluator().description();
    }

    public ClosingBooksDefaultPolicyType mode() {
        return mode;
    }

    public long eventThreshold() {
        return eventThreshold;
    }

    public ClosingBooksTimeBoundary timeBoundary() {
        return timeBoundary;
    }

    public String zoneId() {
        return zoneId;
    }

    public Integer intervalDays() {
        return intervalDays;
    }

    public void updateMode(String mode) {
        requireNonNull(mode, "No mode provided");
        if (mode.isBlank()) {
            throw new IllegalArgumentException("mode must not be blank");
        }
        this.mode = ClosingBooksDefaultPolicyType.valueOf(mode.trim().replace('-', '_').toUpperCase());
    }

    public void updateEventThreshold(long eventThreshold) {
        if (eventThreshold <= 0) {
            throw new IllegalArgumentException("eventThreshold must be > 0");
        }
        this.eventThreshold = eventThreshold;
    }

    public void updateTimeBoundary(String timeBoundary) {
        requireNonNull(timeBoundary, "No timeBoundary provided");
        if (timeBoundary.isBlank()) {
            throw new IllegalArgumentException("timeBoundary must not be blank");
        }
        this.timeBoundary = ClosingBooksTimeBoundary.valueOf(timeBoundary.trim().replace('-', '_').toUpperCase());
    }

    public void updateZoneId(String zoneId) {
        requireNonNull(zoneId, "No zoneId provided");
        if (zoneId.isBlank()) {
            throw new IllegalArgumentException("zoneId must not be blank");
        }
        ZoneId.of(zoneId.trim());
        this.zoneId = zoneId.trim();
    }

    public void updateIntervalDays(int intervalDays) {
        if (intervalDays <= 0) {
            throw new IllegalArgumentException("intervalDays must be > 0");
        }
        this.intervalDays = intervalDays;
    }

    private BuiltInClosingBooksPolicyEvaluator<TradingAccount> evaluator() {
        return new BuiltInClosingBooksPolicyEvaluator<>(AggregateType.of(TradingDemoAggregateConfiguration.TRADING_ACCOUNTS.toString()),
                                                        mode,
                                                        eventThreshold,
                                                        timeBoundary,
                                                        ZoneId.of(zoneId),
                                                        intervalDays,
                                                        clock,
                                                        meterRegistry,
                                                        account -> account.eventOrderOfLastAppliedEvent().longValue() + 1,
                                                        account -> account.periodId);
    }
}
