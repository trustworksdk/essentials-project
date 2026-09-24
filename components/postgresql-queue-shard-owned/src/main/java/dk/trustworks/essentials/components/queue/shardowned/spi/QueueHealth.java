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

package dk.trustworks.essentials.components.queue.shardowned.spi;

/**
 * Whether a queue is actually being served, seen from the database rather than from one process.
 *
 * <h2>Why this is separate from depth</h2>
 * Depth answers "how much work is waiting". It cannot answer "is anybody doing it" — a queue with
 * nobody consuming and a queue that is merely busy look identical from depth alone, and they diverge
 * only slowly, long after the cause. Every failure this engine has had in that family — a fair share
 * computed against instances that had departed, a shard count two processes disagreed about, a
 * consumer that shed shards to nobody — showed up first as shards with no live owner, and was
 * invisible in every metric that existed at the time.
 * <p>
 * {@code unownedShards} is therefore the number to alert on. In steady state it is zero; it is briefly
 * non-zero while shards move between instances, and persistently non-zero means messages are sitting
 * in shards nobody is reading.
 *
 * @param shardCount     shards configured for this queue, per lane
 * @param unorderedOwned unordered shards with a live lease right now
 * @param orderedOwned   ordered shards with a live lease right now
 * @param liveInstances  instances heartbeating for this queue. Two processes sharing an instance id
 *                       count as one, which halves the share each is allowed to hold — so a value
 *                       below the number of running processes is itself a finding
 */
public record QueueHealth(int shardCount,
                          int orderedUnits,
                          int unorderedOwned,
                          int orderedOwned,
                          int liveInstances) {

    /**
     * Shards, across both lanes, that no live instance is reading. Alert on this being persistently
     * above zero.
     * <p>
     * The two lanes are counted against DIFFERENT totals. The unordered lane has {@code shardCount}
     * shards, which the operator chose and can grow; the ordered lane has a fixed
     * {@code orderedUnits}, because its routing space cannot depend on a number that changes. Summing
     * one of them twice — which this did while the counts happened to be equal — reports a healthy
     * queue as short of owners, or a stranded one as fine.
     */
    public int unownedShards() {
        return Math.max(0, (shardCount + orderedUnits) - unorderedOwned - orderedOwned);
    }

    /**
     * True when every shard of both lanes has a live owner.
     */
    public boolean fullyOwned() {
        return unownedShards() == 0;
    }
}
