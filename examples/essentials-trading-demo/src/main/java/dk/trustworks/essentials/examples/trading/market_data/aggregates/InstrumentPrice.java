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

package dk.trustworks.essentials.examples.trading.market_data.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicy;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.SnapshotExecutionMode;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentPriceEvent;
import dk.trustworks.essentials.examples.trading.market_data.events.PriceInitialized;
import dk.trustworks.essentials.examples.trading.market_data.events.PriceUpdated;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The authoritative latest market price for one instrument, and the consistency boundary for every change to it.
 *
 * <p>An event-sourced {@link AggregateRoot}: {@link #updatePrice} does not assign {@code latestPrice}, it
 * {@code apply}s a {@link PriceUpdated}, and the {@code @EventHandler} methods at the bottom are the only place the
 * field is ever written.
 *
 * <p>Keyed by {@link InstrumentId} rather than a price id of its own -- a price stream <em>is</em> the instrument's
 * identity, under a different {@code AggregateType}. That is deliberate: it means the price for an instrument is
 * reachable without a lookup, and that {@code Instrument} and {@code InstrumentPrice} stay separate consistency
 * boundaries despite sharing an id. No transaction writes both.
 *
 * <p>This is the demo's high-write-rate aggregate, which is why it declares a snapshot policy and
 * {@code Instrument} does not. A repeated tick at the price already held emits nothing, so an unchanged market does
 * not lengthen the stream.
 *
 * <p>Both numbers on that policy are load-bearing. {@code everyNEvents} sits well below the demo's own price-stress
 * run sizes: the admin console's "Max Throughput" button issues 1000 updates across an
 * {@code trading-demo.simulation.instrument-count} of 2, so each stream only reaches ~500 events -- at a threshold of
 * 1000 a full stress run never crossed it and the snapshot metrics stayed empty. 100 matches {@code TradingAccount}'s
 * cadence and snapshots several times per run. The mode stays {@link SnapshotExecutionMode#ASYNC_DURABLE} as the
 * intended contrast to {@code TradingAccount}'s synchronous policy: the write path is not charged for the snapshot,
 * at the cost of the snapshot landing slightly behind the stream.
 */
@AggregateSnapshotPolicy(aggregateType = "InstrumentPrices",
                         mode = SnapshotExecutionMode.ASYNC_DURABLE,
                         everyNEvents = 100)
public class InstrumentPrice extends AggregateRoot<InstrumentId, InstrumentPriceEvent, InstrumentPrice> {
    private Amount latestPrice;

    /**
     * Used for rehydration
     */
    public InstrumentPrice(InstrumentId aggregateId) {
        super(aggregateId);
    }

    public InstrumentPrice(InstrumentId instrumentId,
                           Amount initialPrice) {
        super(instrumentId);
        requireNonNull(initialPrice, "No initialPrice provided");
        apply(new PriceInitialized(instrumentId, initialPrice));
    }

    public void updatePrice(Amount newPrice) {
        requireNonNull(newPrice, "No newPrice provided");
        if (latestPrice != null && latestPrice.compareTo(newPrice) == 0) {
            return;
        }
        apply(new PriceUpdated(aggregateId(), newPrice));
    }

    /**
     * The current market price.
     *
     * <p><strong>A deliberate, documented exception to "aggregates do not expose state to the read side."</strong>
     * Everywhere else in this demo a view slice projects the events rather than reading the write model. Here it
     * genuinely cannot: the trade-valuation read model needs the market price <em>as of now</em>, not as of the last
     * event it happened to have processed, and this aggregate is the authoritative store of that value. Reading it
     * through the aggregate is the honest way to say so.
     *
     * <p>This is not a licence to add sibling accessors. Nothing else on this aggregate is public, and nothing on
     * {@code Instrument} is.
     */
    public Amount latestPrice() {
        return latestPrice;
    }

    @EventHandler
    private void on(PriceInitialized e) {
        latestPrice = e.price();
    }

    @EventHandler
    private void on(PriceUpdated e) {
        latestPrice = e.price();
    }
}
