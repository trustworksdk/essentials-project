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

import java.sql.SQLException;
import java.util.*;

/**
 * Explicit ownership for a caller that wants to pull rather than be called.
 * <p>
 * The three requirements the old pull method bundled are separated here. A caller that needs the
 * message inside a transaction it controls acknowledges within its own unit of work; a caller driving
 * its own loop calls {@link #poll}; a caller with a long-running handler holds a session whose lease
 * it extends, rather than fighting a queue-wide timeout that has to suit everyone at once.
 * <p>
 * The session is the thing that holds the right to acknowledge. That is not ceremony: under ownership
 * an acknowledgement from something that does not hold the lease is exactly the write that must be
 * refused, and making the right explicit is what lets it be checked.
 */
public interface QueueSession extends AutoCloseable {

    /**
     * @param max upper bound on messages returned; may return fewer, including none
     */
    List<PulledMessage> poll(int max) throws SQLException;

    /**
     * Acknowledge handled messages. Rejected if this session no longer holds its lease, in which case
     * the messages stay for whoever does hold it.
     *
     * @return true if the acknowledgement was accepted
     */
    boolean acknowledge(Collection<MessageId> ids) throws SQLException;

    /**
     * Fail a message, applying the redelivery policy: another attempt after a backoff, or the dead
     * letter lane once the policy is exhausted.
     */
    void fail(MessageId id, Throwable cause) throws SQLException;

    /**
     * Push the lease out, for a handler that is still working. A session that lets its lease lapse is
     * superseded and its acknowledgements stop being accepted.
     *
     * @return true if the lease was extended; false if it had already been lost
     */
    boolean extendLease() throws SQLException;

    @Override
    void close();

    record PulledMessage(MessageId id, String key, byte[] payload, int payloadType, int attempts) {
    }
}
