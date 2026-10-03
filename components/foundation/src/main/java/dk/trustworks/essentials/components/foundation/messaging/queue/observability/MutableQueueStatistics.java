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

package dk.trustworks.essentials.components.foundation.messaging.queue.observability;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.shared.Exceptions;

import java.time.*;
import java.util.concurrent.atomic.*;

/**
 * The mutable counters behind a {@link QueueStatistics} snapshot — one instance per tracked queue.
 * <p>
 * Written from message-delivery threads, so every mutator stays allocation-free and lock-free: {@link LongAdder}
 * for counters, plain volatile writes for "last seen" values and a compare-and-set loop for the maximum duration.
 * Timestamps are kept as epoch milliseconds where {@code 0} means "never happened", which the snapshot turns into
 * a {@code null} {@link Instant}.
 * <p>
 * Mirrors {@code MutableSubscriptionStatistics} in the event store deliberately — the admin surface presents both
 * side by side, so a reader should not have to learn two idioms.
 */
class MutableQueueStatistics {
    /** Longer failure texts are truncated - the registry must not become a log. */
    private static final int MAX_FAILURE_REASON_LENGTH = 512;

    private final QueueName queueName;
    private final Clock     clock;
    private final long      statisticsSinceEpochMillis;

    private final LongAdder messagesHandled      = new LongAdder();
    private final LongAdder messagesRetried      = new LongAdder();
    private final LongAdder redeliveryRequests   = new LongAdder();
    private final LongAdder messagesDeadLettered = new LongAdder();

    private final LongAdder  totalHandlerNanos = new LongAdder();
    private final AtomicLong maxHandlerNanos   = new AtomicLong();

    private volatile long   lastHandledAtEpochMillis;
    private volatile long   lastFailureAtEpochMillis;
    private volatile String lastFailureReason;

    MutableQueueStatistics(QueueName queueName, Clock clock) {
        this.queueName = queueName;
        this.clock = clock;
        this.statisticsSinceEpochMillis = clock.millis();
    }

    void recordHandled(Duration handlerDuration) {
        messagesHandled.increment();
        lastHandledAtEpochMillis = clock.millis();
        if (handlerDuration != null) {
            var nanos = handlerDuration.toNanos();
            totalHandlerNanos.add(nanos);
            recordMax(maxHandlerNanos, nanos);
        }
    }

    void recordRedeliveryRequested() {
        redeliveryRequests.increment();
    }

    void recordRetried(Throwable cause) {
        messagesRetried.increment();
        recordFailure(cause);
    }

    void recordDeadLettered(Throwable cause) {
        messagesDeadLettered.increment();
        recordFailure(cause);
    }

    private void recordFailure(Throwable cause) {
        lastFailureAtEpochMillis = clock.millis();
        lastFailureReason = renderFailureReason(cause);
    }

    /**
     * @return an immutable snapshot of the counters as of now. Counters are read one at a time, so a snapshot taken
     * while messages are flowing is eventually consistent across fields - never torn within a field
     */
    QueueStatistics snapshot() {
        var handled = messagesHandled.sum();
        return new QueueStatistics(
                queueName,
                Instant.ofEpochMilli(statisticsSinceEpochMillis),
                new QueueStatistics.Delivery(
                        handled,
                        handled > 0 ? Duration.ofNanos(totalHandlerNanos.sum() / handled) : null,
                        handled > 0 ? Duration.ofNanos(maxHandlerNanos.get()) : null,
                        instantOrNull(lastHandledAtEpochMillis)),
                new QueueStatistics.Outcomes(
                        messagesRetried.sum(),
                        redeliveryRequests.sum(),
                        messagesDeadLettered.sum(),
                        instantOrNull(lastFailureAtEpochMillis),
                        lastFailureReason));
    }

    private static void recordMax(AtomicLong target, long candidate) {
        long current;
        while ((current = target.get()) < candidate && !target.compareAndSet(current, candidate)) {
            // Another thread moved the maximum in between - re-read and retry
        }
    }

    /**
     * Render the <em>root</em> cause, not the throwable as given.
     * <p>
     * A {@code @MessageHandler} throw always reaches the observer wrapped — {@code UnitOfWorkException →
     * ReflectionException → InvocationTargetException → yours} — so rendering the outermost type would make
     * almost every queue's {@code lastFailureReason} read "UnitOfWorkException: …", which distinguishes nothing.
     * The handler's own exception is what an operator is looking for.
     */
    private static String renderFailureReason(Throwable cause) {
        if (cause == null) {
            return null;
        }
        var rootCause = Exceptions.getRootCause(cause);
        var rendered  = rootCause != null ? rootCause : cause;
        var reason    = rendered.getClass().getSimpleName() + (rendered.getMessage() != null ? ": " + rendered.getMessage() : "");
        return reason.length() > MAX_FAILURE_REASON_LENGTH ? reason.substring(0, MAX_FAILURE_REASON_LENGTH) : reason;
    }

    private static Instant instantOrNull(long epochMillis) {
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis) : null;
    }
}
