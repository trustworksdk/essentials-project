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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_trade_settled;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trades;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.mark_trade_settled} slice -- one command, one handler
 * (rules/slice-design.md &sect;R1).
 * <p>
 * The ordering guard lives on {@code Trade}: a trade cannot be marked settled before a settlement was requested for
 * it. This handler only names the step.
 */
@Service
public class MarkTradeSettledHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(MarkTradeSettledHandler.class);

    private final Trades trades;

    public MarkTradeSettledHandler(Trades trades) {
        this.trades = requireNonNull(trades, "No trades provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(MarkTradeSettled cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Marking Trade '{}' as settled", cmd.tradeId());
        trades.getTrade(cmd.tradeId()).markSettled();
    }
}
