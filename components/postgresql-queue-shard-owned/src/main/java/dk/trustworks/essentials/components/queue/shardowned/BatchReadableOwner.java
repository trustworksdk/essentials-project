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
 * <b>What this is for, and what it is not for.</b> Parking was collapsed to one thread per pump long
 * ago; reading was not. An owner with anything to do issues three statements of its own — the cursor
 * read, the head sweep, and when its next delayed row becomes visible — so a pump serving N shards
 * issues 3N. That does not amortise, and it is what decides how large a routing space the engine can
 * afford: at sixty-four shards a queue pays 6.4 queries/s doing nothing, or roughly 1 920/s across
 * three hundred queues against 240 today.
 * <p>
 * It is an <b>idle-cost</b> mechanism. Under load it usually does nothing, and that is by design
 * elsewhere: {@code needsAttention} consumes a per-shard wake-up so a pump reads only the shard that
 * was signalled (14.5 cursor reads per message before that existed, 2.0 after), which normally leaves
 * one attentive shard per pass and nothing to batch. The win is the case where many shards' sweeps
 * fall due together — a quiet queue — so that is what it must be measured against.
 * <p>
 * <b>Each shard still gets a full batch.</b> The per-shard limit is not a share of one batch. Splitting
 * it makes the batched read return fewer rows per shard than the per-shard read it replaces, so the
 * two stop being interchangeable and an owner sees a different amount of its own backlog depending on
 * how many of its siblings happened to be attentive that pass. {@code ShardOwnedBatchedReadIT} pins
 * the equivalence.
 * <p>
 * <b>The batch is an optimisation, never a requirement.</b> An owner handed no batch reads for itself
 * exactly as it always did — which is what lets {@link ShardPump} fall back to per-owner reads if the
 * wide statement fails, and what keeps the owner testable without a pump.
 */
interface BatchReadableOwner extends LeasedOwner {

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
     * @param nextVisibleMillis when this shard's earliest delayed row becomes visible; meaningful only
     *                          when a sweep was requested
     */
    void applyBatchRead(List<ShardOwnedStorage.OrderedRow> cursorRows,
                        List<ShardOwnedStorage.OrderedRow> sweptRows,
                        OptionalLong nextVisibleMillis);
}
