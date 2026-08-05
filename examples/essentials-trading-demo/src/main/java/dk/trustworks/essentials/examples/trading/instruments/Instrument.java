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

package dk.trustworks.essentials.examples.trading.instruments;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Instrument aggregate used as the baseline example that relies on neither snapshots nor closing books.
 */
public class Instrument extends AggregateRoot<InstrumentId, InstrumentEvent, Instrument> {
    public String symbol;
    public String displayName;
    public boolean suspended;
    public String suspensionReason;

    protected Instrument() {
    }

    /**
     * Used for rehydration.
     */
    public Instrument(InstrumentId instrumentId) {
        super(instrumentId);
    }

    public Instrument(InstrumentId instrumentId,
                      String symbol,
                      String displayName) {
        this(instrumentId);
        requireNonNull(symbol, "No symbol provided");
        requireNonNull(displayName, "No displayName provided");

        apply(new InstrumentEvent.InstrumentRegistered(instrumentId,
                                                       symbol,
                                                       displayName));
    }

    public void rename(String newDisplayName) {
        requireNonNull(newDisplayName, "No newDisplayName provided");
        if (newDisplayName.equals(displayName)) {
            return;
        }
        apply(new InstrumentEvent.InstrumentRenamed(aggregateId(), newDisplayName));
    }

    public void suspend(String reason) {
        requireNonNull(reason, "No reason provided");
        if (suspended) {
            return;
        }
        apply(new InstrumentEvent.InstrumentSuspended(aggregateId(), reason));
    }

    @EventHandler
    private void on(InstrumentEvent.InstrumentRegistered event) {
        symbol = event.symbol;
        displayName = event.displayName;
        suspended = false;
        suspensionReason = null;
    }

    @EventHandler
    private void on(InstrumentEvent.InstrumentRenamed event) {
        displayName = event.displayName;
    }

    @EventHandler
    private void on(InstrumentEvent.InstrumentSuspended event) {
        suspended = true;
        suspensionReason = event.reason;
    }
}
