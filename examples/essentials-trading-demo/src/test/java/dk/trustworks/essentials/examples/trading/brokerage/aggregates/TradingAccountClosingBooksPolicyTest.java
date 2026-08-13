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
import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.ResolvedAggregateClosingBooksConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTriggerMode;
import dk.trustworks.essentials.examples.trading.brokerage.config.TradingAccountClosingBooksProperties;
import dk.trustworks.essentials.examples.trading.brokerage.types.ClosingBooksSettings;
import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TradingAccountClosingBooksPolicyTest {
    @Test
    void event_count_mode_rolls_when_threshold_is_reached() {
        var properties = new TradingAccountClosingBooksProperties();
        properties.setMode(ClosingBooksDefaultPolicyType.EVENT_COUNT);
        properties.setEventThreshold(2L);

        // Fixed inside the account's own period, since nextPeriodId is derived from the clock: with the system clock
        // this test only passed while the wall clock happened to be in April 2026.
        var policy = new TradingAccountClosingBooksPolicy(staticResolver(),
                                                          properties,
                                                          Clock.fixed(Instant.parse("2026-04-15T00:00:00Z"), ZoneOffset.UTC),
                                                          Optional.empty());
        var account = account("2026-04");
        account.depositCash(Amount.of(BigDecimal.TEN));

        assertThat(policy.shouldRolloverOnAccess(account)).isTrue();
        assertThat((CharSequence) policy.nextPeriodId(account)).isEqualTo(PeriodId.of("2026-04"));
    }

    @Test
    void time_boundary_mode_rolls_when_month_changes() {
        var properties = new TradingAccountClosingBooksProperties();
        properties.setMode(ClosingBooksDefaultPolicyType.TIME_BOUNDARY);
        properties.setTimeBoundary(ClosingBooksTimeBoundary.END_OF_MONTH);
        properties.setZoneId("UTC");

        var policy = new TradingAccountClosingBooksPolicy(staticResolver(),
                                                          properties,
                                                          Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC),
                                                          Optional.empty());
        var account = account("2026-04");

        assertThat(policy.shouldRolloverOnAccess(account)).isTrue();
        assertThat((CharSequence) policy.nextPeriodId(account)).isEqualTo(PeriodId.of("2026-05"));
    }

    /**
     * The five {@code volatile} fields with a setter each became one immutable {@link ClosingBooksSettings}. What the
     * old shape could not express is that a configuration change is <em>one</em> change: this asserts that
     * {@link TradingAccountClosingBooksPolicy#update} swaps the whole value at once and that
     * {@link TradingAccountClosingBooksPolicy#settings()} hands out that same value.
     */
    @Test
    void update_replaces_the_whole_settings_value_in_one_step() {
        var policy = manualOnlyPolicy();

        assertThat(policy.settings().mode()).isEqualTo(ClosingBooksDefaultPolicyType.MANUAL_ONLY);
        assertThat(policy.settings().eventThreshold()).isEqualTo(100L);
        assertThat(policy.settings().timeBoundary()).isEqualTo(ClosingBooksTimeBoundary.END_OF_MONTH);
        assertThat(policy.settings().zoneId()).isEqualTo(ZoneId.of("Europe/Copenhagen"));
        assertThat(policy.description()).isEqualTo("manual-only");

        policy.update(settings -> settings.withMode(ClosingBooksDefaultPolicyType.TIME_BOUNDARY)
                                          .withTimeBoundary(ClosingBooksTimeBoundary.END_OF_WEEK)
                                          .withZoneId(ZoneId.of("UTC")));

        assertThat(policy.settings()).isEqualTo(new ClosingBooksSettings(ClosingBooksDefaultPolicyType.TIME_BOUNDARY,
                                                                        100L,
                                                                        ClosingBooksTimeBoundary.END_OF_WEEK,
                                                                        ZoneId.of("UTC"),
                                                                        null));
        assertThat(policy.description()).contains("end-of-week").contains("UTC");
    }

    /**
     * Regression test for the sole-writer defect this refactor closed (see {@code REFACTORING_PLAN.md} § Findings this
     * refactor closes, item 1).
     *
     * <p>The previous shape captured the five values, ran the benchmark scenario, and restored them in a
     * {@code finally}. An admin request retuning the policy in the middle returned 200 and was then silently reverted
     * by that restore. So two things have to hold at once: the lock must be held for the <em>whole</em> action, not
     * just for the two swaps -- and once the action is over, the competing update must still take effect rather than
     * having been swallowed by the restore.
     */
    @Test
    void with_temporary_settings_excludes_a_concurrent_update_and_does_not_swallow_it() throws Exception {
        var policy            = manualOnlyPolicy();
        var previousSettings  = policy.settings();
        var competingUpdate   = new CountDownLatch(1);
        var updateLanded      = new CountDownLatch(1);
        var landedMidAction   = new AtomicBoolean(true);
        var observedInAction  = new AtomicReference<ClosingBooksSettings>();

        var updater = new Thread(() -> {
            competingUpdate.countDown();
            policy.update(settings -> settings.withMode(ClosingBooksDefaultPolicyType.EVENT_COUNT));
            updateLanded.countDown();
        }, "competing-closing-books-update");

        var actionResult = policy.withTemporarySettings(settings -> settings.withEventThreshold(5),
                                                        () -> {
                                                            try {
                                                                updater.start();
                                                                assertThat(competingUpdate.await(5, TimeUnit.SECONDS)).isTrue();
                                                                // The lock is held for the whole action, so the competing
                                                                // update cannot land while the override is in force.
                                                                landedMidAction.set(updateLanded.await(250, TimeUnit.MILLISECONDS));
                                                                observedInAction.set(policy.settings());
                                                                return "scenario-result";
                                                            } catch (InterruptedException e) {
                                                                Thread.currentThread().interrupt();
                                                                throw new IllegalStateException("Interrupted while running the action", e);
                                                            }
                                                        });

        updater.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(actionResult).isEqualTo("scenario-result");
        assertThat(landedMidAction)
                .describedAs("The competing update must not land while withTemporarySettings holds the lock")
                .isFalse();
        assertThat(observedInAction.get())
                .describedAs("The action must see the override, and only the override")
                .isEqualTo(previousSettings.withEventThreshold(5));

        assertThat(updateLanded.await(10, TimeUnit.SECONDS))
                .describedAs("The competing update must be applied once the action releases the lock")
                .isTrue();
        assertThat(policy.settings())
                .describedAs("The override is restored, and the competing update is applied on top of the restored value -- neither lost nor reverted")
                .isEqualTo(previousSettings.withMode(ClosingBooksDefaultPolicyType.EVENT_COUNT));
    }

    private static TradingAccountClosingBooksPolicy manualOnlyPolicy() {
        var properties = new TradingAccountClosingBooksProperties();
        properties.setMode(ClosingBooksDefaultPolicyType.MANUAL_ONLY);
        properties.setEventThreshold(100L);
        properties.setTimeBoundary(ClosingBooksTimeBoundary.END_OF_MONTH);
        properties.setZoneId("Europe/Copenhagen");
        return new TradingAccountClosingBooksPolicy(staticResolver(),
                                                    properties,
                                                    Clock.fixed(Instant.parse("2026-04-15T00:00:00Z"), ZoneOffset.UTC),
                                                    Optional.empty());
    }

    private static TradingAccount account(String periodId) {
        return new TradingAccount(TradingAccountGenerationId.of("ACC-1#1"),
                                  TradingAccountId.of("ACC-1"),
                                  OwnerId.of("owner-1"),
                                  PeriodId.of(periodId));
    }

    private static AggregateClosingBooksConfigurationResolver staticResolver() {
        return (aggregateType, aggregateImplementationType) -> new ResolvedAggregateClosingBooksConfiguration(true,
                                                                                                              ClosingBooksTriggerMode.ON_ACCESS,
                                                                                                              ClosingBooksDefaultPolicyType.MANUAL_ONLY,
                                                                                                              100L,
                                                                                                              ClosingBooksTimeBoundary.END_OF_MONTH,
                                                                                                              "Europe/Copenhagen",
                                                                                                              null);
    }
}
