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

package dk.trustworks.essentials.examples.perflab.queuedesign;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.AcknowledgeMessageAsHandled;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.interceptor.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * A prototype of batched acknowledgement, used by {@code QueueDesignAbScenario} to measure what deferring
 * and grouping the per-message ack would buy. Not production code and not a proposal to ship as an
 * interceptor — it exists to put a number on the lever before anyone commits to a queue redesign.
 *
 * <h2>What it replaces</h2>
 * Today every handled message issues its own {@code DELETE FROM durable_queues WHERE id = :id} in its own
 * transaction ({@code SingleOperationTransaction} is the default mode). The batch fetch amortises across a
 * whole tick, so this delete is <em>the</em> per-message commit. When enabled, this interceptor short-circuits
 * {@link AcknowledgeMessageAsHandled}, buffers the id, and a flusher issues one
 * {@code DELETE ... WHERE id IN (...)} per interval or per {@code maxBatchSize}, whichever comes first.
 *
 * <h2>Why the measurement is a lower bound, not the full win</h2>
 * {@code PostgresqlDurableQueues.acknowledgeMessageAsHandled} wraps the interceptor chain in
 * {@code unitOfWorkFactory.withUnitOfWork(...)}, so the unit of work — and therefore the Hikari connection
 * acquisition — happens <em>before</em> any interceptor gets a say. Short-circuiting here removes the DELETE
 * statement and its WAL-writing commit, but the connection is still borrowed and returned per message. A real
 * implementation would batch at a level that skips that too, so whatever this measures, the genuine change
 * is worth at least that much.
 *
 * <h2>Delivery semantics</h2>
 * At-least-once is preserved: an un-flushed row still has {@code is_being_delivered = TRUE}, so the fetch
 * query will not hand it out again, and a crash before the flush leaves it to be recovered by
 * {@code resetMessagesStuckBeingDelivered} after {@code messageHandlingTimeout} — exactly the path an
 * un-acked in-flight message already takes today. The flush interval must stay well under that timeout, or
 * messages start being redelivered while their ack is still buffered.
 */
@Component
@InterceptorOrder(1)
public class BatchingAcknowledgeInterceptor implements DurableQueuesInterceptor {
    private static final Logger log = LoggerFactory.getLogger(BatchingAcknowledgeInterceptor.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final String                                                        sharedQueueTableName;
    private final Queue<QueueEntryId>                                           pendingAcknowledgements = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean                                                 enabled                 = new AtomicBoolean(false);
    private final AtomicLong                                                    flushedMessages         = new AtomicLong();
    private final AtomicLong                                                    flushCount              = new AtomicLong();

    private volatile ScheduledExecutorService flusher;
    private volatile int                      maxBatchSize = 200;

    public BatchingAcknowledgeInterceptor(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                          @Value("${essentials.durable-queues.shared-queue-table-name:durable_queues}") String sharedQueueTableName) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        // The table name is concatenated into the DELETE below, so it goes through the same guard the
        // framework's own SQL does. See the SQL-injection note in the root CLAUDE.md.
        PostgresqlUtil.checkIsValidTableOrColumnName(sharedQueueTableName);
        this.sharedQueueTableName = sharedQueueTableName;
    }

    @Override
    public void setDurableQueues(DurableQueues durableQueues) {
        // Nothing needed - the interceptor writes to the queue table directly rather than going back
        // through the DurableQueues API, which is the whole point of batching.
    }

    /**
     * Starts buffering acks and flushing them in batches. Idempotent.
     *
     * @param flushInterval how often to flush; must stay well below {@code messageHandlingTimeout}
     * @param maxBatchSize  flush early once this many ids are buffered
     */
    public void enable(Duration flushInterval, int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
        if (!enabled.compareAndSet(false, true)) {
            return;
        }
        flushedMessages.set(0);
        flushCount.set(0);
        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "perf-lab-ack-batch-flusher");
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(this::flushQuietly,
                                       flushInterval.toMillis(),
                                       flushInterval.toMillis(),
                                       TimeUnit.MILLISECONDS);
        log.info("Batched acknowledgement enabled - flushInterval={}, maxBatchSize={}", flushInterval, maxBatchSize);
    }

    /**
     * Stops buffering and drains whatever is still pending, so a case cannot leave rows behind for the next
     * one to trip over.
     */
    public void disableAndDrain() {
        if (!enabled.compareAndSet(true, false)) {
            return;
        }
        var currentFlusher = flusher;
        if (currentFlusher != null) {
            currentFlusher.shutdownNow();
        }
        while (!pendingAcknowledgements.isEmpty()) {
            flushQuietly();
        }
        log.info("Batched acknowledgement disabled - flushed {} messages in {} batches", flushedMessages.get(), flushCount.get());
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public long getFlushedMessages() {
        return flushedMessages.get();
    }

    public long getFlushCount() {
        return flushCount.get();
    }

    public int getPendingCount() {
        return pendingAcknowledgements.size();
    }

    @Override
    public boolean intercept(AcknowledgeMessageAsHandled operation,
                             InterceptorChain<AcknowledgeMessageAsHandled, Boolean, DurableQueuesInterceptor> interceptorChain) {
        if (!enabled.get()) {
            return interceptorChain.proceed();
        }
        pendingAcknowledgements.add(operation.getQueueEntryId());
        if (pendingAcknowledgements.size() >= maxBatchSize) {
            flushQuietly();
        }
        // Reported as acknowledged to the caller. The row is deleted at the next flush; until then it stays
        // is_being_delivered=TRUE, so it is neither re-fetched nor lost.
        return true;
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (Exception e) {
            // A failed flush is not a lost message: the ids stay buffered for the next attempt, and if the
            // process dies the rows are recovered by resetMessagesStuckBeingDelivered.
            log.warn("Batched acknowledgement flush failed - {} ids still pending", pendingAcknowledgements.size(), e);
        }
    }

    private void flush() {
        var batch = new ArrayList<String>(maxBatchSize);
        QueueEntryId next;
        while (batch.size() < maxBatchSize && (next = pendingAcknowledgements.poll()) != null) {
            batch.add(next.toString());
        }
        if (batch.isEmpty()) {
            return;
        }

        try {
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                      .createUpdate("DELETE FROM " + sharedQueueTableName + " WHERE id IN (<ids>)")
                                                                      .bindList("ids", batch)
                                                                      .execute());
            flushedMessages.addAndGet(batch.size());
            flushCount.incrementAndGet();
        } catch (RuntimeException e) {
            // Put them back rather than dropping them on the floor.
            batch.forEach(id -> pendingAcknowledgements.add(QueueEntryId.of(id)));
            throw e;
        }
    }
}
