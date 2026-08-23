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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Configuration for {@link BatchedAcknowledgementBuffer}, so that enabling batched acknowledgement adds one
 * parameter to a storage implementation's constructor rather than three.
 *
 * @param enabled       whether acknowledgements are coalesced into batches. Off by default: batching widens the
 *                      redelivery window by up to one flush interval, which is a semantic change a deployment
 *                      should opt into rather than inherit from an upgrade
 * @param maxBatchSize  flush once this many acknowledgements are pending
 * @param flushInterval flush at least this often, so a trickle of messages is not left buffered. Must stay
 *                      well below the message-handling timeout — {@link BatchedAcknowledgementBuffer} rejects a
 *                      value that could race {@code resetMessagesStuckBeingDelivered}
 */
public record BatchedAcknowledgementSettings(boolean enabled,
                                             int maxBatchSize,
                                             Duration flushInterval) {
    /**
     * 64 acknowledgements per flush. Large enough that the transaction cost is amortised to insignificance —
     * the measured penalty is for one transaction <em>per message</em>, and that is gone by the time a batch
     * holds tens — and small enough that a crash loses only a bounded number of acknowledgements to
     * redelivery.
     */
    public static final int DEFAULT_MAX_BATCH_SIZE = 64;

    /**
     * 50 ms. Chosen against the 30 s default message-handling timeout, which it must stay far below, and
     * against delivery latency: a message whose acknowledgement is buffered still occupies a worker slot's
     * worth of in-flight accounting, so a long interval would throttle a small queue.
     */
    public static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofMillis(50);

    public BatchedAcknowledgementSettings {
        requireNonNull(flushInterval, "No flushInterval provided");
        requireTrue(maxBatchSize > 0, "maxBatchSize must be > 0");
    }

    /**
     * @return settings with batching switched off — today's behaviour, one transaction per acknowledgement
     */
    public static BatchedAcknowledgementSettings disabled() {
        return new BatchedAcknowledgementSettings(false, DEFAULT_MAX_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL);
    }

    /**
     * @return settings with batching switched on at the defaults. Named {@code enabledWithDefaults} rather
     * than {@code enabled} because the record component of that name already owns the accessor
     */
    public static BatchedAcknowledgementSettings enabledWithDefaults() {
        return new BatchedAcknowledgementSettings(true, DEFAULT_MAX_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL);
    }
}
