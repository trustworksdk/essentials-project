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
 * <b>No longer raised</b> - kept for compatibility with code that refers to it.
 * <p>
 * It was raised on the ordered live sink of {@code CdcEventStore.BackfillThenLiveOrdered} when the live-tail drain had
 * been parked on a missing {@code global_event_order} for longer than {@code eventBus.liveDrainStallThreshold}. The
 * drain advanced {@code expectedNext} strictly by {@code +1} past the head and could not skip a global order that never
 * arrives on the live CDC bus (most commonly a rolled-back {@code IDENTITY} value, which writes no WAL), so this was the
 * retryable signal that re-subscribed the pipeline and resumed its gap-handler-aware backfill from
 * {@link #stalledAtGlobalOrder()}.
 * <p>
 * The drain no longer waits for a missing global order: past the head it hands live events on as the CDC bus delivers
 * them, and the subscription's delivery tracker delivers an event whose transaction commits after a higher global order
 * when it arrives, out of global order. There is nothing left to stall on.
 *
 * @deprecated never raised; planned for removal in the next major release
 */
@Deprecated(forRemoval = true)
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
