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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_settlement_settled;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Settlements;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.mark_settlement_settled} slice -- one command, one handler
 * (rules/slice-design.md &sect;R1).
 * <p>
 * The ordering guard (settlement cannot complete before clearing was confirmed) lives on {@code Settlement}. This
 * slice touches only the {@code Settlement} aggregate; marking the {@code Trade} settled is
 * {@code mark_trade_settled}, a separate consistency boundary.
 */
@Service
public class MarkSettlementSettledHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(MarkSettlementSettledHandler.class);

    private final Settlements settlements;

    public MarkSettlementSettledHandler(Settlements settlements) {
        this.settlements = requireNonNull(settlements, "No settlements provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(MarkSettlementSettled cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Marking as settled Settlement '{}'", cmd.settlementId());
        settlements.getSettlement(cmd.settlementId()).markSettled();
    }
}
