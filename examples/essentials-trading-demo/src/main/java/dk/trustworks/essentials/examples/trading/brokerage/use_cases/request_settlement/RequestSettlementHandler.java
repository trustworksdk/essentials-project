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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_settlement;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trades;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.request_settlement} slice -- one command, one handler
 * (rules/slice-design.md &sect;R1).
 * <p>
 * The ordering guard (a settlement cannot be requested before the trade was executed) lives on {@code Trade}, not
 * here: it is an invariant of the aggregate's lifecycle, and it throws before any event is applied so a rejected
 * command leaves no trace in the stream.
 * <p>
 * This only records the request on the {@code Trade}. Creating the {@code Settlement} aggregate is
 * {@code create_settlement}'s job -- the two are separate consistency boundaries and nothing writes both in one
 * transaction.
 */
@Service
public class RequestSettlementHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestSettlementHandler.class);

    private final Trades trades;

    public RequestSettlementHandler(Trades trades) {
        this.trades = requireNonNull(trades, "No trades provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(RequestSettlement cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Requesting Settlement '{}' for Trade '{}'", cmd.settlementId(), cmd.tradeId());
        trades.getTrade(cmd.tradeId()).requestSettlement(cmd.settlementId());
    }
}
