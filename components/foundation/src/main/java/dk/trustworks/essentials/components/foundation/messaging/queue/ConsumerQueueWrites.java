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
 */
final class ConsumerQueueWrites {
    private ConsumerQueueWrites() {
    }

    /**
     * Run a queue write with the thread's interrupt flag cleared, restoring it afterwards
     *
     * @param write the queue write
     */
    static void withInterruptFlagCleared(Runnable write) {
        var wasInterrupted = Thread.interrupted();
        try {
            write.run();
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
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