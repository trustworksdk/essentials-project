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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.update_price;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.Amount;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code market_data.update_price} slice (rules/slice-design.md §R2), and the authoritative
 * latest-price write path of the demo.
 * <p>
 * The instrument is named by the path, the new price by a request parameter, and the command is built from the two
 * here. {@code UpdatePrice} requires every component, so a JSON body carrying only the price would deserialize with a
 * null {@code instrumentId} and throw inside the record's canonical constructor before this method ran; a body type
 * without the id would be a mirror DTO, which §R2 forbids.
 * <p>
 * Both {@code @PathVariable InstrumentId} and {@code @RequestParam Amount} bind because
 * {@code TradingDemoWebConfiguration} imports {@code EssentialsWebMvcConfigurer}, which registers
 * {@code SingleValueTypeConverter} -- it converts {@code String} to {@code CharSequenceType} and to
 * {@code NumberType}, and {@code Amount} is the latter.
 * <p>
 * Uses {@code send} rather than {@code sendAndDontWait}. This is the hot path, so the temptation to fire and forget is
 * real, but the load generator ticks and then reads back, and a valuation must not race the write it was told
 * completed.
 */
@RestController
@RequestMapping(path = "/api/admin/instrument-prices")
public class UpdatePriceAPI {
    private final CommandBus commandBus;

    public UpdatePriceAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{instrumentId}")
    public void updatePrice(@PathVariable InstrumentId instrumentId,
                            @RequestParam Amount price) {
        commandBus.send(new UpdatePrice(instrumentId, price));
    }
}
