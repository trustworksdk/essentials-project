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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.suspend_instrument;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The API file of the {@code market_data.suspend_instrument} slice (rules/slice-design.md §R2).
 * <p>
 * The instrument is named by the path, the reason by a request parameter, and the command is built from the two here.
 * {@code SuspendInstrument} requires every component, so a JSON body carrying only the reason would deserialize with a
 * null {@code instrumentId} and throw inside the record's canonical constructor before this method ran; a body type
 * without the id would be a mirror DTO, which §R2 forbids.
 * <p>
 * The route is {@code POST .../suspension} rather than {@code .../suspend} because it creates a suspension -- and
 * because there is no un-suspend, there is deliberately no {@code DELETE} counterpart.
 * <p>
 * Uses {@code send} rather than {@code sendAndDontWait} so the suspension is visible when the call returns.
 */
@RestController
@RequestMapping(path = "/api/admin/instruments")
public class SuspendInstrumentAPI {
    private final CommandBus commandBus;

    public SuspendInstrumentAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{instrumentId}/suspension")
    public void suspend(@PathVariable InstrumentId instrumentId,
                        @RequestParam String reason) {
        commandBus.send(new SuspendInstrument(instrumentId, reason));
    }
}
