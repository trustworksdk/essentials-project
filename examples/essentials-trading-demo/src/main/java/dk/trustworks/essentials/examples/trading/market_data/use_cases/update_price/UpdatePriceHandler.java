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

import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrices;
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code market_data.update_price} slice -- one command, one handler
 * (rules/slice-design.md §R1).
 * <p>
 * <b>This is the authoritative latest-price write path.</b> Everything that needs the current market price resolves,
 * directly or through a slice, to the {@code InstrumentPrice} aggregate this handler mutates. The demo's direct-write
 * JDBC price table is a benchmark comparison artifact living in the demo harness -- it is not part of this context and
 * is deliberately not referenced from here.
 * <p>
 * Loads and mutates, so there is no {@code save} call: the {@code UnitOfWork} persists the applied events on commit.
 * Only {@code initialize_price} constructs a new aggregate.
 * <p>
 * Uses {@code getPrice}, not {@code findPrice}, so a tick for an instrument whose price stream was never initialized
 * fails rather than quietly opening one at an arbitrary price.
 */
@Service
public class UpdatePriceHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(UpdatePriceHandler.class);

    private final InstrumentPrices instrumentPrices;

    public UpdatePriceHandler(InstrumentPrices instrumentPrices) {
        this.instrumentPrices = requireNonNull(instrumentPrices, "No instrumentPrices provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(UpdatePrice cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.trace("===> Updating Price for Instrument '{}' to {}", cmd.instrumentId(), cmd.price());
        instrumentPrices.getPrice(cmd.instrumentId())
                        .updatePrice(cmd.price());
    }
}
