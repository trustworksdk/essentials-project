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

package dk.trustworks.essentials.examples.trading.prices;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;

import java.math.BigDecimal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Latest price aggregate for one instrument.
 */
@AggregateSnapshotPolicy(aggregateType = "InstrumentPrices",
                         mode = SnapshotExecutionMode.ASYNC_DURABLE,
                         everyNEvents = 1000)
public class InstrumentPrice extends AggregateRoot<InstrumentId, InstrumentPriceEvent, InstrumentPrice> {
    public BigDecimal latestPrice;

    protected InstrumentPrice() {
    }

    /**
     * Used for rehydration.
     */
    public InstrumentPrice(InstrumentId instrumentId) {
        super(instrumentId);
    }

    public InstrumentPrice(InstrumentId instrumentId, BigDecimal initialPrice) {
        this(instrumentId);
        requireNonNull(initialPrice, "No initialPrice provided");
        apply(new InstrumentPriceEvent.PriceInitialized(instrumentId, initialPrice));
    }

    public void updatePrice(BigDecimal newPrice) {
        requireNonNull(newPrice, "No newPrice provided");
        if (latestPrice != null && latestPrice.compareTo(newPrice) == 0) {
            return;
        }
        apply(new InstrumentPriceEvent.PriceUpdated(aggregateId(), newPrice));
    }

    @EventHandler
    private void on(InstrumentPriceEvent.PriceInitialized event) {
        latestPrice = event.price;
    }

    @EventHandler
    private void on(InstrumentPriceEvent.PriceUpdated event) {
        latestPrice = event.price;
    }
}
