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

package dk.trustworks.essentials.examples.trading.accounts.use_cases.open_account;

import dk.trustworks.essentials.examples.trading.identity.types.AuthenticatedUser;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of {@code accounts.open_account}. A customer opens an account for themselves; an operator may open one
 * for anyone.
 */
@RestController
@RequestMapping(path = "/api/accounts")
public class OpenAccountAPI {
    private final CommandBus commandBus;

    public OpenAccountAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void openAccount(@RequestBody OpenAccount cmd, @AuthenticationPrincipal AuthenticatedUser caller) {
        if (!caller.isOperator() && !caller.userId().equals(cmd.ownerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A customer may only open accounts for themselves");
        }
        commandBus.send(cmd);
    }
}
