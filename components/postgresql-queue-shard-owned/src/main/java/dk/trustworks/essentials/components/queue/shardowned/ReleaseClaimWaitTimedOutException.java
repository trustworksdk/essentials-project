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

import dk.trustworks.essentials.components.queue.shardowned.spi.KeptUnitRelease;

import java.sql.SQLTransientException;

/**
 * What {@link ReleaseChargeLedger#charge} throws when a release of the owner's lease row held its claim on the owner
 * ({@link ReleaseChargeLedger#claimReleaseUnlessMoveOwed}) for longer than the calling thread's network wait bound
 * ({@link NetworkWaitBound}): the charge did not start, so nothing was charged, dead-lettered or recorded. Its own type,
 * so the engine tells this wait, which it gave up itself, from a statement that failed on the server - a PostgreSQL
 * {@code lock_timeout} among them, which carries the same SQL state. A release on request
 * ({@code ShardOwnedQueue.releaseKeptOrderedUnit}) catches it and answers
 * {@link KeptUnitRelease.Outcome#CLAIM_WAIT_TIMED_OUT}; on the other release paths it is a failed charge like any other.
 * <p>
 * Engine-internal: it never leaves {@code MessageQueue}. Its SQL state is {@link ReleaseChargeLedger#RELEASE_CLAIM_WAIT_TIMED_OUT}
 * ({@code 55P03}), outside class {@code 08}, so it is not taken for a failure that may have gone through on the server
 * ({@link LeaseRowState#mayHaveGoneThrough}): no statement was sent.
 */
final class ReleaseClaimWaitTimedOutException extends SQLTransientException {
    private static final long serialVersionUID = 1L;

    ReleaseClaimWaitTimedOutException(String reason) {
        super(reason, ReleaseChargeLedger.RELEASE_CLAIM_WAIT_TIMED_OUT);
    }
}
