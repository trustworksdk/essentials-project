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

package dk.trustworks.essentials.examples.trading.trades;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Transactional application service for the {@link Trade} aggregate.
 */
@Service
public class TradeService {
    private final StatefulAggregateRepository<TradeId, TradeEvent, Trade> repository;

    public TradeService(StatefulAggregateRepository<TradeId, TradeEvent, Trade> repository) {
        this.repository = repository;
    }

    @Transactional
    public Trade placeTrade(TradeId tradeId,
                            TradingAccountId accountId,
                            InstrumentId instrumentId,
                            String side,
                            BigDecimal quantity,
                            BigDecimal price) {
        return repository.save(new Trade(tradeId, accountId, instrumentId, side, quantity, price));
    }

    @Transactional
    public Trade executeTrade(TradeId tradeId) {
        var trade = repository.load(tradeId);
        trade.execute();
        return trade;
    }

    @Transactional
    public Trade requestSettlement(TradeId tradeId, String settlementId) {
        var trade = repository.load(tradeId);
        trade.requestSettlement(settlementId);
        return trade;
    }

    @Transactional
    public Trade markSettled(TradeId tradeId) {
        var trade = repository.load(tradeId);
        trade.markSettled();
        return trade;
    }

    @Transactional(readOnly = true)
    public Trade load(TradeId tradeId) {
        return repository.load(tradeId);
    }

    @Transactional(readOnly = true)
    public Optional<Trade> tryLoad(TradeId tradeId) {
        return repository.tryLoad(tradeId);
    }
}
