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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.initialize_price;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code market_data.initialize_price} slice (rules/slice-design.md §R2).
 * <p>
 * The command <em>is</em> the request body -- no DTO and no mapper. The {@code InstrumentId} and {@code Amount} inside
 * it round-trip because {@code TradingDemoWebConfiguration} registers {@code EssentialTypesJacksonModule} on the web
 * {@code ObjectMapper}.
 * <p>
 * Uses {@code send} rather than {@code sendAndDontWait}: the demo's bootstrap initializes a price and then immediately
 * starts ticking it and valuing trades against it, so the stream must exist when this call returns.
 */
@RestController
@RequestMapping(path = "/api/admin/instrument-prices")
public class InitializePriceAPI {
    private final CommandBus commandBus;

    public InitializePriceAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping
    public void initializePrice(@RequestBody InitializePrice cmd) {
        commandBus.send(cmd);
    }
}
