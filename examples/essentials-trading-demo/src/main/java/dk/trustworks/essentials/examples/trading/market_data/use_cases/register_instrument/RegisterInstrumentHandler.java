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

import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instrument;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code market_data.register_instrument} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 * <p>
 * This is the only slice in {@code market_data} that <em>constructs</em> an {@link Instrument}. Constructing it is what
 * emits {@code InstrumentRegistered}, and that decision belongs to a slice rather than to {@link Instruments}, which is
 * why the repository takes an already-built aggregate. Every other instrument slice loads and mutates instead.
 * <p>
 * The handler unpacks the command and passes fields; the aggregate never names a command type.
 */
@Service
public class RegisterInstrumentHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(RegisterInstrumentHandler.class);

    private final Instruments instruments;

    public RegisterInstrumentHandler(Instruments instruments) {
        this.instruments = requireNonNull(instruments, "No instruments provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(RegisterInstrument cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Registering Instrument '{}' ({})", cmd.instrumentId(), cmd.symbol());
        // A newly constructed aggregate is the one case that goes through the repository - an already-loaded one is
        // persisted by the UnitOfWork on commit.
        instruments.registerNewInstrument(new Instrument(cmd.instrumentId(),
                                                        cmd.symbol(),
                                                        cmd.displayName()));
    }
}
