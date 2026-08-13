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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_books_and_open_next_period;

import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code brokerage.close_books_and_open_next_period} slice (rules/slice-design.md §R2).
 *
 * <p>Posting to {@code /generations} reads as "create the next generation", which is what the rollover is.
 *
 * <p>The account id is in the path <em>and</em> in the command; {@link #reconcile} is the whole adapter -- the path
 * wins when the body leaves it out, a disagreement is a 400.
 *
 * <p>Uses {@code send} rather than {@code sendAndDontWait} so the demo dashboard's generation counter has already
 * advanced when the call returns.
 */
@RestController
@RequestMapping(path = "/api/admin/trading-accounts")
public class CloseBooksAndOpenNextPeriodAPI {
    private final CommandBus commandBus;

    public CloseBooksAndOpenNextPeriodAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{accountId}/generations")
    public void closeBooksAndOpenNextPeriod(@PathVariable TradingAccountId accountId,
                                            @RequestBody CloseBooksAndOpenNextPeriod cmd) {
        commandBus.send(reconcile(accountId, cmd));
    }

    private CloseBooksAndOpenNextPeriod reconcile(TradingAccountId accountId, CloseBooksAndOpenNextPeriod cmd) {
        if (cmd.accountId() == null) return new CloseBooksAndOpenNextPeriod(accountId, cmd.nextPeriodId());
        if (!cmd.accountId().equals(accountId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId in the path '" + accountId + "' does not match the one in the body '" + cmd.accountId() + "'");
        return cmd;
    }
}
