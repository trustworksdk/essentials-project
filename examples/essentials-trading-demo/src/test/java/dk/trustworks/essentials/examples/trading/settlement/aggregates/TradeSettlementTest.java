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

package dk.trustworks.essentials.examples.trading.settlement.aggregates;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.settlement.events.*;
import dk.trustworks.essentials.examples.trading.settlement.types.*;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class TradeSettlementTest {
    private static final ExecutionId EXECUTION = ExecutionId.of("EX-1");
    private static final LocalDate   T_PLUS_1  = LocalDate.of(2030, 1, 8);

    private static TradeSettlement instructed() {
        return new TradeSettlement(EXECUTION, AccountId.of("ACC-1"), InstrumentId.of("ACME"), TradeSide.BUY, usd("100.10"), T_PLUS_1);
    }

    private static Money usd(String amount) {
        return Money.of(amount, CurrencyCode.USD);
    }

    @Test
    void a_delayed_settlement_settles_once_and_nothing_after_it_changes_the_outcome() {
        var settlement = instructed();
        settlement.postpone(T_PLUS_1.plusDays(1), 1);
        settlement.settle();
        settlement.settle();
        settlement.abandon("FAILED", "SIMULATED_FAILURE"); // after the settlement: nothing
        settlement.postpone(T_PLUS_1.plusDays(3), 2);       // late: nothing

        assertThat(settlement.getUncommittedChanges().events).hasExactlyElementsOfTypes(SettlementInstructed.class, SettlementPostponed.class, SettlementSettled.class);
        assertThat(settlement.settlementState()).isEqualTo(SettlementState.SETTLED);
    }

    @Test
    void an_abandoned_settlement_never_settles() {
        var settlement = instructed();
        settlement.abandon("CANCELLED", "SIMULATED_CANCELLATION");
        settlement.settle();

        assertThat(settlement.getUncommittedChanges().events).hasExactlyElementsOfTypes(SettlementInstructed.class, SettlementAbandoned.class);
    }

    @Test
    void a_correction_waits_for_its_settlement_and_is_recorded_once() {
        var settlement = instructed();
        assertThatThrownBy(() -> settlement.correctAmount(usd("100.10"), usd("100.09"))).isInstanceOf(SettlementNotYetRecordable.class)
                                                                                        .isNotInstanceOf(IllegalArgumentException.class);
        settlement.settle();
        settlement.correctAmount(usd("100.10"), usd("100.09"));
        settlement.correctAmount(usd("100.10"), usd("100.09"));

        assertThat(settlement.getUncommittedChanges().events).filteredOn(SettlementAmountChanged.class::isInstance).singleElement()
                                                             .satisfies(e -> assertThat(((SettlementAmountChanged) e).amount()).isEqualTo(usd("100.09")));
    }
}
