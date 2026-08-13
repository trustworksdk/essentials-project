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

import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrice;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrices;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code market_data.initialize_price} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 * <p>
 * This is the only slice that <em>constructs</em> an {@link InstrumentPrice}, which is what emits
 * {@code PriceInitialized} and opens the stream. {@code update_price} loads and mutates an existing one.
 * <p>
 * The handler unpacks the command and passes fields; the aggregate never names a command type.
 */
@Service
public class InitializePriceHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(InitializePriceHandler.class);

    private final InstrumentPrices instrumentPrices;

    public InitializePriceHandler(InstrumentPrices instrumentPrices) {
        this.instrumentPrices = requireNonNull(instrumentPrices, "No instrumentPrices provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(InitializePrice cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Initializing Price for Instrument '{}' at {}", cmd.instrumentId(), cmd.price());
        // A newly constructed aggregate is the one case that goes through the repository - an already-loaded one is
        // persisted by the UnitOfWork on commit.
        instrumentPrices.initializeNewPrice(new InstrumentPrice(cmd.instrumentId(),
                                                               cmd.price()));
    }
}
