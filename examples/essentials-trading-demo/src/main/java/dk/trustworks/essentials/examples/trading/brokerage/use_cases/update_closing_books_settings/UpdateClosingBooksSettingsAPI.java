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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.update_closing_books_settings;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code brokerage.update_closing_books_settings} slice (rules/slice-design.md §R2).
 *
 * <p>One endpoint, taking all five settings at once. It replaced four separate endpoints -- one per field -- which
 * could interleave with each other and with the load generator's policy-comparison scenario. Do not split it back up.
 *
 * <p>Uses {@code send} rather than {@code sendAndDontWait} so a caller that retunes the policy and then drives a
 * workload knows the workload ran under the settings it asked for.
 */
@RestController
@RequestMapping(path = "/api/admin/trading-accounts")
public class UpdateClosingBooksSettingsAPI {
    private final CommandBus commandBus;

    public UpdateClosingBooksSettingsAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/closing-books")
    public void updateClosingBooksSettings(@RequestBody UpdateClosingBooksSettings cmd) {
        commandBus.send(cmd);
    }
}
