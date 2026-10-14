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

import java.util.function.Supplier;

/**
 * The one rule for a queue write a consumer makes from a thread that {@code stop()} may have interrupted: it runs with
 * the thread's interrupt flag cleared, and the flag is restored afterwards. A handler that caught the interrupt usually
 * re-sets the flag (Kafka does), and the MongoDB driver refuses to check out a connection on an interrupted thread,
 * which would leave the message marked as being delivered until the stuck-message reset.
 * <p>
 * {@link DefaultDurableQueueConsumer} and {@link CentralizedMessageFetcher} send every write that ends a delivery
 * through it: the acknowledgement, the retry (a handler failure or a manual redelivery request), the dead-letter mark,
 * and the release or hand-back of a message after {@code stop()}.
 * <p>
 * A handler that returns normally with the interrupt flag set while its consumer is stopping is <b>not</b>
 * acknowledged: it has not necessarily finished its work (a Kafka send cut short, a blocking call that gave up on the
 * interrupt), and acknowledging would delete a message that was never processed. Both consumers hand it back for
 * immediate redelivery instead, with {@code retryMessage(id, cause, Duration.ZERO)} - see
 * {@link #returnedInterruptedByStop(boolean)}. A handler that returns with the flag set while its consumer is still
 * running was not interrupted by a stop: it is acknowledged, with the flag cleared for the write as usual. So is one
 * that returns with the flag clear while its consumer is stopping.
 * <p>
 * This is narrower than the shard-owned engine's rule, and differs from it in two ways. {@code ShardOwner} refuses to
 * acknowledge on the interrupt flag alone, whether or not its consumer is stopping (it counts the delivery as
 * {@code abandonedOnInterrupt}); these consumers require the flag <b>and</b> a stopping consumer, the only time they
 * interrupt a handler themselves. And {@code ShardOwner}
 * charges no attempt: it simply leaves the message for the next owner. These consumers hand it back through
 * {@code retryMessage}, which always counts one redelivery attempt.
 */
final class ConsumerQueueWrites {
    private ConsumerQueueWrites() {
    }

    /**
     * Whether the handler that just returned normally on this thread may have abandoned its work because its
     * consumer's {@code stop()} interrupted it: the consumer is stopping and the thread's interrupt flag is set. The
     * message is then handed back instead of acknowledged.
     *
     * @param consumerStarted whether the consumer that delivered the message is still started
     * @return {@code true} when the message must be handed back rather than acknowledged
     */
    static boolean returnedInterruptedByStop(boolean consumerStarted) {
        return !consumerStarted && Thread.currentThread().isInterrupted();
    }

    /**
     * @return the cause recorded as the last delivery error of a message handed back because
     * {@link #returnedInterruptedByStop(boolean)}
     */
    static InterruptedException returnedInterruptedByStopCause() {
        return new InterruptedException("The message handler returned while interrupted by its consumer stopping; it may not have finished its work, " +
                                                "so the message is redelivered instead of acknowledged");
    }

    /**
     * Run a queue write with the thread's interrupt flag cleared, restoring it afterwards
     *
     * @param write the queue write
     */
    static void withInterruptFlagCleared(Runnable write) {
        callWithInterruptFlagCleared(() -> {
            write.run();
            return null;
        });
    }

    /**
     * Run a queue write that returns a value with the thread's interrupt flag cleared, restoring it afterwards
     *
     * @param write the queue write
     * @param <R>   the type the write returns
     * @return what the write returned
     */
    static <R> R callWithInterruptFlagCleared(Supplier<R> write) {
        var wasInterrupted = Thread.interrupted();
        try {
            return write.get();
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}