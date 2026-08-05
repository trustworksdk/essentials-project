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

import dk.trustworks.essentials.examples.trading.prices.InstrumentPriceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Read-only query service used by the demo admin API to inspect trades and their valuation.
 */
@Service
public class TradeAdminQueryService {
    private final TradeService tradeService;
    private final InstrumentPriceService instrumentPriceService;

    public TradeAdminQueryService(TradeService tradeService,
                                  InstrumentPriceService instrumentPriceService) {
        this.tradeService = tradeService;
        this.instrumentPriceService = instrumentPriceService;
    }

    @Transactional(readOnly = true)
    public TradeAdminView getTradeView(TradeId tradeId) {
        var trade = tradeService.load(tradeId);
        var latestMarketPrice = instrumentPriceService.tryLoad(trade.instrumentId)
                                                      .map(instrumentPrice -> instrumentPrice.latestPrice)
                                                      .orElse(null);
        var marketValue = latestMarketPrice != null ? latestMarketPrice.multiply(trade.quantity) : null;
        var unrealizedPnl = latestMarketPrice != null ? calculateUnrealizedPnl(trade, latestMarketPrice) : null;

        return new TradeAdminView(trade.aggregateId().toString(),
                                  trade.accountId.toString(),
                                  trade.instrumentId.toString(),
                                  trade.side,
                                  trade.quantity,
                                  trade.price,
                                  trade.grossAmount,
                                  trade.executed,
                                  trade.settlementRequested,
                                  trade.settled,
                                  trade.settlementId,
                                  latestMarketPrice,
                                  marketValue,
                                  unrealizedPnl);
    }

    private BigDecimal calculateUnrealizedPnl(Trade trade, BigDecimal latestMarketPrice) {
        var priceDelta = latestMarketPrice.subtract(trade.price);
        if ("SELL".equalsIgnoreCase(trade.side)) {
            priceDelta = priceDelta.negate();
        }
        return priceDelta.multiply(trade.quantity);
    }
}
