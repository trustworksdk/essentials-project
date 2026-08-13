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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.rename_instrument;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code market_data.rename_instrument} slice (rules/slice-design.md §R2).
 * <p>
 * The instrument is named by the path, the new display name by a request parameter, and the command is built from the
 * two here. That shape is deliberate: {@code RenameInstrument} requires <em>every</em> component, so a JSON body
 * carrying only the new name would deserialize with a null {@code instrumentId} and throw inside the record's canonical
 * constructor before this method ran. The alternative -- a body type without the id -- would be a mirror DTO, which §R2
 * forbids. Building the command inline keeps one type, fully non-null.
 * <p>
 * The typed {@code @PathVariable InstrumentId} binds because {@code TradingDemoWebConfiguration} imports
 * {@code EssentialsWebMvcConfigurer}, which registers {@code SingleValueTypeConverter} with the {@code
 * FormatterRegistry}.
 * <p>
 * Uses {@code send} rather than {@code sendAndDontWait} so the rename is visible when the call returns -- the demo's
 * bootstrap and load generator run these in sequence.
 */
@RestController
@RequestMapping(path = "/api/admin/instruments")
public class RenameInstrumentAPI {
    private final CommandBus commandBus;

    public RenameInstrumentAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{instrumentId}/name")
    public void rename(@PathVariable InstrumentId instrumentId,
                       @RequestParam String displayName) {
        commandBus.send(new RenameInstrument(instrumentId, displayName));
    }
}
