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

package dk.trustworks.essentials.examples.trading.brokerage.use_cases.create_settlement;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code brokerage.create_settlement} slice (rules/slice-design.md &sect;R2).
 * <p>
 * Uses {@code send} rather than {@code sendAndDontWait} because the demo harness drives a settlement straight
 * through clearing to closure in one pass and needs the write to be visible when this call returns.
 */
@RestController
@RequestMapping("/api/admin/settlements")
public class CreateSettlementAPI {
    private final CommandBus commandBus;

    public CreateSettlementAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping
    public void createSettlement(@RequestBody CreateSettlement cmd) {
        commandBus.send(cmd);
    }
}
