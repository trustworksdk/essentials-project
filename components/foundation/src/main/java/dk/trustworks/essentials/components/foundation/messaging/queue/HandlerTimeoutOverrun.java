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

import org.slf4j.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/**
 * Notices a handler that ran longer than its queue gives a delivery before it takes the message back as stuck
 * ({@link DurableQueues#maxUnservedAfterInstanceLoss()}: {@code message-handling-timeout} on {@code PostgresqlDurableQueues}
 * and MongoDB). Past that point the queue may already have delivered the message a second time, so two attempts ran
 * at once - for an {@link OrderedMessage}, the same key in two handlers.
 * <p>
 * Counted every time and logged at most once a minute per queue: a handler that is slow is usually slow every time, and
 * a WARN per message would bury the one line that matters. The shard-owned engine has its own (its lease, not a
 * timeout, bounds a delivery there).
 */
final class HandlerTimeoutOverrun {
    private static final Logger log              = LoggerFactory.getLogger(HandlerTimeoutOverrun.class);
    private static final long   WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final AtomicLong nextWarningNanos = new AtomicLong(System.nanoTime());
    private final LongAdder  overruns         = new LongAdder();

    /**
     * @param queueName             the queue the handler consumed from
     * @param handlerStartedAtNanos when the handler was called ({@link System#nanoTime()})
     * @param timeout               the queue's window; nothing is checked when empty
     */
    void check(QueueName queueName, long handlerStartedAtNanos, Optional<Duration> timeout) {
        if (timeout.isEmpty()) {
            return;
        }
        var now     = System.nanoTime();
        var elapsed = now - handlerStartedAtNanos;
        if (elapsed <= timeout.get().toNanos()) {
            return;
        }
        overruns.increment();
        var next = nextWarningNanos.get();
        if (now - next >= 0 && nextWarningNanos.compareAndSet(next, now + WARN_EVERY_NANOS)) {
            log.warn("[{}] A message handler ran {} ms, longer than the {} ms the queue gives a delivery " +
                             "(message-handling-timeout). The queue may already have taken the message back as stuck and " +
                             "delivered it again, so two attempts ran at once - for an ordered message, the same key in two " +
                             "handlers. Make the handler shorter (time out its blocking calls well inside the timeout) or raise " +
                             "the timeout. {} handler(s) on this queue have outlasted it so far; this is logged at most once a minute",
                     queueName, TimeUnit.NANOSECONDS.toMillis(elapsed), timeout.get().toMillis(), overruns.sum());
        }
    }

    long overruns() {
        return overruns.sum();
    }
}
