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

import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code market_data.suspend_instrument} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 * <p>
 * Loads and mutates, so there is no {@code save} call: the {@code UnitOfWork} persists the applied events on commit.
 * <p>
 * Suspending an already-suspended instrument applies nothing, so the <em>first</em> reason is the one that survives.
 * That guard sits on {@code Instrument}, not here, because it has to hold for a redelivered command too.
 */
@Service
public class SuspendInstrumentHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(SuspendInstrumentHandler.class);

    private final Instruments instruments;

    public SuspendInstrumentHandler(Instruments instruments) {
        this.instruments = requireNonNull(instruments, "No instruments provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(SuspendInstrument cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Suspending Instrument '{}': {}", cmd.instrumentId(), cmd.reason());
        instruments.getInstrument(cmd.instrumentId())
                   .suspend(cmd.reason());
    }
}
