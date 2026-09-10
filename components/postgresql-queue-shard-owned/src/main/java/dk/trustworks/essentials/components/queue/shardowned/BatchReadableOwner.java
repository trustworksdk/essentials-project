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

package dk.trustworks.essentials.components.queue.shardowned;

import java.util.*;

/**
 * An owner whose reads can be issued for many shards in one statement.
 * <p>
 * <b>What this is for.</b> Parking was collapsed to one thread per pump long ago; reading was not. An
 * owner with anything to do issues three statements of its own — the cursor read, the head sweep, and
 * when its next delayed row becomes visible — so a pump serving N shards issued 3N. That does not
 * amortise, and it is what decides how large a routing space the engine can afford: at
 * {@code ORDERED_UNITS} of sixty-four, a queue pays 6.4 queries/s doing nothing, against 0.8 at eight.
 * <p>
 * It is an <b>idle-cost</b> mechanism. Under load it usually does nothing, by design elsewhere:
 * {@code needsAttention} consumes a per-shard wake-up so a pump reads only the shard that was
 * signalled, which normally leaves one attentive owner per pass and nothing to batch. The win is the
 * case where many shards' sweeps fall due together — a quiet queue — which is exactly the case that
 * decides the routing space's cost, and what {@code ShardOwnedOrderedIdleCostIT} gates.
 * <p>
 * <b>A pump is shared by every queue in the process, so a batch is per QUEUE.</b> This is the trap
 * that cost two debugging sessions: {@code ShardRuntime} hands each pump a storage handle bound to
 * queue id 0 — "the pumps only use it to open connections" — so issuing a batched statement on it
 * queried queue 0 and matched nothing, silently. The cursor read then returned no rows and every
 * message arrived by the per-shard sweep; worse, the batched sweep returned no rows while the owner
 * recorded that it had swept, which suppressed the real sweep and doubled the backoff on every empty
 * pass. Hence {@link #queueId()}, and hence the pump grouping by it.
 * <p>
 * <b>Each shard still gets a full batch.</b> The per-shard limit is not a share of one batch:
 * splitting it makes the batched read return fewer rows per shard than the per-shard read it
 * replaces, so an owner sees a different amount of its own backlog depending on how many siblings
 * happened to be attentive. {@code ShardOwnedBatchedReadIT} pins the equivalence.
 * <p>
 * <b>The batch is an optimisation, never a requirement.</b> An owner handed no batch reads for itself
 * exactly as it always did, which is what lets {@link ShardPump} fall back if the wide statement
 * fails, and what keeps the owner testable without a pump.
 */
interface BatchReadableOwner extends LeasedOwner {

    /** The queue this owner's rows live in. A pump serves several; a batched statement binds one. */
    short queueId();

    /**
     * This owner's queue-scoped counters, which are NOT the pump's.
     * <p>
     * {@code ShardRuntime} builds its own {@link ShardOwnerMetrics} for the pumps, so anything a pump
     * counts on its own handle is invisible to {@code queue.metrics()} — the same shape of mistake as
     * binding the pump's queue id. Work done on a queue's behalf is counted on that queue.
     */
    ShardOwnerMetrics ownerMetrics();

    /** Where this owner's next cursor read would start. */
    long batchReadCursor();

    /** Whether this owner would also sweep from the head this pass. */
    boolean batchSweepDue();

    /**
     * Hand this owner the rows the pump read on its behalf. Consumed by the next
     * {@link #pumpOnce(java.sql.Connection)} and then discarded.
     *
     * @param cursorRows        rows above {@link #batchReadCursor()}, empty if there were none
     * @param sweptRows         rows from the head, or {@code null} when no sweep was requested —
     *                          "did not sweep" and "swept and found nothing" are different, and only
     *                          the second may reset the sweep backoff
     * @param nextVisibleMillis when this shard's earliest delayed row becomes visible; meaningful
     *                          only when a sweep was requested
     */
    void applyBatchRead(List<ShardOwnedStorage.OrderedRow> cursorRows,
                        List<ShardOwnedStorage.OrderedRow> sweptRows,
                        OptionalLong nextVisibleMillis);
}
