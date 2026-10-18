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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.reverse_trade_booking;

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of {@code accounts.reverse_trade_booking}: an operator endpoint (phase 1 design §8). From phase 2 on the same
 * command is also sent by the process that owns it; the endpoint stays for operators and tests.
 */
@RestController
@RequestMapping(path = "/api/admin/accounts")
public class ReverseTradeBookingAPI {
    private final CommandBus commandBus;

    public ReverseTradeBookingAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{accountId}/trade-booking-reversals")
    public void reverse(@PathVariable AccountId accountId, @RequestBody ReverseTradeBooking cmd) {
        requireSameAccount(accountId, cmd.accountId());
        commandBus.send(cmd);
    }

    private static void requireSameAccount(AccountId inPath, AccountId inBody) {
        if (!inPath.equals(inBody)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId in the path '" + inPath + "' does not match the one in the body '" + inBody + "'");
        }
    }
}
