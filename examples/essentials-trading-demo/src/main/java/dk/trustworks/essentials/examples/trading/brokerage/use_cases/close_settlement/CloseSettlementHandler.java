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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_settlement;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Settlements;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.close_settlement} slice -- one command, one handler
 * (rules/slice-design.md &sect;R1).
 * <p>
 * Two guards on {@code Settlement} apply: it must still be open, and it must have been reconciled. The first is what
 * makes a repeat of this command an error rather than a no-op -- unlike every other step in the lifecycle.
 */
@Service
public class CloseSettlementHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(CloseSettlementHandler.class);

    private final Settlements settlements;

    public CloseSettlementHandler(Settlements settlements) {
        this.settlements = requireNonNull(settlements, "No settlements provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(CloseSettlement cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Closing Settlement '{}'", cmd.settlementId());
        settlements.getSettlement(cmd.settlementId()).closeSettlement();
    }
}
