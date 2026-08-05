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
import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.ResolvedAggregateClosingBooksConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTriggerMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;

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
        var account = new TradingAccount(TradingAccountGenerationId.of("ACC-1#1"),
                                         TradingAccountId.of("ACC-1"),
                                         "owner-1",
                                         "2026-04");
        account.depositCash(BigDecimal.TEN);

        assertThat(policy.shouldRolloverOnAccess(account)).isTrue();
        assertThat(policy.nextPeriodId(account)).isEqualTo("2026-04");
    }

    @Test
    void time_boundary_mode_rolls_when_month_changes() {
        var properties = new TradingAccountClosingBooksProperties();
        properties.setMode(ClosingBooksDefaultPolicyType.TIME_BOUNDARY);
        properties.setTimeBoundary(ClosingBooksTimeBoundary.END_OF_MONTH);
        properties.setZoneId("UTC");

        var policy = new TradingAccountClosingBooksPolicy(staticResolver(), properties, Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC), Optional.empty());
        var account = new TradingAccount(TradingAccountGenerationId.of("ACC-1#1"),
                                         TradingAccountId.of("ACC-1"),
                                         "owner-1",
                                         "2026-04");

        assertThat(policy.shouldRolloverOnAccess(account)).isTrue();
        assertThat(policy.nextPeriodId(account)).isEqualTo("2026-05");
    }

    private AggregateClosingBooksConfigurationResolver staticResolver() {
        return (aggregateType, aggregateImplementationType) -> new ResolvedAggregateClosingBooksConfiguration(true,
                                                                                                              ClosingBooksTriggerMode.ON_ACCESS,
                                                                                                              ClosingBooksDefaultPolicyType.MANUAL_ONLY,
                                                                                                              100L,
                                                                                                              ClosingBooksTimeBoundary.END_OF_MONTH,
                                                                                                              "Europe/Copenhagen",
                                                                                                              null);
    }
}
