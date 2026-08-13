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

package dk.trustworks.essentials.examples.trading.brokerage.aggregates;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.AggregateClosingBooksConfigurationResolver;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.BuiltInClosingBooksPolicyEvaluator;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.examples.trading.brokerage.config.TradingAccountClosingBooksProperties;
import dk.trustworks.essentials.examples.trading.brokerage.types.ClosingBooksSettings;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Decides whether a {@link TradingAccount} should roll its books when it is next touched, and what the next period is
 * called.
 *
 * <p>The decision itself is the framework's {@link BuiltInClosingBooksPolicyEvaluator}; this class owns the
 * <em>configuration</em> it is evaluated against and the fact that the configuration can change at runtime -- the demo
 * lets an admin endpoint retune the policy and lets the load harness compare two policies against each other on the
 * same data.
 *
 * <h2>Why one settings record behind one lock</h2>
 * This held five separate {@code volatile} fields with a setter each. That has two defects. A reader could observe a
 * half-applied change -- the new {@code mode} against the old {@code timeBoundary} -- because five writes are not one
 * write. And two writers had no way to exclude each other, which is exactly what the comparison scenario needs: it
 * swaps the policy, runs a workload, and swaps it back, and an admin request landing in the middle used to leave the
 * settings in a combination neither party chose.
 *
 * <p>So the state is one immutable {@link ClosingBooksSettings} reference, and every mutation goes through the same
 * {@link ReentrantLock}. {@link #withTemporarySettings} holds that lock for the whole action rather than just the two
 * swaps, which is what makes the override actually exclusive.
 *
 * <h2>The two {@code String} seams</h2>
 * {@link BuiltInClosingBooksPolicyEvaluator} is {@code String}-typed for the period id, so the conversion to and from
 * {@link PeriodId} happens at exactly two points: the {@code currentPeriodIdProvider} lambda handed to the evaluator,
 * and the return of {@link #nextPeriodId}. Nowhere else in this context is a period a bare {@code String}.
 */
@Component
public class TradingAccountClosingBooksPolicy {
    private final ReentrantLock            lock = new ReentrantLock();
    private final Clock                    clock;
    private final Optional<MeterRegistry>  meterRegistry;
    private volatile ClosingBooksSettings  settings;

    public TradingAccountClosingBooksPolicy(AggregateClosingBooksConfigurationResolver configurationResolver,
                                            TradingAccountClosingBooksProperties properties,
                                            Clock clock,
                                            Optional<MeterRegistry> meterRegistry) {
        requireNonNull(configurationResolver, "No configurationResolver provided");
        requireNonNull(properties, "No properties provided");
        this.clock = requireNonNull(clock, "No clock provided");
        this.meterRegistry = requireNonNull(meterRegistry, "No meterRegistry provided");

        // Resolution ladder, most specific first: an explicit application property, then whatever the
        // @AggregateClosingBooksPolicy annotation on TradingAccount resolved to, then a hardcoded fallback.
        var resolvedConfiguration = configurationResolver.resolve(TradingAccounts.AGGREGATE_TYPE,
                                                                  TradingAccount.class);
        var mode = properties.getMode() != null
                   ? properties.getMode()
                   : resolvedConfiguration.defaultPolicy() != null && resolvedConfiguration.defaultPolicy() != ClosingBooksDefaultPolicyType.UNSPECIFIED
                     ? resolvedConfiguration.defaultPolicy()
                     : ClosingBooksDefaultPolicyType.MANUAL_ONLY;
        var eventThreshold = properties.getEventThreshold() != null
                             ? properties.getEventThreshold()
                             : resolvedConfiguration.eventThreshold() != null
                               ? resolvedConfiguration.eventThreshold()
                               : 100L;
        var timeBoundary = properties.getTimeBoundary() != null
                           ? properties.getTimeBoundary()
                           : resolvedConfiguration.timeBoundary() != null
                             ? resolvedConfiguration.timeBoundary()
                             : ClosingBooksTimeBoundary.NONE;
        var zoneId = properties.getZoneId() != null && !properties.getZoneId().isBlank()
                     ? properties.getZoneId().trim()
                     : resolvedConfiguration.zoneId() != null && !resolvedConfiguration.zoneId().isBlank()
                       ? resolvedConfiguration.zoneId()
                       : "UTC";
        var intervalDays = properties.getIntervalDays() != null
                           ? properties.getIntervalDays()
                           : resolvedConfiguration.intervalDays();

        this.settings = new ClosingBooksSettings(mode,
                                                 eventThreshold,
                                                 timeBoundary,
                                                 ZoneId.of(zoneId),
                                                 intervalDays);
    }

    public boolean shouldRolloverOnAccess(TradingAccount account) {
        requireNonNull(account, "No account provided");
        return evaluator(settings).shouldRolloverOnAccess(account);
    }

    public PeriodId nextPeriodId(TradingAccount account) {
        requireNonNull(account, "No account provided");
        return PeriodId.of(evaluator(settings).nextPeriodId(account));
    }

    public String description() {
        return evaluator(settings).description();
    }

    /**
     * The configuration currently in force, as one consistent snapshot.
     */
    public ClosingBooksSettings settings() {
        return settings;
    }

    /**
     * Applies a change to the settings under the lock, so concurrent callers serialise instead of overwriting each
     * other's reads.
     */
    public void update(UnaryOperator<ClosingBooksSettings> change) {
        requireNonNull(change, "No change provided");
        lock.lock();
        try {
            settings = requireNonNull(change.apply(settings), "The change returned no settings");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code action} with {@code override} applied to the settings, then restores the previous settings.
     *
     * <p>The lock is held for the whole action, not just for the two swaps. That is the point: an overridden policy is
     * only meaningful if nothing else can retune it while the action runs, and the previous shape -- swap, run, swap
     * back -- let an admin request land in the middle and be undone by the restore.
     */
    public <T> T withTemporarySettings(UnaryOperator<ClosingBooksSettings> override,
                                       Supplier<T> action) {
        requireNonNull(override, "No override provided");
        requireNonNull(action, "No action provided");
        lock.lock();
        try {
            var previousSettings = settings;
            settings = requireNonNull(override.apply(previousSettings), "The override returned no settings");
            try {
                return action.get();
            } finally {
                settings = previousSettings;
            }
        } finally {
            lock.unlock();
        }
    }

    private BuiltInClosingBooksPolicyEvaluator<TradingAccount> evaluator(ClosingBooksSettings currentSettings) {
        return new BuiltInClosingBooksPolicyEvaluator<>(TradingAccounts.AGGREGATE_TYPE,
                                                        currentSettings.mode(),
                                                        currentSettings.eventThreshold(),
                                                        currentSettings.timeBoundary(),
                                                        currentSettings.zoneId(),
                                                        currentSettings.intervalDays(),
                                                        clock,
                                                        meterRegistry,
                                                        account -> account.eventOrderOfLastAppliedEvent().longValue() + 1,
                                                        account -> account.periodId().toString());
    }
}
