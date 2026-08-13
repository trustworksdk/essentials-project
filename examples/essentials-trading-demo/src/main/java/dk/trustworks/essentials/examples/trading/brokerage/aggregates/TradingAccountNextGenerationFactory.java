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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksAggregateInstantiationContext;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.TypedClosingBooksNextGenerationFactory;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.types.Amount;
import org.springframework.stereotype.Component;

/**
 * Defines what state a trading account carries into its next books generation: the owner and the closing cash balance
 * carry over, realized P&amp;L resets to zero because it is reported per period, and reserved funds do not carry at
 * all -- {@code AccountBooksClosed} zeroes them on the way out.
 *
 * <p>Registering this as a bean is not optional decoration. The framework validates that an aggregate with an
 * automatic close-and-open-next-generation policy has a carry-forward strategy; without it a rollover would open the
 * next generation from nothing and silently lose the account's cash.
 *
 * <p>It reads {@link TradingAccount}'s package-private accessors, which is why it lives in this package rather than
 * in {@code config/}.
 */
@Component
public class TradingAccountNextGenerationFactory implements TypedClosingBooksNextGenerationFactory<TradingAccountId, TradingAccountGenerationId, TradingAccount, PeriodId> {
    @Override
    public Class<TradingAccount> aggregateImplementationType() {
        return TradingAccount.class;
    }

    @Override
    public TradingAccount createNextGeneration(TradingAccount currentAggregate,
                                               ClosingBooksAggregateInstantiationContext<TradingAccountId, TradingAccountGenerationId> context,
                                               PeriodId nextPeriodId) {
        return new TradingAccount(context.streamAggregateId(),
                                  context.logicalAggregateId().value(),
                                  currentAggregate.ownerId(),
                                  nextPeriodId,
                                  currentAggregate.cashBalance(),
                                  Amount.ZERO);
    }
}
