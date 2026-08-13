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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.use_cases.open_account;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code banking.open_account} slice (rules/slice-design.md §R2).
 * <p>
 * Uses {@code send} rather than {@code sendAndDontWait} so a caller knows the account exists before it tries to
 * transfer money into it. The {@code account_balance} view is projected asynchronously and will lag slightly behind
 * this call.
 */
@RestController
@RequestMapping(path = "/banking")
public class OpenAccountAPI {
    private final CommandBus commandBus;

    public OpenAccountAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/open-account")
    public void openAccount(@RequestBody OpenAccount cmd) {
        commandBus.send(cmd);
    }
}
