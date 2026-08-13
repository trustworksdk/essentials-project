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
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentEvent;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRegistered;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRenamed;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentSuspended;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An instrument's reference data, and the consistency boundary for every change to it.
 *
 * <p>An event-sourced {@link AggregateRoot}: its methods do not assign state, they {@code apply} an
 * {@link InstrumentEvent}, and the {@code @EventHandler} methods at the bottom are the only place the fields are ever
 * written. The same handlers run when the aggregate is rehydrated from its stream, so replaying history and handling a
 * new command follow the identical path.
 *
 * <p>This is the demo's baseline aggregate: it relies on neither snapshots nor closing books, so it is the one to
 * compare the other two against. Its stream is short by construction -- an instrument is registered once, occasionally
 * renamed, and at most once suspended.
 *
 * <p>Both mutating methods are idempotent no-ops rather than errors: renaming to the current display name, and
 * suspending an already-suspended instrument, return without applying anything. A repeated command therefore leaves no
 * trace in the stream instead of growing it.
 *
 * <p>Reached through {@link Instruments}. Commands are unpacked by the slice that handles them, so this class never
 * names a command type.
 */
public class Instrument extends AggregateRoot<InstrumentId, InstrumentEvent, Instrument> {
    private Symbol  symbol;
    private String  displayName;
    private boolean suspended;
    private String  suspensionReason;

    /**
     * Used for rehydration
     */
    public Instrument(InstrumentId aggregateId) {
        super(aggregateId);
    }

    public Instrument(InstrumentId instrumentId,
                      Symbol symbol,
                      String displayName) {
        super(instrumentId);
        requireNonNull(symbol, "No symbol provided");
        requireNonNull(displayName, "No displayName provided");

        apply(new InstrumentRegistered(instrumentId,
                                       symbol,
                                       displayName));
    }

    public void rename(String displayName) {
        requireNonNull(displayName, "No displayName provided");
        if (displayName.equals(this.displayName)) {
            return;
        }
        apply(new InstrumentRenamed(aggregateId(), displayName));
    }

    public void suspend(String reason) {
        requireNonNull(reason, "No reason provided");
        if (suspended) {
            return;
        }
        apply(new InstrumentSuspended(aggregateId(), reason));
    }

    @EventHandler
    private void on(InstrumentRegistered e) {
        symbol = e.symbol();
        displayName = e.displayName();
        suspended = false;
        suspensionReason = null;
    }

    @EventHandler
    private void on(InstrumentRenamed e) {
        displayName = e.displayName();
    }

    @EventHandler
    private void on(InstrumentSuspended e) {
        suspended = true;
        suspensionReason = e.reason();
    }
}
