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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.release_funds;

import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code brokerage.release_funds} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 *
 * <p>Releasing more than is reserved is rejected by {@code TradingAccount}, which is the only place that knows the
 * reserved total. Note that a books rollover zeroes reservations on the way out ({@code AccountBooksClosed}), so a
 * release arriving after a rollover has nothing left to release and fails -- that is the intended reading, not a bug.
 */
@Service
public class ReleaseFundsHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(ReleaseFundsHandler.class);

    private final TradingAccounts tradingAccounts;

    public ReleaseFundsHandler(TradingAccounts tradingAccounts) {
        this.tradingAccounts = requireNonNull(tradingAccounts, "No tradingAccounts provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(ReleaseFunds cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Releasing {} on TradingAccount '{}'", cmd.amount(), cmd.accountId());
        tradingAccounts.getAccountForMutation(cmd.accountId())
                       .releaseFunds(cmd.amount());
    }
}
