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
 * Thrown by {@code WalReplicationTailer} when the replication stream has received no WAL
 * messages for longer than the configured
 * {@link CdcProperties.WalReplicationTailerProperties#getMaxIdleDuration() maxIdleDuration}.
 * Typically means the TCP socket is silently half-open (server-side backend gone but the
 * client's {@code readPending()} is still returning {@code null} indefinitely) — a condition
 * the idle LSN push can't always surface because the status push may simply queue in the
 * outbound socket buffer without erroring.
 * <p>
 * Propagated up to {@code runPollLoop}, which treats it like any other streaming exception:
 * the replication connection is closed and the tailer reconnects via the normal backoff
 * path. Marking this as a distinct exception type (rather than a generic {@link RuntimeException})
 * makes it easy to grep for in logs and keeps the error-handler's decision predictable —
 * stale-stream is always a RETRY_CONNECTION, never a STOP.
 */
public class StaleReplicationStreamException extends RuntimeException {
    public StaleReplicationStreamException(String message) {
        super(message);
    }
}
