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
 * How much a pull session takes ownership of at once.
 * <p>
 * Exposed as a parameter rather than hidden, because the alternatives differ only in granularity and
 * choosing wrongly is expensive in a way that is invisible from the call site. A slice of n messages
 * is n message leases; a shard lease is the same idea one level up. The write cost follows directly:
 * message-granularity leases must be recorded, shard-granularity ones are already recorded.
 * <p>
 * The default is derived from the message rather than configured, because for ordered messages the
 * choice is not a preference. A puller holding individual messages of a key cannot stop the shard's
 * owner dispatching a later one concurrently — the per-key in-flight set that enforces order lives in
 * the owner's memory, and the puller is elsewhere. Ordered pull therefore requires {@link #SHARD}.
 *
 * <h2>Why there is no KEY scope</h2>
 * The taxonomy this enum comes from has a per-key rung: "ordering safe, blocks only that key". It is
 * absent here rather than present-and-throwing, because it cannot be built in this engine and a
 * constant that always throws is a worse API than one that does not exist.
 * <p>
 * Per-key order here is enforced by an in-memory set of the keys a shard's owner currently has in
 * flight. A session in another process cannot enter that set, and for it to take one key safely the
 * owner would have to re-check the database before dispatching each key — a query per message on the
 * ordered fast path, which is exactly the cost ordering-by-ownership exists to avoid. The rung is
 * free in a claim-based queue, where every dispatch already reads and writes; it is not free here.
 * On the ordered lane the unit of exclusivity <em>is</em> the shard, so {@link #SHARD} is the answer
 * rather than a fallback.
 */
public enum SessionScope {
    /**
     * One message per lease. Unordered lane only. A session never holds more than the message it is
     * working on, and a push consumer keeps running on the same shard beside it — at the cost of a
     * write per claimed message, and of an occasional duplicate where the owner had already read the
     * row before the claim landed.
     */
    MESSAGE,
    /**
     * A batch of messages per lease. Unordered lane only, same trade as {@link #MESSAGE}. Note that
     * the "one write per batch" distinction is notional in a shard-owned engine: a batch claim stamps
     * every row in one statement either way. What differs is how much a session holds at once.
     */
    BATCH,
    /**
     * A whole shard. No per-message write at all. Ordering safe, blocks the shard.
     */
    SHARD
}
