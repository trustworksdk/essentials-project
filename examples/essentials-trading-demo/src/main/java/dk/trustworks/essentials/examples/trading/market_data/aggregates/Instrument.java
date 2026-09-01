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
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRiskApproved;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRiskRejected;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentSuspended;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.RiskRating;
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
 * <p>Every mutating method is an idempotent no-op rather than an error: renaming to the current display name,
 * suspending an already-suspended instrument, and recording a risk decision on an instrument that already has one all
 * return without applying anything. A repeated command therefore leaves no trace in the stream instead of growing it.
 * For the risk decision that idempotency is not a nicety -- see {@link #recordRiskApproval}.
 *
 * <p>Reached through {@link Instruments}. Commands are unpacked by the slice that handles them, so this class never
 * names a command type.
 */
public class Instrument extends AggregateRoot<InstrumentId, InstrumentEvent, Instrument> {
    private Symbol  symbol;
    private String  displayName;
    private boolean suspended;
    private String  suspensionReason;
    private boolean riskAssessed;

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

    /**
     * Records that an external risk assessment cleared this instrument.
     *
     * <p>The no-op on an instrument that already carries a risk decision is what makes the
     * {@code market_data.risk_approve_instrument} automation safe. That automation calls the risk service <em>outside</em>
     * any transaction, so the call can succeed and the transaction that records its outcome still fail -- after which the
     * triggering {@code InstrumentRegistered} is redelivered and the whole handler runs again. Without this guard the
     * second run would append a second decision to the stream.
     *
     * <p>The first decision therefore stands, exactly as the first suspension reason does.
     */
    public void recordRiskApproval(RiskRating riskRating) {
        requireNonNull(riskRating, "No riskRating provided");
        if (riskAssessed) {
            return;
        }
        apply(new InstrumentRiskApproved(aggregateId(), riskRating));
    }

    /**
     * Records that an external risk assessment refused to clear this instrument. Idempotent for the same reason as
     * {@link #recordRiskApproval}.
     */
    public void recordRiskRejection(String reason) {
        requireNonNull(reason, "No reason provided");
        if (riskAssessed) {
            return;
        }
        apply(new InstrumentRiskRejected(aggregateId(), reason));
    }

    @EventHandler
    private void on(InstrumentRegistered e) {
        symbol = e.symbol();
        displayName = e.displayName();
        suspended = false;
        suspensionReason = null;
        riskAssessed = false;
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

    @EventHandler
    private void on(InstrumentRiskApproved e) {
        riskAssessed = true;
    }

    @EventHandler
    private void on(InstrumentRiskRejected e) {
        riskAssessed = true;
    }
}
