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

import dk.trustworks.essentials.components.foundation.Lifecycle;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Coalesces message acknowledgements so that a batch of handled messages is acknowledged in one
 * {@link DurableQueues#acknowledgeMessagesAsHandled(java.util.Collection)} call — one statement inside one
 * transaction — instead of one transaction per message.
 *
 * <h2>Why</h2>
 * The acknowledgement is the queue's dominant per-message cost, and the cost is the transaction rather than
 * the statement — <em>in a raw-SQL harness</em>. Measured at <strong>16.5x</strong> on drain time in a raw-SQL harness — but that does <b>not</b>
 * reproduce through the component, where it measures <strong>1.02x</strong> on a backlog drain and
 * <strong>1.00x</strong> in steady state with a worse p99. The acknowledgement transaction is simply not the
 * bottleneck once a connection pool and a real consumer are in the picture. See
 * {@code docs/durable-queues-measurements.md} §2, "Claims withdrawn under measurement".
 * <p>
 * An earlier
 * interceptor-level prototype measured only 1.13x because it could remove the {@code DELETE} but not the
 * surrounding {@code UnitOfWork}; this buffer removes both.
 *
 * <h2>Ordered messages must not be buffered</h2>
 * The caller is responsible for keeping {@link QueuedMessage.DeliveryMode#IN_ORDER} messages out of here.
 * The per-key barrier infers completion from the <em>absence</em> of a row with a lower {@code key_order}, so
 * an acknowledgement still sitting in this buffer blocks every later message for that key until the next
 * flush. Deferring ordered acknowledgements measured 0.82x — actively worse than not batching at all. Until
 * completion is decoupled from row deletion (the per-key cursor design), ordered delivery cannot batch.
 *
 * <h2>Delivery semantics are unchanged</h2>
 * At-least-once is preserved and the redelivery window widens by at most one flush interval. A buffered
 * acknowledgement's row still has {@code is_being_delivered = TRUE}, so no fetch hands it out again; if this
 * process dies before the flush, the row is recovered by {@code resetMessagesStuckBeingDelivered} after the
 * message-handling timeout — the same path an un-acknowledged in-flight message already takes today.
 * <p>
 * That recovery path is also the reason {@link #flushInterval} must stay well below the message-handling
 * timeout. If it does not, the stuck-reset resurrects messages whose acknowledgement is merely buffered and
 * they are delivered a second time. The constructor enforces this rather than documenting it.
 */
public final class BatchedAcknowledgementBuffer implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(BatchedAcknowledgementBuffer.class);

    /**
     * The flush interval must not exceed this fraction of the message-handling timeout, or a buffered
     * acknowledgement can lose the race against {@code resetMessagesStuckBeingDelivered} and its message is
     * delivered twice. A quarter leaves room for a flush that is itself slow.
     */
    private static final double MAX_FLUSH_INTERVAL_FRACTION_OF_HANDLING_TIMEOUT = 0.25d;

    private final DurableQueues           durableQueues;
    private final int                     maxBatchSize;
    private final Duration                flushInterval;
    private final Queue<QueueEntryId>     pending    = new ConcurrentLinkedQueue<>();
    private final AtomicInteger           pendingSize = new AtomicInteger();
    private final AtomicBoolean           started    = new AtomicBoolean();
    private final AtomicLong              acknowledgedMessages = new AtomicLong();
    private final AtomicLong              flushes    = new AtomicLong();
    /**
     * Serialises flushes. Several worker threads can cross the size threshold at once, and without this each
     * would issue its own statement — reintroducing exactly the per-message round trip being removed.
     */
    private final ReentrantLock           flushLock  = new ReentrantLock();

    private volatile ScheduledExecutorService flusher;

    /**
     * @param durableQueues          the queues to acknowledge against
     * @param maxBatchSize           flush once this many acknowledgements are pending
     * @param flushInterval          flush at least this often, so a trickle of messages is not left buffered
     * @param messageHandlingTimeout the timeout after which {@code resetMessagesStuckBeingDelivered} considers
     *                               an in-flight message abandoned. Used only to reject a {@code flushInterval}
     *                               that would race it
     */
    public BatchedAcknowledgementBuffer(DurableQueues durableQueues,
                                        int maxBatchSize,
                                        Duration flushInterval,
                                        Duration messageHandlingTimeout) {
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues provided");
        requireTrue(maxBatchSize > 0, "maxBatchSize must be > 0");
        this.maxBatchSize = maxBatchSize;
        this.flushInterval = requireNonNull(flushInterval, "No flushInterval provided");
        requireTrue(!flushInterval.isNegative() && !flushInterval.isZero(), "flushInterval must be positive");
        requireNonNull(messageHandlingTimeout, "No messageHandlingTimeout provided");
        var maxAllowedFlushIntervalMs = (long) (messageHandlingTimeout.toMillis() * MAX_FLUSH_INTERVAL_FRACTION_OF_HANDLING_TIMEOUT);
        requireTrue(flushInterval.toMillis() <= maxAllowedFlushIntervalMs,
                    "flushInterval " + flushInterval + " is too long relative to the messageHandlingTimeout " + messageHandlingTimeout
                            + " - it must be at most " + maxAllowedFlushIntervalMs + " ms, otherwise resetMessagesStuckBeingDelivered "
                            + "can resurrect a message whose acknowledgement is still buffered and it will be delivered twice");
    }

    @Override
    public void start() {
        if (started.compareAndSet(false, true)) {
            flusher = Executors.newScheduledThreadPool(1, runnable -> {
                var thread = new Thread(runnable);
                thread.setName("DurableQueues-AcknowledgementFlusher");
                thread.setDaemon(true);
                return thread;
            });
            flusher.scheduleWithFixedDelay(this::flushQuietly,
                                           flushInterval.toMillis(),
                                           flushInterval.toMillis(),
                                           TimeUnit.MILLISECONDS);
            log.info("Started batched acknowledgement with maxBatchSize {} and flushInterval {}", maxBatchSize, flushInterval);
        }
    }

    /**
     * Flushes what is buffered before shutting the flusher down. Without the final flush a graceful stop
     * would leave handled messages looking in-flight, and every one of them would be redelivered after the
     * handling timeout.
     */
    @Override
    public void stop() {
        if (started.compareAndSet(true, false)) {
            var currentFlusher = flusher;
            if (currentFlusher != null) {
                currentFlusher.shutdown();
                flusher = null;
            }
            flushQuietly();
            log.info("Stopped batched acknowledgement after {} flushes covering {} messages", flushes.get(), acknowledgedMessages.get());
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Buffer an acknowledgement, flushing synchronously on the calling thread if the batch is now full.
     * <p>
     * Flushing on the caller rather than signalling the flusher keeps the backpressure where it belongs: if
     * acknowledgement cannot keep up, the worker that produced the overflow pays for it instead of the buffer
     * growing without bound.
     *
     * @param queueEntryId the message to acknowledge
     */
    public void acknowledge(QueueEntryId queueEntryId) {
        requireNonNull(queueEntryId, "No queueEntryId provided");
        pending.add(queueEntryId);
        if (pendingSize.incrementAndGet() >= maxBatchSize) {
            flushQuietly();
        }
    }

    /**
     * Drain and acknowledge everything currently buffered.
     *
     * @return the number of messages acknowledged
     */
    public int flush() {
        // tryLock rather than lock: if another thread is already flushing it will drain whatever this thread
        // just added, so waiting here would only serialise workers behind a statement that is already covering
        // their work. The scheduled flush is the backstop for anything a lost race leaves behind.
        if (!flushLock.tryLock()) {
            return 0;
        }
        try {
            var batch = new ArrayList<QueueEntryId>(maxBatchSize);
            QueueEntryId queueEntryId;
            while (batch.size() < maxBatchSize && (queueEntryId = pending.poll()) != null) {
                pendingSize.decrementAndGet();
                batch.add(queueEntryId);
            }
            if (batch.isEmpty()) {
                return 0;
            }
            var acknowledged = durableQueues.acknowledgeMessagesAsHandled(batch);
            flushes.incrementAndGet();
            acknowledgedMessages.addAndGet(acknowledged);
            if (acknowledged != batch.size()) {
                log.debug("Acknowledged {} of {} buffered messages - the remainder were already deleted or had been marked as Dead-Letter-Messages",
                          acknowledged,
                          batch.size());
            }
            return acknowledged;
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * Flush, treating failure the way a failed single-message acknowledgement is already treated.
     * <p>
     * The drained ids are deliberately <b>not</b> put back. Their rows still carry
     * {@code is_being_delivered = TRUE}, so {@code resetMessagesStuckBeingDelivered} recovers them after the
     * handling timeout — the same recovery a failed single acknowledgement relies on. Re-queueing them would
     * turn one poisoned batch into an endless retry that also blocks every later acknowledgement behind it.
     */
    private void flushQuietly() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("Failed to flush buffered acknowledgements - the affected messages stay marked as being delivered "
                             + "and will be recovered by resetMessagesStuckBeingDelivered: {}", e.getMessage());
        }
    }

    /**
     * @return the number of acknowledgements currently buffered, for tests and diagnostics
     */
    public int pendingAcknowledgements() {
        return pendingSize.get();
    }

    /**
     * @return the number of flushes issued so far, for tests and diagnostics. A batching implementation that
     * is not actually batching shows up here as a flush count equal to the message count
     */
    public long flushCount() {
        return flushes.get();
    }
}
