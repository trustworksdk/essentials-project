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

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code brokerage.close_settlement} slice (rules/slice-design.md &sect;R2).
 * <p>
 * The command carries only the aggregate id, so the endpoint takes it as a typed {@link SettlementId} path variable
 * and assembles the command inline -- assembly, which &sect;R2 allows, not a DTO. The typed binding works because
 * {@code config/TradingDemoWebConfiguration} imports {@code EssentialsWebMvcConfigurer}.
 * <p>
 * Uses {@code send} rather than {@code sendAndDontWait}: the demo harness runs these calls in sequence and depends
 * on each write being visible when the call returns.
 */
@RestController
@RequestMapping("/api/admin/settlements")
public class CloseSettlementAPI {
    private final CommandBus commandBus;

    public CloseSettlementAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{settlementId}/closure")
    public void closeSettlement(@PathVariable SettlementId settlementId) {
        commandBus.send(new CloseSettlement(settlementId));
    }
}
