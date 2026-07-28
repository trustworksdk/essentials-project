/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

/**
 * Raised on the ordered live sink of {@code CdcEventStore.BackfillThenLiveOrdered} when the live-tail
 * drain has been parked on a missing {@code global_event_order} for longer than the configured
 * {@code eventBus.liveDrainStallThreshold}.
 * <p>
 * The drain advances {@code expectedNext} strictly by {@code +1} and has no way to skip a global order
 * that will never arrive on the live CDC bus (most commonly a rolled-back {@code IDENTITY} value that
 * never produces data WAL). Left alone, such a permanent hole in the live tail stalls the affected
 * subscriber forever — and, because CDC stays globally healthy, the stall never self-heals via an
 * availability flip to polling (see {@code cdc/cdc-improvements.md} §P10).
 * <p>
 * This exception is the <b>retryable</b> signal that drives the Tier-1 recovery: {@code pollEvents}
 * filters on this type in its {@code retryWhen} and re-subscribes the CDC pipeline, resuming the
 * gap-handler-aware backfill from {@link #stalledAtGlobalOrder()}. Backfill then classifies the hole
 * (transient → wait/recover, permanent → skip) using the existing {@code SubscriptionGapHandler}, which
 * is the only path proven to heal this condition. The {@code expectedNext} value at stall time is the
 * authoritative resume point: every order below it has already been emitted contiguously.
 */
public class CdcLiveDrainStalledException extends RuntimeException {
    /**
     * The {@code global_event_order} the live drain was parked on (its {@code expectedNext}) when the
     * stall was detected. Recovery resumes backfill from this value — everything below it is already
     * delivered.
     */
    private final long stalledAtGlobalOrder;

    public CdcLiveDrainStalledException(long stalledAtGlobalOrder, String message) {
        super(message);
        this.stalledAtGlobalOrder = stalledAtGlobalOrder;
    }

    /**
     * @return the {@code global_event_order} the drain was parked on (its {@code expectedNext}); the
     * authoritative resume point for recovery, since every lower order has already been emitted.
     */
    public long stalledAtGlobalOrder() {
        return stalledAtGlobalOrder;
    }
}
