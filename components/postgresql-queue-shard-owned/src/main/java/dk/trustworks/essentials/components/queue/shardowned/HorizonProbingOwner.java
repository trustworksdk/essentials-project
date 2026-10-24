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

import java.sql.*;
import java.util.Set;

/**
 * An owner whose cursor is a safe watermark, and so needs the running write transactions
 * ({@link ShardOwnedStorage#runningWriteTransactionIds}) after every read that leaves it behind.
 * <p>
 * The pump runs a pass in three steps for these owners: every cursor read of the pass first (the batched ones, then
 * {@link #readAhead} for the rest), then ONE probe for every owner that asks for it, then each owner's
 * {@link #pumpOnce}. The probe is therefore after every one of those reads - what the watermark's rule requires of the
 * probe a candidate is recorded with (docs/durable-queue-ordered-routing-design.md §3) - and about as close behind
 * them as an owner's own probe used to be, so the candidates it records retire as soon as they did.
 * {@code pg_stat_activity} is database-wide, so one probe serves the owners of every queue the pump serves; each owner
 * used to take its own, and under load that was the most frequent statement the pumps issued.
 * <p>
 * Taking the probe after the WHOLE pass instead was measured and rejected: it holds more writers that started after
 * the reads, every one of which a candidate then waits for, so the watermark trailed further and the re-reads cost
 * back much of what the probes saved, with a worse latency tail under saturation.
 */
interface HorizonProbingOwner extends LeasedOwner {

    /**
     * Read this pass's rows now, before the pass's probe, unless the pump's batched read already did. Must be followed
     * by {@link #pumpOnce} in the same pass.
     */
    void readAhead(Connection connection) throws SQLException;

    /**
     * @return whether the rows read for this pass leave the watermark behind, so this pass needs a probe
     */
    boolean horizonProbeWanted();

    /**
     * Hand over the probe the pump took after this pass's reads, for the following {@link #pumpOnce} to use instead of
     * probing for itself.
     *
     * @param running     the running write transaction ids
     * @param issuedNanos {@link System#nanoTime()} just BEFORE the probe's statement was issued; the owner uses the
     *                    probe only for rows read before that
     */
    void applyHorizonProbe(Set<Long> running, long issuedNanos);

    /**
     * The metrics of this owner's queue, charged with the probe when it is taken on this owner's behalf.
     */
    ShardOwnerMetrics ownerMetrics();
}
