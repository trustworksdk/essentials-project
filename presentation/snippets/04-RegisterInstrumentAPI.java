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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.register_instrument;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code market_data.register_instrument} slice (rules/slice-design.md §R2).
 * <p>
 * The command <em>is</em> the request body -- no DTO and no mapper. The typed {@code InstrumentId} and {@code Symbol}
 * inside it round-trip because {@code TradingDemoWebConfiguration} registers {@code EssentialTypesJacksonModule} on the
 * web {@code ObjectMapper}.
 * <p>
 * Uses {@code send} rather than {@code sendAndDontWait}: the demo's bootstrap registers an instrument and then
 * immediately initializes its price and places trades against it, so the write must be visible when this call returns.
 */
@RestController
@RequestMapping(path = "/api/admin/instruments")
public class RegisterInstrumentAPI {
    private final CommandBus commandBus;

    public RegisterInstrumentAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping
    public void registerInstrument(@RequestBody RegisterInstrument cmd) {
        commandBus.send(cmd);
    }
}
