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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.place_trade;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trade;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trades;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.place_trade} slice -- one command, one handler
 * (rules/slice-design.md &sect;R1).
 * <p>
 * This is the only place a {@link Trade} is constructed, which is what emits {@code TradePlaced};
 * {@link Trades#placeNewTrade} only persists the already-constructed aggregate. The handler unpacks the command into
 * fields, so the aggregate never names a command type.
 */
@Service
public class PlaceTradeHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(PlaceTradeHandler.class);

    private final Trades trades;

    public PlaceTradeHandler(Trades trades) {
        this.trades = requireNonNull(trades, "No trades provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(PlaceTrade cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Placing Trade '{}'", cmd.tradeId());
        trades.placeNewTrade(new Trade(cmd.tradeId(),
                                       cmd.accountId(),
                                       cmd.instrumentId(),
                                       cmd.side(),
                                       cmd.quantity(),
                                       cmd.price()));
    }
}
