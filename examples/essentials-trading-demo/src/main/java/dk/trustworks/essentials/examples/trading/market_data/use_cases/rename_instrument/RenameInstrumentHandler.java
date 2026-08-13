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

import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code market_data.rename_instrument} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 * <p>
 * Loads and mutates, so there is no {@code save} call: the {@code UnitOfWork} persists the events the aggregate applied
 * when it commits. Only a newly constructed aggregate goes through {@code Instruments.registerNewInstrument}.
 * <p>
 * Uses {@code getInstrument}, not {@code findInstrument}, so renaming an instrument that was never registered fails
 * rather than quietly creating one. The idempotency guard -- renaming to the name already held applies nothing -- sits
 * on {@code Instrument}, where it also covers a redelivery of this command.
 */
@Service
public class RenameInstrumentHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(RenameInstrumentHandler.class);

    private final Instruments instruments;

    public RenameInstrumentHandler(Instruments instruments) {
        this.instruments = requireNonNull(instruments, "No instruments provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(RenameInstrument cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Renaming Instrument '{}' to '{}'", cmd.instrumentId(), cmd.displayName());
        instruments.getInstrument(cmd.instrumentId())
                   .rename(cmd.displayName());
    }
}
